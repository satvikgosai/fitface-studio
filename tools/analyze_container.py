#!/usr/bin/env python3
"""Byte-level analyzer for SM-R390 OPPO watch-face containers.

Re-derives the container structure from raw bytes and accounts for every one of
them. It shares no code with `:core:format`, so agreement between the two is
independent corroboration rather than a tautology.

Accepts `.bin` containers, the `.apk` packages they ship inside, or directories
of either, and writes per face:

    <out>/index.json            what was analysed, for build_report.py
    <out>/<face>/model.json     complete structural model + coverage audit
    <out>/<face>/entries/*.bin  every directory-entry payload, verbatim
    <out>/<face>/images/*.png   every embedded raster decoded
    <out>/<face>/thumbs/*.png   bounded thumbnails for the visual report

Three checks have to pass before the model is trusted, and all three are recorded
in it: every CRC-16 recomputed and matched, every byte assigned to exactly one
semantic class with zero residual, and the file rebuilt from the parsed pieces
and compared to the original.

Standard library only. Examples:

    python3 tools/analyze_container.py corpus/SM_R390 --out out
    python3 tools/analyze_container.py corpus/packages --out out --skip-images
    python3 tools/analyze_container.py face.bin other.apk --out out
"""

from __future__ import annotations

import argparse
import binascii
import hashlib
import json
import math
import re
import struct
import sys
import zipfile
import zlib
from collections import Counter
from pathlib import Path

from watchface_schema import (
    COMMON_FIELDS,
    OPEN_GAPS,
    SCHEMA_VERSION,
    SOURCE_LABELS,
    WIDGET_TYPES,
)

# ---------------------------------------------------------------- constants

MAGICS = (b"oppo", b"lqsw")
HEADER_SIZE = 32
ENTRY_SIZE = 74
PATH_FIELD = 64
STYLE_MAGIC = 0x12345678
STYLE_HEADER_SIZE = 24
WIDGET_COMMON = 24
WIDGET_MIN_WRITER = 16
IMAGE_HEADER = 12
IMAGE_TRAILER = 4
PREVIEW_STRIDE = 0x18570
PREVIEW_WIDTH = 178
PREVIEW_HEIGHT = 280
PREVIEW_DATA_SIZE = 99_684

FMT_RGB565 = 0x0082
FMT_RGB565A = 0x0080
FMT_INDEXED8 = 0x0088
PALETTE_ENTRIES = 256
PALETTE_BYTES = PALETTE_ENTRIES * 4

FORMATS = {
    FMT_RGB565: ("RGB565", 2, False),
    FMT_RGB565A: ("RGB565+A", 3, True),
    FMT_INDEXED8: ("Indexed8", 1, True),
}

# `./SM-R390_00046_256x402/style0.bin` -> the panel the watch renders in. Read
# from the declared geometry, never from raster 0: a style is not obliged to
# carry a full-panel background raster at all.
PANEL_IN_PATH = re.compile(r"_(\d+)x(\d+)/")

crc16 = lambda b: binascii.crc_hqx(bytes(b), 0xFFFF)


class ContainerError(Exception):
    """The input is not a container this analyzer can parse."""


# ---------------------------------------------------------------- PNG writer

def _chunk(tag: bytes, payload: bytes) -> bytes:
    return (struct.pack(">I", len(payload)) + tag + payload
            + struct.pack(">I", zlib.crc32(tag + payload) & 0xFFFFFFFF))


def write_png(path: Path, width: int, height: int, rows: list[bytes], alpha: bool) -> None:
    """Minimal PNG encoder. `rows` are raw RGB or RGBA scanlines."""
    ihdr = struct.pack(">IIBBBBB", width, height, 8, 6 if alpha else 2, 0, 0, 0)
    raw = b"".join(b"\x00" + row for row in rows)
    path.write_bytes(b"\x89PNG\r\n\x1a\n" + _chunk(b"IHDR", ihdr)
                     + _chunk(b"IDAT", zlib.compress(raw, 6)) + _chunk(b"IEND", b""))


# ---------------------------------------------------------------- pixel decode

def rgb565_to_rgb(value: int) -> tuple[int, int, int]:
    r5, g6, b5 = (value >> 11) & 0x1F, (value >> 5) & 0x3F, value & 0x1F
    return (r5 * 255 + 15) // 31, (g6 * 255 + 31) // 63, (b5 * 255 + 15) // 31


_LUT = [rgb565_to_rgb(v) for v in range(65536)]
_LUT_BYTES = [bytes(c) for c in _LUT]


def _colour_stats(counts: Counter, total: int, key_hex) -> dict:
    return {
        "unique_colors": len(counts),
        "top_colors": [{"key": key_hex(v),
                        "hex": "#%02X%02X%02X" % _colour_of(v),
                        "count": n,
                        "pct": round(100 * n / total, 3)}
                       for v, n in counts.most_common(6)],
    }


def _colour_of(value):
    """`value` is either an RGB565 word or an already-unpacked (r, g, b)."""
    return value if isinstance(value, tuple) else _LUT[value]


def decode_image(payload: memoryview, width: int, height: int, fmt: int):
    """Return (rows, alpha_flag, statistics) for one raster.

    Rows come back as RGB or RGBA scanlines. The three formats are decoded
    separately rather than through one generic loop, because a per-pixel Python
    loop over a 256x402 panel is the whole cost of a corpus sweep.
    """
    _, bpp, alpha = FORMATS[fmt]
    total = width * height
    rows: list[bytes] = []
    a_hist: Counter = Counter()
    opaque = transparent = 0

    if fmt == FMT_INDEXED8:
        # 256-entry BGRA palette, then one index byte per pixel.
        palette = []
        for i in range(PALETTE_ENTRIES):
            b, g, r, a = payload[i * 4:i * 4 + 4]
            palette.append(bytes((r, g, b, a)))
        indices = bytes(payload[PALETTE_BYTES:PALETTE_BYTES + total])
        counts = Counter(indices)
        for i, n in counts.items():
            a = palette[i][3]
            a_hist[a] += n
            if a == 255:
                opaque += n
            elif a == 0:
                transparent += n
        for y in range(height):
            row = indices[y * width:(y + 1) * width]
            rows.append(b"".join(palette[i] for i in row))
        stats = {
            "unique_colors": len({palette[i] for i in counts}),
            "palette_entries_used": len(counts),
            "top_colors": [{"key": f"idx {i}",
                            "hex": "#%02X%02X%02X" % tuple(palette[i][:3]),
                            "count": n,
                            "pct": round(100 * n / total, 3)}
                           for i, n in counts.most_common(6)],
        }
    elif fmt == FMT_RGB565:
        words = struct.unpack(f"<{total}H", bytes(payload[:total * 2]))
        counts = Counter(words)
        for y in range(height):
            rows.append(b"".join(
                [_LUT_BYTES[v] for v in words[y * width:(y + 1) * width]]))
        stats = _colour_stats(counts, total, lambda v: f"0x{v:04X}")
    else:
        raw = bytes(payload[:total * 3])
        lo, hi, av = raw[0::3], raw[1::3], raw[2::3]
        words = [low | (high << 8) for low, high in zip(lo, hi)]
        counts = Counter(words)
        a_hist = Counter(av)
        opaque, transparent = a_hist.get(255, 0), a_hist.get(0, 0)
        for y in range(height):
            s, e = y * width, (y + 1) * width
            rows.append(b"".join(
                [_LUT_BYTES[v] + bytes((a,)) for v, a in zip(words[s:e], av[s:e])]))
        stats = _colour_stats(counts, total, lambda v: f"0x{v:04X}")

    if alpha:
        stats.update({
            "alpha_levels": len(a_hist),
            "fully_opaque_px": opaque,
            "fully_transparent_px": transparent,
            "partial_alpha_px": total - opaque - transparent,
            "transparent_pct": round(100 * transparent / total, 2),
        })
    return rows, alpha, stats


