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

# ---------------------------------------------------------------- constants

MAGIC = b"oppo"
HEADER_SIZE = 32
ENTRY_SIZE = 74
PATH_FIELD = 64
STYLE_MAGIC = 0x12345678
STYLE_HEADER_SIZE = 24
WIDGET_FIXED = 36
IMAGE_HEADER = 12

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

WIDGET_TYPES = {
    1: "Static", 2: "Hand", 3: "Sprite", 5: "Pair", 7: "Badge",
    13: "Comp", 16: "Arc", 17: "LineBar",
}

# Preview-grade labels only: sequence IDs are firmware constants and nothing in
# the file names them. Every one of these was read off an embedded preview
# raster agreeing with the widget's own geometry, never from a vendor table.
# An unlisted ID is reported as its raw number rather than guessed at.
SEQ_LABELS = {
    0: "none/static", 2: "hour tens", 3: "hour ones", 9: "hour hand",
    10: "minute tens", 11: "minute ones", 12: "second hand", 14: "second tens",
    15: "second ones", 17: "date", 18: "weekday", 22: "am/pm",
    29: "steps", 37: "heart rate", 41: "calories", 48: "battery",
    69: "weather", 72: "sleep", 104: "stress", 115: "activity",
    106: "world clock", 107: "world clock", 109: "world clock",
    110: "world clock", 116: "world clock", 125: "world clock",
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
    """0x12345678 | locale word | group count | 3 reserved words | N x (len, offset)."""
    magic, locale, groups = struct.unpack_from("<III", data, 0)
    reserved = data[0x0C:0x18]
    descriptors, cursor = [], 0x18
    for i in range(groups):
        if cursor + 8 > len(data):
            break
        length, offset = struct.unpack_from("<II", data, cursor)
        raw = data[offset:offset + length]
        text = raw.decode("utf-8", "replace")
        descriptors.append({
            "index": i, "descriptor_offset": cursor,
            "byte_length": length, "text_offset": offset,
            "text": text, "codepoints": len(text), "hex": raw.hex(),
        })
        cursor += 8
    text_start = cursor
    covered = bytearray(len(data))
    covered[0:text_start] = b"\x01" * text_start
    for d in descriptors:
        for i in range(d["text_offset"], min(d["text_offset"] + d["byte_length"], len(data))):
            covered[i] = 1
    return {
        "kind": "glyph_table",
        "magic": f"0x{magic:08X}", "magic_ok": magic == STYLE_MAGIC,
        "locale_word": locale, "group_count": groups,
        "reserved_hex": reserved.hex(), "reserved_zero": reserved == bytes(12),
        "descriptor_table": {"offset": 0x18, "size": groups * 8},
        "text_region": {"offset": text_start, "size": len(data) - text_start},
        "groups": descriptors,
        "unaccounted_bytes": covered.count(0),
        "ascending_offsets": all(
            descriptors[i]["text_offset"] <= descriptors[i + 1]["text_offset"]
            for i in range(len(descriptors) - 1)),
    }


def parse_font_binding(data: bytes) -> dict:
    """92-byte record: family index, 71 opaque bytes, 16-byte name, size word."""
    name = data[0x48:0x58].split(b"\x00", 1)[0].decode("ascii", "replace")
    size_word = struct.unpack_from("<I", data, 0x58)[0]
    opaque = data[0x01:0x48]
    nonzero = [{"offset": 1 + i, "value": b} for i, b in enumerate(opaque) if b]
    return {
        "kind": "font_binding",
        "family_index": data[0],
        "binding_name": name,
        "name_field_hex": data[0x48:0x58].hex(),
        "point_size": size_word,
        "opaque_region": {"offset": 0x01, "size": 0x47},
        "opaque_nonzero": nonzero,
        "opaque_zero_bytes": 0x47 - len(nonzero),
        "record_size": len(data),
    }


def parse_setting(data: bytes) -> dict:
    """256-byte identity block."""
    marker = data[0x00:0x0C]
    magic = struct.unpack_from("<I", data, 0x0C)[0]
    face_id = data[0x10:0x20].split(b"\x00", 1)[0].decode("ascii", "replace")
    gap20 = data[0x20:0x30]
    version = struct.unpack_from("<I", data, 0x30)[0]
    w34, w36 = struct.unpack_from("<HH", data, 0x34)
    slot_a, slot_b = data[0x38:0x78], data[0x78:0xB8]
    tail = data[0xB8:]
    dec = lambda s: s[1:].split(b"\x00", 1)[0].decode("ascii", "replace")
    return {
        "kind": "setting",
        "marker_ascii": marker.split(b"\x00", 1)[0].decode("ascii", "replace"),
        "marker_hex": marker.hex(),
        "struct_magic": f"0x{magic:08X}", "magic_ok": magic == STYLE_MAGIC,
        "face_id": face_id, "face_id_field_hex": data[0x10:0x20].hex(),
        "reserved_0x20_hex": gap20.hex(), "reserved_0x20_zero": gap20 == bytes(16),
        "face_version": version,
        "word_0x34": w34, "word_0x36": f"0x{w36:04X}",
        "name_slot_a": {"offset": 0x38, "lead_byte": slot_a[0], "name": dec(slot_a),
                        "trailing_zero": slot_a[1 + len(dec(slot_a)):] == bytes(
                            len(slot_a) - 1 - len(dec(slot_a)))},
        "name_slot_b": {"offset": 0x78, "lead_byte": slot_b[0], "name": dec(slot_b),
                        "trailing_zero": slot_b[1 + len(dec(slot_b)):] == bytes(
                            len(slot_b) - 1 - len(dec(slot_b)))},
        "slots_identical": slot_a == slot_b,
        "tail_region": {"offset": 0xB8, "size": len(tail)},
        "tail_zero": tail == bytes(len(tail)),
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
            return out, f"{label}: unknown format 0x{fmt:04X} at 0x{cursor:X}"
        name, bpp, _ = FORMATS[fmt]
        palette = PALETTE_BYTES if fmt == FMT_INDEXED8 else 0
        px = palette + w * h * bpp
        if w == 0 or h == 0 or size < px or cursor + IMAGE_HEADER + size > end:
            return out, f"{label}: implausible image at 0x{cursor:X} ({w}x{h}, {size} B)"
        out.append({
            "index": len(out), "record_offset": cursor,
            "pixel_offset": cursor + IMAGE_HEADER,
            "width": w, "height": h,
            "format": f"0x{fmt:04X}", "format_name": name,
            "bytes_per_pixel": bpp, "palette_bytes": palette,
            "reserved": resv,
            "declared_size": size, "pixel_bytes": px,
            "trailer_size": size - px,
            "trailer_hex": data[cursor + IMAGE_HEADER + px:
                                cursor + IMAGE_HEADER + size].hex(),
            "record_total": IMAGE_HEADER + size,
            "section_relative_offset": cursor - start,
        })
        cursor += IMAGE_HEADER + size
    if cursor != end:
        return out, f"{label}: image stream ended at 0x{cursor:X}, expected 0x{end:X}"
    return out, None


def scan_widgets(data: bytes, end: int, label: str) -> tuple[list[dict], str | None]:
    out, cursor = [], STYLE_HEADER_SIZE
    while cursor < end:
        if cursor + WIDGET_FIXED > end:
            return out, f"{label}: truncated widget at 0x{cursor:X}"
        wtype, seq = struct.unpack_from("<II", data, cursor)
        op08 = struct.unpack_from("<I", data, cursor + 0x08)[0]
        index_size = struct.unpack_from("<I", data, cursor + 0x0C)[0]
        size, gidx = index_size & 0xFFFF, index_size >> 16
        if size < WIDGET_FIXED or cursor + size > end:
            return out, f"{label}: bad widget size {size} at 0x{cursor:X}"
        op10, op14 = struct.unpack_from("<II", data, cursor + 0x10)
        x, y, w, h = struct.unpack_from("<hhHH", data, cursor + 0x18)
        word20 = struct.unpack_from("<I", data, cursor + 0x20)[0]
        nwords = (size - WIDGET_FIXED) // 4
        words = list(struct.unpack_from(f"<{nwords}I", data, cursor + WIDGET_FIXED)) if nwords else []
        # Records are 4-byte aligned in most of the corpus, but 50-byte records
        # with a two-byte tail after the final complete word are documented, so
        # the leftover is captured rather than assumed absent.
        tail = data[cursor + WIDGET_FIXED + nwords * 4: cursor + size]
        out.append({
            "ordinal": len(out), "record_offset": cursor, "record_size": size,
            "global_index": gidx, "index_size_word": f"0x{index_size:08X}",
            "type": wtype, "type_name": WIDGET_TYPES.get(wtype, f"type_{wtype}"),
            "sequence_id": seq, "sequence_label": SEQ_LABELS.get(seq, ""),
            "opaque_0x08": op08, "opaque_0x10": op10, "opaque_0x14": op14,
            "x": x, "y": y, "w": w, "h": h,
            "word_0x20": word20, "word_0x20_hex": f"0x{word20:08X}",
            "type_words": [{"i": i, "offset": WIDGET_FIXED + i * 4,
                            "value": v, "hex": f"0x{v:08X}"}
                           for i, v in enumerate(words)],
            "tail_size": len(tail), "tail_hex": tail.hex(),
            "raw_hex": data[cursor:cursor + size].hex(),
        })
        cursor += size
    if cursor != end:
        return out, f"{label}: widget stream ended at 0x{cursor:X}, expected 0x{end:X}"
    return out, None


def parse_style(data: bytes, label: str) -> dict:
    magic, wcount, wbytes, ibytes, unk10, ioff = struct.unpack_from("<IIIIII", data, 0)
    info = {
        "kind": "style",
        "magic": f"0x{magic:08X}", "magic_ok": magic == STYLE_MAGIC,
        "declared_widget_count": wcount,
        "declared_widget_bytes": wbytes,
        "declared_image_bytes": ibytes,
        "unknown_0x10": unk10, "unknown_0x10_hex": f"0x{unk10:08X}",
        "declared_image_offset": ioff,
        "eq_image_offset": {"expected": STYLE_HEADER_SIZE + wbytes, "actual": ioff,
                            "ok": ioff == STYLE_HEADER_SIZE + wbytes},
        "eq_entry_size": {"expected": ioff + ibytes, "actual": len(data),
                          "ok": ioff + ibytes == len(data)},
    }
    if not (STYLE_HEADER_SIZE <= ioff <= len(data)):
        raise ContainerError(f"{label}: image offset 0x{ioff:X} outside the entry")
    widgets, werr = scan_widgets(data, ioff, label)
    images, ierr = scan_images(data, ioff, len(data), label)
    info.update({
        "widgets": widgets, "widget_error": werr,
        "parsed_widget_count": len(widgets),
        "count_matches": len(widgets) == wcount,
        "images": images, "image_error": ierr,
        "image_count": len(images),
        "widget_size_histogram": dict(sorted(Counter(w["record_size"] for w in widgets).items())),
        "type_histogram": dict(sorted(Counter(w["type_name"] for w in widgets).items())),
        "indices_contiguous": [w["global_index"] for w in widgets] == list(range(len(widgets))),
    })
    # Cross-reference widget words against image-section relative offsets. A hit
    # is a candidate, not a proof: the same 32-bit value can be a colour or a
    # mode, so the report never rewrites one of these on the strength of a match.
    valid = {img["section_relative_offset"]: img["index"] for img in images}
    for wdg in info["widgets"]:
        hits = []
        for tw in wdg["type_words"]:
            if tw["value"] in valid:
                hits.append({"offset": tw["offset"], "image_index": valid[tw["value"]],
                             "image_rel_offset": tw["value"]})
        if wdg["word_0x20"] in valid:
            hits.append({"offset": 0x20, "image_index": valid[wdg["word_0x20"]],
                         "image_rel_offset": wdg["word_0x20"]})
        wdg["image_refs"] = hits
    referenced = {h["image_index"] for w in info["widgets"] for h in w["image_refs"]}
    info["images_referenced"] = sorted(referenced)
    info["images_unreferenced"] = [i["index"] for i in images if i["index"] not in referenced]
    return info


def parse_preview(data: bytes, label: str) -> dict:
    images, err = scan_images(data, 0, len(data), label)
    return {"kind": "preview", "images": images, "image_error": err,
            "image_count": len(images)}


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
    if magic != MAGIC:
        raise ContainerError(f"bad magic {magic!r}, expected {MAGIC!r}")
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
        "header": {
            "offset": 0, "size": HEADER_SIZE,
            "magic_hex": magic.hex(), "magic_ascii": magic.decode("ascii", "replace"),
            "magic_ok": magic == MAGIC,
            "version": version,
            "declared_payload": payload, "actual_payload": len(data) - HEADER_SIZE,
            "payload_ok": payload == len(data) - HEADER_SIZE,
            "entry_count": count,
            "crc16_stored": f"0x{crc:04X}",
            "crc16_computed": f"0x{crc16(data[HEADER_SIZE:]):04X}",
            "crc16_ok": crc == crc16(data[HEADER_SIZE:]),
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
        path = raw_path.split(b"\x00", 1)[0].decode("utf-8", "replace")
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
            if base == "setting.bin" and size >= 0xB8:
                parsed = parse_setting(payload_bytes)
            elif base == "preview.bin":
                parsed = parse_preview(payload_bytes, base)
            elif base == "aod.bin" or base.startswith("style"):
                parsed = parse_style(payload_bytes, base)
            elif base.startswith("font_") and size == 92:
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
                    "trailer_size", "section_relative_offset", "record_offset")}})
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
                "trailer_size", "png", "thumb_w", "thumb_h",
                "section_relative_offset", "record_offset")}, "stats": stats})

        model["entries"].append({
            "index": i,
            "record_offset": rec_off,
            "path": path,
            "basename": base,
            "disk_name": disk_name,
            "path_field_hex_head": raw_path[:32].hex(),
            "path_len": len(path),
            "path_nul_terminated": len(path) < PATH_FIELD,
            "path_padding_zero": raw_path[len(path):] == bytes(PATH_FIELD - len(path)),
            "payload_offset": off,
            "payload_size": size,
            "payload_end": off + size,
            "crc16_stored": f"0x{ecrc:04X}",
            "crc16_computed": f"0x{crc16(payload_bytes):04X}",
            "crc16_ok": ecrc == crc16(payload_bytes),
            "sha256": hashlib.sha256(payload_bytes).hexdigest(),
            "gap_before": off - prev_end,
            "pct_of_file": round(100 * size / len(data), 3),
            "entropy_bits": round(entropy_profile(payload_bytes, max(1, len(payload_bytes)))[0], 3),
            "parsed": parsed,
        })
        coverage.append({"start": off, "end": off + size, "role": "entry", "detail": base})
        prev_end = off + size

    # ---------- byte-coverage audit
    covered = bytearray(len(data))
    for span in coverage:
        for j in range(span["start"], span["end"]):
            covered[j] = 1
    holes, run = [], None
    for j, c in enumerate(covered):
        if not c and run is None:
            run = j
        elif c and run is not None:
            holes.append({"start": run, "end": j})
            run = None
    if run is not None:
        holes.append({"start": run, "end": len(data)})

    # ---------- reconstruction proof, from what was written to disk
    rebuilt = bytearray(data[:HEADER_SIZE])
    for en in model["entries"]:
        rebuilt += data[en["record_offset"]:en["record_offset"] + ENTRY_SIZE]
    for en in model["entries"]:
        rebuilt += (out / "entries" / en["disk_name"]).read_bytes()

    model["coverage"] = {
        "spans": coverage,
        "unaccounted_byte_count": covered.count(0),
        "holes": holes,
        "trailing_bytes": len(data) - prev_end,
        "tightly_packed": all(en["gap_before"] == 0 for en in model["entries"]),
        "reconstruction_identical": bytes(rebuilt) == data,
        "reconstruction_sha256": hashlib.sha256(bytes(rebuilt)).hexdigest(),
    }

    # ---------- aggregate stats for the report's charts
    img_bytes = sum(i["declared_size"] for i in all_images)
    styles = [e for e in model["entries"] if e["parsed"].get("kind") == "style"]
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
        "style_count": len(styles),
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
    # One job per face; a container reached twice (loose .bin and inside its
    # package) is analysed once.
    seen, unique = set(), []
    for face, data, origin in jobs:
        if face in seen:
            continue
        seen.add(face)
        unique.append((face, data, origin))
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
        clean = (model["header"]["crc16_ok"] and model["header"]["payload_ok"]
                 and c["unaccounted_byte_count"] == 0 and c["reconstruction_identical"]
                 and s["byte_class_delta"] == 0
                 and all(e["crc16_ok"] for e in model["entries"]))
        analysed.append({"face": face, "origin": origin,
                         "file_size": model["file_size"], "sha256": model["sha256"],
                         "entries": s["entry_count"], "images": s["total_images"],
                         "widgets": s["total_widgets"], "clean": clean})
        if not args.quiet:
            print(f"[ok] {face}  {model['file_size']:,} B  entries={s['entry_count']} "
                  f"images={s['total_images']} widgets={s['total_widgets']} "
                  f"{'verified' if clean else 'CHECKS FAILED'}", file=sys.stderr)

    (args.out / "index.json").write_text(json.dumps({
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