def thumbnail(rows: list[bytes], width: int, height: int, alpha: bool, cap: int):
    """Nearest-neighbour downscale, checkerboard-composited if it has alpha."""
    step = max(1, math.ceil(max(width, height) / cap))
    tw, th = max(1, width // step), max(1, height // step)
    ch = 4 if alpha else 3
    out_rows = []
    for ty in range(th):
        src = rows[min(ty * step, height - 1)]
        line = bytearray()
        for tx in range(tw):
            o = min(tx * step, width - 1) * ch
            r, g, b = src[o], src[o + 1], src[o + 2]
            if alpha:
                a = src[o + 3]
                bg = 0x6B if ((tx // 4) + (ty // 4)) % 2 else 0x4A
                r = (r * a + bg * (255 - a)) // 255
                g = (g * a + bg * (255 - a)) // 255
                b = (b * a + bg * (255 - a)) // 255
            line += bytes((r, g, b))
        out_rows.append(bytes(line))
    return out_rows, tw, th


# ---------------------------------------------------------------- entry parsers

def parse_glyph_table(data: bytes) -> dict:
    """Decode the canonical localized UTF-8 dictionary and prove exact packing."""
    if len(data) < 0x18:
        raise ContainerError("glyph dictionary is shorter than its 24-byte header")
    magic, locale, groups = struct.unpack_from("<III", data, 0)
    reserved = data[0x0C:0x18]
    table_end = 0x18 + groups * 8
    if table_end > len(data):
        raise ContainerError(
            f"glyph dictionary declares {groups} descriptors past EOF")
    descriptors, cursor = [], 0x18
    for i in range(groups):
        length, offset = struct.unpack_from("<II", data, cursor)
        if not 1 <= length <= 64:
            raise ContainerError(
                f"glyph item {i} length {length} is outside the usable bound of 1..64")
        if offset < table_end or offset + length > len(data):
            raise ContainerError(
                f"glyph item {i} range 0x{offset:X}..0x{offset + length:X} is invalid")
        raw = data[offset:offset + length]
        try:
            text = raw.decode("utf-8")
        except UnicodeDecodeError as error:
            raise ContainerError(f"glyph item {i} is not valid UTF-8: {error}") from error
        descriptors.append({
            "index": i, "descriptor_offset": cursor,
            "byte_length": length, "text_offset": offset,
            "text": text, "codepoints": len(text), "hex": raw.hex(),
        })
        cursor += 8
    text_start = cursor
    assigned = [0] * len(data)
    for i in range(text_start):
        assigned[i] += 1
    for d in descriptors:
        for i in range(d["text_offset"], d["text_offset"] + d["byte_length"]):
            assigned[i] += 1
    holes = [i for i, count in enumerate(assigned) if count == 0]
    overlaps = [i for i, count in enumerate(assigned) if count > 1]
    contiguous = all(
        d["text_offset"] == (text_start if i == 0 else
                              descriptors[i - 1]["text_offset"] +
                              descriptors[i - 1]["byte_length"])
        for i, d in enumerate(descriptors)
    )
    exact_end = (not descriptors and text_start == len(data)) or (
        bool(descriptors) and
        descriptors[-1]["text_offset"] + descriptors[-1]["byte_length"] == len(data))
    return {
        "kind": "glyph_table",
        "magic": f"0x{magic:08X}", "magic_ok": magic == STYLE_MAGIC,
        "locale_word": locale, "group_count": groups,
        "reserved_hex": reserved.hex(), "reserved_zero": reserved == bytes(12),
        "descriptor_table": {"offset": 0x18, "size": groups * 8},
        "text_region": {"offset": text_start, "size": len(data) - text_start},
        "groups": descriptors,
        "unaccounted_bytes": len(holes),
        "overlapping_bytes": len(overlaps),
        "canonical_contiguous": contiguous and exact_end and not holes and not overlaps,
        "ascending_offsets": all(
            descriptors[i]["text_offset"] <= descriptors[i + 1]["text_offset"]
            for i in range(len(descriptors) - 1)),
        "lookup": {
            "descriptor_offset_formula": "(requested_index + 3) * 8",
            "header_fields_checked": False,
            "stored_count_bounds_checked": False,
            "fallback_locale": "en",
            "consumer_item_length": "1..64 UTF-8 bytes",
        },
    }


def parse_font_binding(data: bytes) -> dict:
    """92-byte record: 72 language selectors, descriptive role, pixel size."""
    if len(data) != 0x5C:
        raise ContainerError(f"numbered font binding is {len(data)} bytes, expected 92")
    selectors = list(data[:0x48])
    name = data[0x48:0x58].split(b"\x00", 1)[0].decode("ascii", "replace")
    size_word = struct.unpack_from("<I", data, 0x58)[0]
    nonzero = [{"language_index": i, "offset": i, "selector": b}
               for i, b in enumerate(selectors) if b]
    return {
        "kind": "font_binding",
        "family_selectors": selectors,
        "selector_nonzero": nonzero,
        "byte_1_override": selectors[1],
        "normal_runtime_language_range": [0, 68],
        "spare_tail_selectors": selectors[69:72],
        # Retained for old consumers; byte 0 is one language slot, not a record-wide family.
        "family_index": selectors[0],
        "binding_name": name,
        "name_field_hex": data[0x48:0x58].hex(),
        "resource_name_read": False,
        "requested_pixel_size": size_word,
        "point_size": size_word,
        "selector_region": {"offset": 0x00, "size": 0x48},
        "resolver_rule": "use selector[1] when nonzero; otherwise selector[current language]",
        "record_size": len(data),
    }


def parse_setting(data: bytes) -> dict:
    """256-byte identity block."""
    if len(data) != 0x100:
        raise ContainerError(f"setting.bin is {len(data)} bytes, expected exactly 256")
    marker = data[0x00:0x0C]
    magic = struct.unpack_from("<I", data, 0x0C)[0]
    face_id = data[0x10:0x20].split(b"\x00", 1)[0].decode("ascii", "replace")
    gap20 = data[0x20:0x30]
    version = struct.unpack_from("<i", data, 0x30)[0]
    style_count, default_style, property_36, property_37 = data[0x34:0x38]
    slot_a, slot_b = data[0x38:0x78], data[0x78:0xB8]
    tail = data[0xB8:]
    def dec(slot: bytes) -> str:
        return slot[1:].split(b"\x00", 1)[0].decode("ascii", "replace")

    def slot_info(offset: int, slot: bytes) -> dict:
        raw_name = slot[1:].split(b"\x00", 1)[0]
        terminator = 1 + len(raw_name)
        return {
            "offset": offset,
            "lead_byte": slot[0],
            "name": raw_name.decode("ascii", "replace"),
            "trailing_zero": slot[terminator:] == bytes(len(slot) - terminator),
        }

    return {
        "kind": "setting",
        "marker_ascii": marker.split(b"\x00", 1)[0].decode("ascii", "replace"),
        "marker_hex": marker.hex(),
        "struct_magic": f"0x{magic:08X}", "magic_ok": magic == STYLE_MAGIC,
        "face_id": face_id, "face_id_field_hex": data[0x10:0x20].hex(),
        "reserved_0x20_hex": gap20.hex(), "reserved_0x20_zero": gap20 == bytes(16),
        "face_version": version,
        "style_count": style_count,
        "default_style": default_style,
        "property_0x36": property_36,
        "property_0x37": property_37,
        "style_count_valid": style_count > 0,
        "default_style_valid": default_style < style_count if style_count else False,
        # Compatibility aliases for report models produced before schema v3.
        "word_0x34": style_count,
        "word_0x36": f"0x{property_37:02X}{property_36:02X}",
        "name_slot_a": slot_info(0x38, slot_a),
        "name_slot_b": slot_info(0x78, slot_b),
        "slots_identical": slot_a == slot_b,
        "tail_region": {"offset": 0xB8, "size": len(tail)},
        "tail_zero": tail == bytes(len(tail)),
        "consumed_ranges": ["0x10..0x1F", "0x30..0x37"],
        "canonical_but_normal_path_unread": ["0x00..0x0F", "0x20..0x2F", "0x38..0xFF"],
        "nonzero_offsets": [i for i, b in enumerate(data) if b],
    }


def scan_images(data: bytes, start: int, end: int, label: str) -> tuple[list[dict], str | None]:
    """Walk a packed image stream; return records and a failure reason if any."""
    out, cursor = [], start
    while cursor < end:
        if cursor + IMAGE_HEADER > end:
            return out, f"{label}: truncated image header at 0x{cursor:X}"
        w, h, fmt, resv, size = struct.unpack_from("<HHHHI", data, cursor)
        if fmt not in FORMATS:
            return out, (f"{label}: noncanonical format 0x{fmt:04X} at 0x{cursor:X}; "
                         "writer accepts only 0x0080, 0x0082 and 0x0088")
        name, bpp, _ = FORMATS[fmt]
        palette = PALETTE_BYTES if fmt == FMT_INDEXED8 else 0
        px = palette + w * h * bpp
        if (w == 0 or h == 0 or size != px + IMAGE_TRAILER
                or cursor + IMAGE_HEADER + size > end):
            return out, f"{label}: implausible image at 0x{cursor:X} ({w}x{h}, {size} B)"
        trailer = data[cursor + IMAGE_HEADER + px:cursor + IMAGE_HEADER + size]
        out.append({
            "index": len(out), "record_offset": cursor,
            "pixel_offset": cursor + IMAGE_HEADER,
            "width": w, "height": h,
            "format": f"0x{fmt:04X}", "format_name": name,
            "format_low_byte": fmt & 0xFF,
            "format_high_byte": fmt >> 8,
            "format_reads_low_byte_only": True,
            "bytes_per_pixel": bpp, "palette_bytes": palette,
            "reserved": resv, "reserved_zero": resv == 0,
            "declared_size": size, "pixel_bytes": px,
            "trailer_size": IMAGE_TRAILER,
            "trailer_hex": trailer.hex(),
            "trailer_zero": trailer == bytes(IMAGE_TRAILER),
            "gui_data_size": size - IMAGE_TRAILER,
            "record_total": IMAGE_HEADER + size,
            "section_relative_offset": cursor - start,
        })
        cursor += IMAGE_HEADER + size
    if cursor != end:
        return out, f"{label}: image stream ended at 0x{cursor:X}, expected 0x{end:X}"
    return out, None


def _decode_scalar(raw: bytes, encoding: str):
    formats = {"u8": "<B", "i8": "<b", "u16": "<H", "i16": "<h",
               "u32": "<I", "i32": "<i"}
    if encoding in formats:
        return struct.unpack(formats[encoding], raw)[0]
    return raw.hex()


def _resolved_field(record: bytes, spec: dict, actual_size: int | None = None) -> dict:
    size = actual_size if actual_size is not None else spec["size"]
    if not isinstance(size, int):
        raise ValueError(f"dynamic field {spec['name']} has no materialised size")
    start, end = spec["offset"], spec["offset"] + size
    raw = record[start:end]
    value = _decode_scalar(raw, spec["encoding"]) if len(raw) == size else None
    return {**spec, "size": size, "raw_hex": raw.hex(), "value": value,
            "in_record": end <= len(record)}


def _decode_comp_parts(record: bytes) -> list[dict]:
    parts = []
    for i in range(4):
        offset = 0x24 + i * 12
        source, fixed_a, fixed_b, dynamic_base = struct.unpack_from("<HHHH", record, offset)
        mode, selector, padding = struct.unpack_from("<BBH", record, offset + 8)
        parts.append({
            "index": i, "offset": offset, "source_id": source,
            "source_label": SOURCE_LABELS.get(source, "") if source != 0xFFFF else "disabled",
            "fixed_dictionary_a": fixed_a, "fixed_dictionary_b": fixed_b,
            "dynamic_dictionary_base": dynamic_base,
            "dynamic_mode": mode,
            "dynamic_mode_name": "dictionary" if mode == 0 else "numeric",
            "numeric_selector": selector,
            "numeric_format": "%d" if selector < 2 or selector >= 7 else f"%0{selector}d",
            "padding": padding,
            "raw_hex": record[offset:offset + 12].hex(),
        })
    return parts


def decode_widget(record: bytes, ordinal: int, record_offset: int) -> dict:
    """Decode one record through its exact per-type schema."""
    wtype, seq, op08 = struct.unpack_from("<III", record, 0)
    index_size = struct.unpack_from("<I", record, 0x0C)[0]
    size, gidx = index_size & 0xFFFF, index_size >> 16
    schema = WIDGET_TYPES[wtype]

    # Type 9 and the inert handlers reinterpret/ignore the common +0x10..+0x17
    # range, so do not emit overlapping field rows for it.
    common_specs = COMMON_FIELDS
    if wtype in (8, 9, 10, 11, 12, 14, 15):
        common_specs = [spec for spec in COMMON_FIELDS if spec["offset"] < 0x10]
    fields = [_resolved_field(record, spec) for spec in common_specs]

    frame_count = None
    for spec in schema["fields"]:
        field_size = spec["size"]
        if field_size == "4 × frame_count":
            count_offset = 0x20 if wtype == 3 else 0x24
            frame_count = record[count_offset]
            field_size = frame_count * 4
        elif field_size == "to record end":
            field_size = max(0, len(record) - spec["offset"])
        fields.append(_resolved_field(record, spec, field_size if isinstance(field_size, int) else None))

    # Classify every byte once. Overlaps would mean the shared schema itself is
    # contradictory and are surfaced in the model rather than silently hidden.
    assigned = [0] * len(record)
    for field in fields:
        for i in range(field["offset"], min(len(record), field["offset"] + field["size"])):
            assigned[i] += 1
    holes = [i for i, count in enumerate(assigned) if count == 0]
    overlaps = [i for i, count in enumerate(assigned) if count > 1]
    if holes:
        # Preserve and expose producer padding beyond an otherwise complete
        # schema. This remains a named unresolved region, never a dropped byte.
        runs, start = [], holes[0]
        previous = holes[0]
        for value in holes[1:]:
            if value != previous + 1:
                runs.append((start, previous + 1))
                start = value
            previous = value
        runs.append((start, previous + 1))
        for start, end in runs:
            fields.append({
                "offset": start, "size": end - start, "name": "unresolved_padding",
                "encoding": "bytes", "role": "unresolved", "access": "not-mapped",
                "description": "Bytes beyond the known layout; preserved verbatim.",
                "raw_hex": record[start:end].hex(), "value": record[start:end].hex(),
                "in_record": True,
            })

    expected_size = schema["minimum"]
    if wtype == 3:
        expected_size = 0x24 + 4 * record[0x20]
    elif wtype == 4:
        expected_size = 0x28 + 4 * record[0x24]
    fixed_schema = wtype not in (8, 9, 10, 11, 12, 14, 15)
    schema_exact = len(record) == expected_size if fixed_schema else None

    pointer_fields = []
    if wtype == 1:
        pointer_fields = [(0x20, "image")]
    elif wtype == 2:
        pointer_fields = [(0x28, "image")]
    elif wtype == 3:
        pointer_fields = [(0x24 + i * 4, f"frame[{i}]") for i in range(record[0x20])]
    elif wtype == 4:
        pointer_fields = [(0x28 + i * 4, f"frame[{i}]") for i in range(record[0x24])]
    elif wtype == 16:
        pointer_fields = [(0x34, "image")]
    elif wtype == 17:
        pointer_fields = [(0x2C, "image")]
    pointers = [{"offset": off, "name": name,
                 "image_rel_offset": struct.unpack_from("<I", record, off)[0]}
                for off, name in pointer_fields if off + 4 <= len(record)]

    alignment = None
    if wtype in (1, 2):
        code, target = struct.unpack_from("<HH", record, 0x1C)
        alignment = {"code": code, "target_global_index": target,
                     "enabled": code != 0xFFFF}
    elif wtype in (5, 13):
        code, target = struct.unpack_from("<HH", record, 0x20)
        alignment = {"code": code, "target_global_index": target,
                     "enabled": code != 0xFFFF}

    font_refs = []
    if wtype == 5 and seq != 116:
        font_refs.append(record[0x28])
    elif wtype == 13:
        font_refs.append(record[0x5E])

    comp_parts = _decode_comp_parts(record) if wtype == 13 else []
    dictionary_refs = []
    if wtype == 5:
        base = struct.unpack_from("<H", record, 0x2C)[0]
        if base != 0xFFFF:
            dictionary_refs.append({"kind": "dynamic_base", "value": base})
    elif wtype == 13:
        for part in comp_parts:
            for key in ("fixed_dictionary_a", "fixed_dictionary_b", "dynamic_dictionary_base"):
                if part[key] != 0xFFFF:
                    dictionary_refs.append({"part": part["index"], "kind": key,
                                            "value": part[key]})
        order = struct.unpack_from("<H", record, 0x62)[0]
        if order not in (0, 0xFFFF):
            dictionary_refs.append({"kind": "order_string", "value": order})

    # Compatibility raw views remain useful when diffing unknown producer data;
    # unlike the old analyzer they are never used to infer pointer semantics.
    word20 = struct.unpack_from("<I", record, 0x20)[0] if len(record) >= 0x24 else 0
    words = []
    for off in range(0x24, len(record) - 3, 4):
        value = struct.unpack_from("<I", record, off)[0]
        words.append({"i": (off - 0x24) // 4, "offset": off,
                      "value": value, "hex": f"0x{value:08X}"})

    x, y = struct.unpack_from("<hh", record, 0x18) if len(record) >= 0x1C else (None, None)
    a, b = struct.unpack_from("<hh", record, 0x1C) if len(record) >= 0x20 else (None, None)
    op10 = struct.unpack_from("<I", record, 0x10)[0] if len(record) >= 0x14 else None
    op14 = struct.unpack_from("<I", record, 0x14)[0] if len(record) >= 0x18 else None
    return {
        "ordinal": ordinal, "record_offset": record_offset, "record_size": size,
        "global_index": gidx, "index_size_word": f"0x{index_size:08X}",
        "type": wtype, "type_name": schema["name"],
        "schema_status": schema["status"],
        "schema_expected_size": expected_size, "schema_exact": schema_exact,
        "sequence_id": seq, "sequence_label": SOURCE_LABELS.get(seq, ""),
        "source_consumption": ("low u16 only" if wtype == 1 else
                               "ignored common word" if wtype in (4, 13) else
                               "must equal 75" if wtype == 9 else "u32 live source"),
        "opaque_0x08": op08, "opaque_0x10": op10, "opaque_0x14": op14,
        "x": x, "y": y, "w": a, "h": b,
        "word_0x20": word20, "word_0x20_hex": f"0x{word20:08X}",
        "type_words": words,
        "decoded_fields": fields,
        "record_unclassified_bytes": len(holes),
        "record_overlapping_bytes": len(overlaps),
        "unresolved_ranges": [
            {"offset": field["offset"], "size": field["size"], "raw_hex": field["raw_hex"]}
            for field in fields if field["role"] == "unresolved"
        ],
        "image_pointer_values": pointers,
        "alignment": alignment,
        "font_binding_refs": font_refs,
        "dictionary_refs": dictionary_refs,
        "comp_parts": comp_parts,
        "tail_size": len(record) % 4, "tail_hex": record[len(record) - len(record) % 4:].hex()
        if len(record) % 4 else "",
        "raw_hex": record.hex(),
    }


def scan_widgets(data: bytes, end: int, declared_count: int,
                 label: str) -> tuple[list[dict], str | None]:
    out, cursor = [], STYLE_HEADER_SIZE
    for ordinal in range(declared_count):
        if cursor + WIDGET_MIN_WRITER > end:
            return out, f"{label}: truncated widget {ordinal} at 0x{cursor:X}"
        wtype = struct.unpack_from("<I", data, cursor)[0]
        if wtype not in WIDGET_TYPES:
            return out, f"{label}: widget {ordinal} has type {wtype}, outside the defined 1..17"
        index_size = struct.unpack_from("<I", data, cursor + 0x0C)[0]
        size = index_size & 0xFFFF
        minimum = WIDGET_TYPES[wtype]["minimum"]
        # The inert types only need 14 readable bytes. A robust
        # file model still requires the complete packed index/size word (16 B).
        robust_minimum = max(WIDGET_MIN_WRITER, minimum)
        if size < robust_minimum or cursor + size > end:
            return out, (f"{label}: type {wtype} widget {ordinal} has size {size}; "
                         f"need at least {robust_minimum} and stream ends at 0x{end:X}")
        if wtype == 3:
            count = data[cursor + 0x20] if size > 0x20 else 0
            if count == 0 or size != 0x24 + count * 4:
                return out, f"{label}: Sprite {ordinal} has inconsistent frame table"
        if wtype == 4:
            if size < 0x28:
                return out, f"{label}: Animation {ordinal} is shorter than 40 bytes"
            count, repeat, period = struct.unpack_from("<BBH", data, cursor + 0x24)
            if not count or not repeat or not period or size != 0x28 + count * 4:
                return out, f"{label}: Animation {ordinal} has inconsistent timing/frame table"
        record = data[cursor:cursor + size]
        out.append(decode_widget(record, ordinal, cursor))
        cursor += size
    if cursor != end:
        return out, (f"{label}: {end - cursor} trailing byte(s) remain inside the "
                     "declared widget stream")
    return out, None


def parse_style(data: bytes, label: str) -> dict:
    if len(data) < STYLE_HEADER_SIZE:
        raise ContainerError(f"{label}: shorter than 24-byte style header")
    magic, wcount, wbytes, ibytes, unk10, ioff = struct.unpack_from("<IIIIII", data, 0)
    font_count = data[0x11]
    computed_image_offset = STYLE_HEADER_SIZE + wbytes
    expected_end = computed_image_offset + ibytes
    info = {
        "kind": "style",
        "magic": f"0x{magic:08X}", "magic_ok": magic == STYLE_MAGIC,
        "declared_widget_count": wcount,
        "declared_widget_bytes": wbytes,
        "declared_image_bytes": ibytes,
        "font_count_word": unk10, "font_count_word_hex": f"0x{unk10:08X}",
        "font_binding_count": font_count,
        "font_binding_count_valid": 1 <= font_count <= 10,
        "font_count_word_canonical": unk10 == font_count << 8,
        "font_count_other_bytes_hex": bytes((data[0x10], data[0x12], data[0x13])).hex(),
        # Compatibility aliases retained for older report models.
        "unknown_0x10": unk10, "unknown_0x10_hex": f"0x{unk10:08X}",
        "declared_image_offset": ioff,
        "computed_image_offset": computed_image_offset,
        "eq_image_offset": {"expected": computed_image_offset, "actual": ioff,
                            "ok": ioff == computed_image_offset,
                            "read_from_widget_bytes_not_this_field": True},
        "eq_entry_size": {"expected": expected_end, "actual": len(data),
                          "ok": expected_end == len(data)},
        "minimum_validation": ["widget_count != 0", "widget_bytes != 0"],
    }
    if wcount == 0 or wbytes == 0:
        raise ContainerError(f"{label}: widget count and widget byte length must both be nonzero")
    if not (STYLE_HEADER_SIZE <= computed_image_offset <= len(data)):
        raise ContainerError(
            f"{label}: computed image offset 0x{computed_image_offset:X} outside entry")
    if expected_end != len(data):
        raise ContainerError(
            f"{label}: declared image section ends at 0x{expected_end:X}, EOF is 0x{len(data):X}")
    widgets, werr = scan_widgets(data, computed_image_offset, wcount, label)
    images, ierr = scan_images(data, computed_image_offset, expected_end, label)
    info.update({
        "widgets": widgets, "widget_error": werr,
        "parsed_widget_count": len(widgets),
        "count_matches": len(widgets) == wcount,
        "images": images, "image_error": ierr,
        "image_count": len(images),
        "widget_size_histogram": dict(sorted(Counter(w["record_size"] for w in widgets).items())),
        "type_histogram": dict(sorted(Counter(w["type_name"] for w in widgets).items())),
        "indices_contiguous": [w["global_index"] for w in widgets] == list(range(len(widgets))),
        "schema_complete": (werr is None and ierr is None
                            and all(not w["record_unclassified_bytes"]
                                    and not w["record_overlapping_bytes"]
                                    and w["schema_exact"] is not False
                                    for w in widgets)),
    })
    # Resolve only the pointer fields the type actually consumes. Numeric
    # equality in some other word is never treated as a reference.
    valid = {img["section_relative_offset"]: img["index"] for img in images}
    pointer_errors = []
    for wdg in info["widgets"]:
        hits = []
        for pointer in wdg["image_pointer_values"]:
            value = pointer["image_rel_offset"]
            if value in valid:
                hits.append({**pointer, "image_index": valid[value], "resolved": True})
            else:
                hits.append({**pointer, "image_index": None, "resolved": False})
                pointer_errors.append({"widget": wdg["ordinal"], **pointer})
        wdg["image_refs"] = hits
    referenced = {h["image_index"] for w in info["widgets"] for h in w["image_refs"]
                  if h["resolved"]}
    info["images_referenced"] = sorted(referenced)
    info["images_unreferenced"] = [i["index"] for i in images if i["index"] not in referenced]
    info["pointer_errors"] = pointer_errors

    indices = {widget["global_index"] for widget in widgets}
    alignment_fallbacks = []
    for widget in widgets:
        alignment = widget.get("alignment")
        if alignment and alignment["enabled"] and alignment["target_global_index"] not in indices:
            # An unresolvable reference deliberately falls back to the whole
            # root when no global index matches; vendor AOD records exercise it.
            alignment_fallbacks.append({"widget": widget["ordinal"], **alignment,
                                        "result": "whole-face fallback"})
    info["alignment_fallbacks"] = alignment_fallbacks
    info["alignment_errors"] = []  # compatibility with schema-v2 report models
    info["cross_record_valid"] = not pointer_errors
    return info


def parse_preview(data: bytes, label: str) -> dict:
    images, err = scan_images(data, 0, len(data), label)
    canonical = (err is None and len(data) % PREVIEW_STRIDE == 0
                 and all(img["record_offset"] == img["index"] * PREVIEW_STRIDE
                         and img["width"] == PREVIEW_WIDTH
                         and img["height"] == PREVIEW_HEIGHT
                         and img["format"] == f"0x{FMT_RGB565:04X}"
                         and img["declared_size"] == PREVIEW_DATA_SIZE
                         for img in images))
    return {
        "kind": "preview", "images": images, "image_error": err,
        "image_count": len(images), "canonical_fixed_stride": canonical,
        "record_stride": PREVIEW_STRIDE,
        "header_seek_formula": "style_index * 0x18570",
        "payload_seek_formula": "12 + style_index * (loaded_data_size + 12)",
        "canonical_geometry": f"{PREVIEW_WIDTH}x{PREVIEW_HEIGHT}",
        "canonical_data_size": PREVIEW_DATA_SIZE,
    }


# ---------------------------------------------------------------- entropy

def entropy_profile(data: bytes, block: int) -> list[float]:
    out = []
    for i in range(0, len(data), block):
        chunk = data[i:i + block]
        counts = Counter(chunk)
        n = len(chunk)
        out.append(round(-sum((c / n) * math.log2(c / n) for c in counts.values()), 3))
    return out


# ---------------------------------------------------------------- driver

def analyze(face: str, data: bytes, origin: str, out_root: Path, *,
            export_images: bool = True, thumb_cap: int = 110) -> dict:
    out = out_root / face
    subs = ("entries", "images", "thumbs") if export_images else ("entries",)
    for sub in subs:
        (out / sub).mkdir(parents=True, exist_ok=True)

    if len(data) < HEADER_SIZE:
        raise ContainerError("shorter than a container header")
    magic, version, payload, count, crc, crc_up, reserved = struct.unpack(
        "<4sIIIH2s12s", data[:HEADER_SIZE])
    if magic not in MAGICS:
        raise ContainerError(f"bad magic {magic!r}, expected one of {MAGICS!r}")
    dir_end = HEADER_SIZE + count * ENTRY_SIZE
    if dir_end > len(data):
        raise ContainerError(f"directory of {count} entries overruns the file")

    model: dict = {
        "face": face,
        # The filename only — never a path from the machine that ran this.
        "origin": origin,
        "file_size": len(data),
        "sha256": hashlib.sha256(data).hexdigest(),
        "md5": hashlib.md5(data).hexdigest(),
        "schema_profile": {
            "schema_version": SCHEMA_VERSION,
            "widget_types": 17,
            "structure_vs_policy": (
                "Parsing cleanly is not the same as being accepted: the 4 MiB limit "
                "and the unchanged-image-count rule are firmware policy, applied "
                "after the bytes are already sound."),
            "open_gaps": [{"name": name, "boundary": boundary}
                          for name, boundary in OPEN_GAPS],
        },
        "header": {
            "offset": 0, "size": HEADER_SIZE,
            "magic_hex": magic.hex(), "magic_ascii": magic.decode("ascii", "replace"),
            "magic_ok": magic in MAGICS,
            "accepted_magics": [m.decode("ascii") for m in MAGICS],
            "version": version,
            "declared_payload": payload, "actual_payload": len(data) - HEADER_SIZE,
            "payload_ok": payload == len(data) - HEADER_SIZE,
            "entry_count": count,
            "crc16_stored": f"0x{crc:04X}",
            "crc16_computed": f"0x{crc16(data[HEADER_SIZE:]):04X}",
            "crc16_ok": crc == crc16(data[HEADER_SIZE:]),
            "crc_checked_by_the_watch": False,
            "crc_upper_hex": crc_up.hex(), "crc_upper_zero": crc_up == b"\x00\x00",
            "reserved_hex": reserved.hex(), "reserved_zero": reserved == bytes(12),
            "fields": [
                {"offset": 0x00, "size": 4, "name": "magic",
                 "value": magic.decode("ascii", "replace")},
                {"offset": 0x04, "size": 4, "name": "version", "value": version},
                {"offset": 0x08, "size": 4, "name": "payload_size", "value": payload},
                {"offset": 0x0C, "size": 4, "name": "entry_count", "value": count},
                {"offset": 0x10, "size": 2, "name": "crc16_body", "value": f"0x{crc:04X}"},
                {"offset": 0x12, "size": 2, "name": "crc_upper", "value": crc_up.hex()},
                {"offset": 0x14, "size": 12, "name": "reserved", "value": reserved.hex()},
            ],
        },
        "directory": {"offset": HEADER_SIZE, "size": count * ENTRY_SIZE, "end": dir_end},
        "entries": [],
    }

    coverage: list[dict] = [
        {"start": 0, "end": HEADER_SIZE, "role": "container_header", "detail": "oppo header"},
        {"start": HEADER_SIZE, "end": dir_end, "role": "directory",
         "detail": f"{count} x {ENTRY_SIZE}-byte records"},
    ]

    all_images: list[dict] = []
    panels: Counter = Counter()
    prev_end = dir_end
    used_names: Counter = Counter()

    for i in range(count):
        rec_off = HEADER_SIZE + i * ENTRY_SIZE
        rec = data[rec_off:rec_off + ENTRY_SIZE]
        raw_path = rec[:PATH_FIELD]
        nul_at = raw_path.find(b"\x00")
        path_bytes = raw_path if nul_at < 0 else raw_path[:nul_at]
        path = path_bytes.decode("utf-8", "replace")
        off, size, ecrc = struct.unpack_from("<IIH", rec, PATH_FIELD)
        if off + size > len(data):
            raise ContainerError(f"entry {i} ({path}) overruns the file")
        payload_bytes = data[off:off + size]
        base = path.rsplit("/", 1)[-1]
        stem = base.rsplit(".", 1)[0]
        if match := PANEL_IN_PATH.search(path):
            panels[f"{match.group(1)}x{match.group(2)}"] += 1

        # Two entries may legitimately share a basename; never let one clobber
        # the other on disk.
        used_names[base] += 1
        disk_name = base if used_names[base] == 1 else f"{stem}~{used_names[base]}.bin"
        (out / "entries" / disk_name).write_bytes(payload_bytes)

        # ---- typed deep parse
        try:
            if base == "setting.bin":
                parsed = parse_setting(payload_bytes)
            elif base == "preview.bin":
                parsed = parse_preview(payload_bytes, base)
            elif base == "aod.bin" or re.fullmatch(r"style\d+\.bin", base):
                parsed = parse_style(payload_bytes, base)
            elif re.fullmatch(r"font_\d+\.bin", base):
                parsed = parse_font_binding(payload_bytes)
            elif base.startswith("font_"):
                parsed = parse_glyph_table(payload_bytes)
            else:
                parsed = {"kind": "unknown"}
        except (ContainerError, struct.error, IndexError, ValueError) as error:
            parsed = {"kind": "unparsed", "error": f"{type(error).__name__}: {error}"}

        # ---- decode + export rasters
        for img in parsed.get("images", []):
            if not export_images:
                all_images.append({"entry": base, **{k: img[k] for k in (
                    "index", "width", "height", "format_name", "declared_size",
                    "trailer_size", "trailer_zero", "section_relative_offset",
                    "record_offset")}})
                continue
            po = img["pixel_offset"]
            rows, alpha, stats = decode_image(
                memoryview(payload_bytes)[po:po + img["pixel_bytes"]],
                img["width"], img["height"], int(img["format"], 16))
            name = (f"{stem}_i{img['index']:03d}_{img['width']}x{img['height']}"
                    f"_{img['format_name'].replace('+', 'a')}.png")
            write_png(out / "images" / name, img["width"], img["height"], rows, alpha)
            trows, tw, th = thumbnail(rows, img["width"], img["height"], alpha, thumb_cap)
            write_png(out / "thumbs" / name, tw, th, trows, False)
            png_bytes = (out / "images" / name).stat().st_size
            img.update({"png": name, "stats": stats, "thumb_w": tw, "thumb_h": th,
                        "compressed_png_bytes": png_bytes,
                        "compression_ratio": round(img["declared_size"] / max(1, png_bytes), 2)})
            all_images.append({"entry": base, **{k: img[k] for k in (
                "index", "width", "height", "format_name", "declared_size",
                "trailer_size", "trailer_zero", "png", "thumb_w", "thumb_h",
                "section_relative_offset", "record_offset")}, "stats": stats})

        model["entries"].append({
            "index": i,
            "record_offset": rec_off,
            "path": path,
            "basename": base,
            "disk_name": disk_name,
            "path_field_hex_head": raw_path[:32].hex(),
            "path_len": len(path),
            "path_byte_len": len(path_bytes),
            "path_nul_terminated": nul_at >= 0,
            "path_padding_zero": (nul_at >= 0 and
                                  raw_path[nul_at:] == bytes(PATH_FIELD - nul_at)),
            "payload_offset": off,
            "payload_size": size,
            "payload_end": off + size,
            "crc16_stored": f"0x{ecrc:04X}",
            "crc16_computed": f"0x{crc16(payload_bytes):04X}",
            "crc16_ok": ecrc == crc16(payload_bytes),
            "crc_checked_by_the_watch": False,
            "sha256": hashlib.sha256(payload_bytes).hexdigest(),
            "gap_before": off - prev_end,
            "pct_of_file": round(100 * size / len(data), 3),
            "entropy_bits": round(entropy_profile(payload_bytes, max(1, len(payload_bytes)))[0], 3),
            "parsed": parsed,
        })
        coverage.append({"start": off, "end": off + size, "role": "entry", "detail": base})
        prev_end = off + size

    # ---------- byte-coverage audit
    assigned = [0] * len(data)
    for span in coverage:
        for j in range(span["start"], span["end"]):
            assigned[j] += 1

    def ranges_where(predicate) -> list[dict]:
        ranges, run = [], None
        for j, value in enumerate(assigned):
            if predicate(value) and run is None:
                run = j
            elif not predicate(value) and run is not None:
                ranges.append({"start": run, "end": j, "size": j - run})
                run = None
        if run is not None:
            ranges.append({"start": run, "end": len(data), "size": len(data) - run})
        return ranges

    holes = ranges_where(lambda value: value == 0)
    overlaps = ranges_where(lambda value: value > 1)

    # ---------- reconstruction proof, from what was written to disk
    rebuilt = bytearray(data[:HEADER_SIZE])
    for en in model["entries"]:
        rebuilt += data[en["record_offset"]:en["record_offset"] + ENTRY_SIZE]
    for en in model["entries"]:
        rebuilt += (out / "entries" / en["disk_name"]).read_bytes()

    model["coverage"] = {
        "spans": coverage,
        "unaccounted_byte_count": sum(1 for value in assigned if value == 0),
        "overlapping_byte_count": sum(1 for value in assigned if value > 1),
        "holes": holes,
        "overlaps": overlaps,
        "trailing_bytes": len(data) - prev_end,
        "tightly_packed": all(en["gap_before"] == 0 for en in model["entries"]),
        "reconstruction_identical": bytes(rebuilt) == data,
        "reconstruction_sha256": hashlib.sha256(bytes(rebuilt)).hexdigest(),
    }

    # ---------- aggregate stats for the report's charts
    img_bytes = sum(i["declared_size"] for i in all_images)
    styles = [e for e in model["entries"] if e["parsed"].get("kind") == "style"]
    selectable_styles = [e for e in styles if re.fullmatch(r"style\d+\.bin", e["basename"])]
    settings = [e for e in model["entries"] if e["parsed"].get("kind") == "setting"]
    previews = [e for e in model["entries"] if e["parsed"].get("kind") == "preview"]
    bindings = [e for e in model["entries"] if e["parsed"].get("kind") == "font_binding"]
    dictionaries = [e for e in model["entries"] if e["parsed"].get("kind") == "glyph_table"]

    # ---------- cross-resource proof
    resource_errors: list[str] = []
    bad_kinds = [e for e in model["entries"]
                 if e["parsed"].get("kind") in ("unknown", "unparsed")]
    for entry in bad_kinds:
        resource_errors.append(
            f'{entry["basename"]}: {entry["parsed"].get("error", "unclassified resource")}')
    if len(settings) != 1:
        resource_errors.append(f"expected one setting.bin, found {len(settings)}")
    if len(previews) != 1:
        resource_errors.append(f"expected one preview.bin, found {len(previews)}")

    style_numbers = sorted(int(entry["basename"][5:-4]) for entry in selectable_styles)
    expected_style_count = settings[0]["parsed"]["style_count"] if len(settings) == 1 else None
    if expected_style_count is not None:
        if style_numbers != list(range(expected_style_count)):
            resource_errors.append(
                f"selectable styles are {style_numbers}, expected 0..{expected_style_count - 1}")
        if len(previews) == 1 and previews[0]["parsed"]["image_count"] != expected_style_count:
            resource_errors.append(
                f'preview has {previews[0]["parsed"]["image_count"]} records, '
                f"setting declares {expected_style_count} styles")
    if len(previews) == 1 and not previews[0]["parsed"]["canonical_fixed_stride"]:
        resource_errors.append("preview.bin is not canonical 99,696-byte fixed-stride data")

    binding_numbers = sorted(int(entry["basename"][5:-4]) for entry in bindings)
    if binding_numbers != list(range(len(binding_numbers))):
        resource_errors.append(
            f"numbered fonts are {binding_numbers}, expected consecutive indices from zero")
    for entry in styles:
        parsed = entry["parsed"]
        if parsed["widget_error"] or parsed["image_error"]:
            resource_errors.append(
                f'{entry["basename"]}: {parsed["widget_error"] or parsed["image_error"]}')
        if not parsed["eq_image_offset"]["ok"] or not parsed["eq_entry_size"]["ok"]:
            resource_errors.append(f'{entry["basename"]}: canonical style equations fail')
        if not parsed["font_binding_count_valid"] or not parsed["font_count_word_canonical"]:
            resource_errors.append(f'{entry["basename"]}: noncanonical font-count word')
        if parsed["font_binding_count"] != len(bindings):
            resource_errors.append(
                f'{entry["basename"]}: declares {parsed["font_binding_count"]} fonts, '
                f"container has {len(bindings)}")
        if not parsed["indices_contiguous"]:
            resource_errors.append(f'{entry["basename"]}: global widget indices are not contiguous')
        if parsed["pointer_errors"]:
            resource_errors.append(
                f'{entry["basename"]}: {len(parsed["pointer_errors"])} unresolved image pointer(s)')
        if any(not image["trailer_zero"] for image in parsed["images"]):
            resource_errors.append(f'{entry["basename"]}: nonzero canonical image trailer')
        for widget in parsed["widgets"]:
            for font_index in widget["font_binding_refs"]:
                if font_index >= len(bindings):
                    resource_errors.append(
                        f'{entry["basename"]} widget {widget["ordinal"]}: font index '
                        f"{font_index} is out of range")
            if widget["dictionary_refs"]:
                if not dictionaries:
                    resource_errors.append(
                        f'{entry["basename"]} widget {widget["ordinal"]}: dictionary '
                        "reference but no locale dictionary")
                else:
                    minimum_count = min(d["parsed"]["group_count"] for d in dictionaries)
                    for reference in widget["dictionary_refs"]:
                        if reference["value"] >= minimum_count:
                            resource_errors.append(
                                f'{entry["basename"]} widget {widget["ordinal"]}: '
                                f'dictionary {reference["kind"]} {reference["value"]} '
                                f"exceeds at least one locale count {minimum_count}")

    byte_integrity = (
        model["header"]["crc16_ok"] and model["header"]["payload_ok"]
        and not holes and not overlaps
        and bytes(rebuilt) == data
        and all(entry["crc16_ok"] for entry in model["entries"])
    )
    schema_complete = not bad_kinds and all(
        entry["parsed"].get("schema_complete", True) for entry in model["entries"])
    model["verification"] = {
        "byte_integrity": byte_integrity,
        "schema_complete": schema_complete,
        "cross_resource_valid": not resource_errors,
        "resource_errors": resource_errors,
                "structurally_compatible": not resource_errors,
        "hardware_policy_proven_for_this_file": False,
    }

    model["images"] = all_images
    model["panel"] = panels.most_common(1)[0][0] if panels else None
    model["stats"] = {
        "entry_count": count,
        "total_images": len(all_images),
        "image_payload_bytes": img_bytes,
        "image_payload_pct": round(100 * img_bytes / len(data), 2),
        "header_bytes": HEADER_SIZE,
        "directory_bytes": count * ENTRY_SIZE,
        "metadata_pct": round(100 * (HEADER_SIZE + count * ENTRY_SIZE) / len(data), 4),
        "widget_bytes": sum(e["parsed"]["declared_widget_bytes"] for e in styles),
        "total_widgets": sum(e["parsed"]["parsed_widget_count"] for e in styles),
        "style_count": len(selectable_styles),
        "style_entry_count": len(styles),
        "format_mix": dict(Counter(i["format_name"] for i in all_images)),
        "dimension_histogram": dict(sorted(Counter(
            f"{i['width']}x{i['height']}" for i in all_images).items(),
            key=lambda kv: -kv[1])[:14]),
        "trailer_sizes": dict(sorted(Counter(i["trailer_size"] for i in all_images).items())),
        "widget_type_totals": dict(sorted(Counter(
            w["type_name"] for e in styles for w in e["parsed"]["widgets"]).items(),
            key=lambda kv: -kv[1])),
        "widget_size_totals": dict(sorted(Counter(
            w["record_size"] for e in styles for w in e["parsed"]["widgets"]).items())),
        "sequence_totals": dict(sorted(Counter(
            w["sequence_id"] for e in styles for w in e["parsed"]["widgets"]).items(),
            key=lambda kv: -kv[1])),
        "entry_kinds": dict(Counter(e["parsed"].get("kind", "unknown")
                                    for e in model["entries"])),
        "byte_class": {
            "container_header": HEADER_SIZE,
            "directory": count * ENTRY_SIZE,
            "image_headers": len(all_images) * IMAGE_HEADER,
            "image_pixels": sum(i["declared_size"] - i["trailer_size"] for i in all_images),
            "image_trailers": sum(i["trailer_size"] for i in all_images),
            "style_headers": len(styles) * STYLE_HEADER_SIZE,
            "widget_records": sum(e["parsed"]["declared_widget_bytes"] for e in styles),
            "setting": sum(e["payload_size"] for e in model["entries"]
                           if e["parsed"].get("kind") == "setting"),
            "font_bindings": sum(e["payload_size"] for e in model["entries"]
                                 if e["parsed"].get("kind") == "font_binding"),
            "glyph_tables": sum(e["payload_size"] for e in model["entries"]
                                if e["parsed"].get("kind") == "glyph_table"),
            "unclassified_entries": sum(
                e["payload_size"] for e in model["entries"]
                if e["parsed"].get("kind") in ("unknown", "unparsed")),
        },
    }
    bc = model["stats"]["byte_class"]
    model["stats"]["byte_class_total"] = sum(bc.values())
    model["stats"]["byte_class_delta"] = len(data) - sum(bc.values())
    model["entropy_64k"] = entropy_profile(data, 65536)
    return model


# ---------------------------------------------------------------- inputs

CONTAINER_IN_APK = re.compile(r"(^|/)assets/[^/]+_\d+x\d+\.bin$")


def containers_in_apk(path: Path) -> list[tuple[str, bytes]]:
    """Every watch-face container inside an APK-shaped package."""
    found = []
    with zipfile.ZipFile(path) as zf:
        for name in zf.namelist():
            if CONTAINER_IN_APK.search(name):
                found.append((Path(name).stem, zf.read(name)))
    return found


def collect(paths: list[Path]) -> list[tuple[str, bytes, str]]:
    """Resolve inputs to (face, bytes, origin-filename) triples."""
    jobs: list[tuple[str, bytes, str]] = []
    files: list[Path] = []
    for p in paths:
        if p.is_dir():
            files += sorted(q for q in p.rglob("*") if q.suffix in (".bin", ".apk"))
        else:
            files.append(p)
    for f in files:
        if f.suffix == ".apk":
            try:
                inner = containers_in_apk(f)
            except zipfile.BadZipFile:
                print(f"[skip] {f.name}: not a readable package", file=sys.stderr)
                continue
            if not inner:
                # Expected: some catalogue entries are customisation apps that
                # carry no container at all and are rendered by the watch.
                print(f"[skip] {f.name}: no container inside", file=sys.stderr)
            for face, data in inner:
                jobs.append((face, data, f.name))
        else:
            jobs.append((f.stem, f.read_bytes(), f.name))
    # Deduplicate identical bytes, not face names. Locale-rich reference variants
    # can legitimately share a face id with a catalogue container while carrying
    # different resources; the older name-only rule silently dropped them.
    seen_hashes, used_ids, unique = set(), set(), []
    for face, data, origin in jobs:
        digest = hashlib.sha256(data).hexdigest()
        if digest in seen_hashes:
            continue
        seen_hashes.add(digest)
        output_id = face
        if output_id in used_ids:
            output_id = f"{face}__{digest[:8]}"
        used_ids.add(output_id)
        unique.append((output_id, data, origin))
    return unique


def main() -> int:
    ap = argparse.ArgumentParser(
        description="Byte-level analyzer for SM-R390 OPPO watch-face containers.",
        epilog="Inputs may be .bin containers, .apk packages, or directories of either.")
    ap.add_argument("inputs", nargs="+", type=Path,
                    help="containers, packages, or directories to analyse")
    ap.add_argument("--out", type=Path, required=True,
                    help="output directory; one subdirectory per face")
    ap.add_argument("--skip-images", action="store_true",
                    help="model only — do not decode or export rasters (much faster)")
    ap.add_argument("--thumb-cap", type=int, default=110, metavar="PX",
                    help="longest thumbnail edge in pixels (default: 110)")
    ap.add_argument("--quiet", action="store_true", help="only report failures")
    args = ap.parse_args()

    jobs = collect(args.inputs)
    if not jobs:
        print("no containers found in the given inputs", file=sys.stderr)
        return 1
    args.out.mkdir(parents=True, exist_ok=True)

    analysed, failed = [], []
    for face, data, origin in jobs:
        try:
            model = analyze(face, data, origin, args.out,
                            export_images=not args.skip_images,
                            thumb_cap=args.thumb_cap)
        except (ContainerError, struct.error, ValueError) as error:
            failed.append((face, f"{type(error).__name__}: {error}"))
            print(f"[fail] {face}: {error}", file=sys.stderr)
            continue
        (args.out / face / "model.json").write_text(json.dumps(model, indent=1))
        s, c = model["stats"], model["coverage"]
        verification = model["verification"]
        clean = (verification["byte_integrity"]
                 and verification["schema_complete"]
                 and verification["cross_resource_valid"]
                 and s["byte_class_delta"] == 0)
        analysed.append({"face": face, "origin": origin,
                         "file_size": model["file_size"], "sha256": model["sha256"],
                         "entries": s["entry_count"], "images": s["total_images"],
                         "widgets": s["total_widgets"], "clean": clean})
        if not args.quiet:
            print(f"[ok] {face}  {model['file_size']:,} B  entries={s['entry_count']} "
                  f"images={s['total_images']} widgets={s['total_widgets']} "
                  f"{'verified' if clean else 'CHECKS FAILED'}", file=sys.stderr)

    (args.out / "index.json").write_text(json.dumps({
        "schema_version": SCHEMA_VERSION,
        "faces": [a["face"] for a in analysed],
        "images_exported": not args.skip_images,
        "analysed": analysed,
        "failed": [{"face": f, "error": m} for f, m in failed],
    }, indent=1))

    dirty = [a["face"] for a in analysed if not a["clean"]]
    print(f"\n{len(analysed)} analysed, {len(failed)} failed, "
          f"{len(dirty)} with failing integrity checks", file=sys.stderr)
    if dirty:
        print(f"  check: {', '.join(dirty)}", file=sys.stderr)
    return 1 if failed or dirty else 0


if __name__ == "__main__":
    raise SystemExit(main())
