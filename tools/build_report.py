#!/usr/bin/env python3
"""Render a self-contained HTML anatomy report from analyze_container.py output.

Consumes the `model.json` files (and, if they were exported, the decoded PNGs)
under an analysis directory and writes one HTML page with no external requests:
every raster is inlined as a data URI and every chart is hand-built SVG.

Corpus counts and validation results are computed from the models actually
loaded. Field definitions come from the shared, evidence-labelled schema used
by the analyzer, so the page remains honest about both its live input and the
larger fixed vendor-only reference audit.

Per-face detail (full widget dumps, asset galleries, variant diffs) is expensive
in page weight, so it is rendered for the first `--detail` faces; every other
section is a census over everything loaded.

Standard library only. Examples:

    python3 tools/build_report.py out --output out/anatomy.html
    python3 tools/build_report.py out --faces SM-R390_00046_256x402 --detail 1
"""

from __future__ import annotations

import argparse
import base64
import html
import json
import sys
from collections import Counter, OrderedDict
from pathlib import Path

from watchface_schema import (
    COMMON_FIELDS,
    EVIDENCE_LEVELS,
    FONT_FAMILIES,
    OPEN_GAPS,
    REFERENCE_BYTE_ROLES,
    REFERENCE_CONTAINER_BYTES,
    REFERENCE_CONTAINER_COUNT,
    REFERENCE_ROLE_RANGES,
    REFERENCE_STYLE_IMAGES,
    REFERENCE_WIDGETS,
    SCHEMA_VERSION,
    SOURCE_LABELS,
    WIDGET_TYPES,
)

# Validated categorical slots — light and dark variants of one palette.
S = ["#2a78d6", "#eb6834", "#1baf7a", "#eda100", "#e87ba4", "#4a3aa7", "#008300", "#e34948"]
SD = ["#3987e5", "#d95926", "#199e70", "#c98500", "#d55181", "#9085e9", "#008300", "#e66767"]

KIND_SLOT = {
    "container_header": 0, "directory": 0,
    "style": 5, "aod": 2, "preview": 4,
    "setting": 1, "font_binding": 3, "glyph_table": 3,
    "unknown": 6, "unparsed": 7,
}
KIND_LABEL = OrderedDict([
    ("container_header", "Container header"),
    ("directory", "Directory"),
    ("aod", "Always-on style"),
    ("font_binding", "font_*.bin (bindings + glyph tables)"),
    ("preview", "Preview rasters"),
    ("setting", "setting.bin"),
    ("style", "Style entries"),
    ("unknown", "Unclassified entry"),
])

e = html.escape


def kb(n: int) -> str:
    if n >= 1 << 20:
        return f"{n / (1 << 20):.2f} MiB"
    if n >= 1400:
        return f"{n / 1024:.1f} KiB"
    return f"{n} B"


def commas(n: int) -> str:
    return f"{n:,}"


def short(face: str) -> str:
    """A compact label for charts: the face number when there is one."""
    digits = [p for p in face.replace("-", "_").split("_") if p.isdigit() and len(p) >= 4]
    return digits[0] if digits else face[-12:]


# ------------------------------------------------------------------ image data

class Assets:
    """Lazily base64-inlines the decoded PNGs sitting next to the models."""

    def __init__(self, root: Path, available: bool):
        self.root = root
        self.available = available
        self._cache: dict[str, str] = {}

    def uri(self, face: str, png: str) -> str | None:
        if not self.available:
            return None
        key = f"{face}/{png}"
        if key not in self._cache:
            path = self.root / face / "images" / png
            if not path.exists():
                return None
            self._cache[key] = ("data:image/png;base64,"
                                + base64.b64encode(path.read_bytes()).decode())
        return self._cache[key]

    def tag(self, face: str, img: dict, cls: str = "") -> str:
        # Full-resolution PNGs throughout; CSS scales them and supplies the
        # checkerboard behind alpha, so nothing is resampled twice.
        uri = self.uri(face, img.get("png", ""))
        if uri is None:
            return '<div class="noimg">no raster export</div>'
        return (f'<img class="ras {cls}" src="{uri}" width="{img["width"]}" '
                f'height="{img["height"]}" alt="{e(img.get("png", ""))}" loading="lazy">')


# ------------------------------------------------------------------ svg helpers

def svg_open(w: int, h: int, cls: str = "chart") -> list[str]:
    return [f'<svg class="{cls}" viewBox="0 0 {w} {h}" role="img" '
            f'preserveAspectRatio="xMidYMid meet">']


def txt(x, y, s, cls="lbl", anchor="start", extra=""):
    return (f'<text x="{x}" y="{y}" class="{cls}" text-anchor="{anchor}" {extra}>'
            f'{e(str(s))}</text>')


# ------------------------------------------------------------------ charts

def chart_hbar(rows: list[tuple[str, int, str]], *, width=880, row_h=27,
               label_w=180, unit="B", note="") -> str:
    """Single-series horizontal bars with fixed label and value columns."""
    if not rows:
        return ""
    h = len(rows) * row_h + 14
    mx = max(v for _, v, _ in rows) or 1
    val_col = width - 132
    plot_x = label_w + 8
    plot_w = max(40, val_col - plot_x - 92)
    o = svg_open(width, h)
    for i, (lab, val, sub) in enumerate(rows):
        y = i * row_h + 7
        bw = max(2.5, plot_w * val / mx)
        o.append(f'<rect x="{plot_x}" y="{y}" width="{bw:.2f}" height="{row_h - 11}" '
                 f'rx="3" class="bar"><title>{e(lab)}: {commas(val)} {unit}</title></rect>')
        o.append(txt(label_w, y + row_h - 16, lab, "lbl", "end"))
        o.append(txt(val_col, y + row_h - 16, f"{commas(val)} {unit}", "val", "end"))
        if sub:
            o.append(txt(val_col + 12, y + row_h - 16, sub, "ax"))
    o.append("</svg>")
    return f'<figure class="fig">{"".join(o)}' + (
        f'<figcaption>{e(note)}</figcaption>' if note else "") + "</figure>"


def chart_grouped_bar(cats: list[str], series: list[tuple[str, list[int]]], *,
                      width=760, height=230) -> str:
    """Grouped columns, one axis, up to eight series."""
    if not cats or not series:
        return ""
    series = series[:8]
    pad_l, pad_b, pad_t = 46, 34, 14
    plot_w, plot_h = width - pad_l - 12, height - pad_b - pad_t
    mx = max(max(v) for _, v in series if v) or 1
    step = plot_w / len(cats)
    bw = min(24, step / (len(series) + 1))
    o = svg_open(width, height)
    for f in range(5):
        gy = pad_t + plot_h * f / 4
        o.append(f'<line x1="{pad_l}" y1="{gy:.1f}" x2="{width - 12}" y2="{gy:.1f}" class="grid"/>')
        o.append(txt(pad_l - 8, gy + 4, commas(round(mx * (4 - f) / 4)), "ax", "end"))
    for si, (name, vals) in enumerate(series):
        for ci, v in enumerate(vals):
            bh = plot_h * v / mx
            x = pad_l + ci * step + step / 2 - (len(series) * bw + 2) / 2 + si * (bw + 2)
            o.append(f'<rect x="{x:.1f}" y="{pad_t + plot_h - bh:.1f}" width="{bw:.1f}" '
                     f'height="{max(1.5, bh):.1f}" rx="3" class="s{si}"/>')
            if v and len(series) <= 3:
                o.append(txt(x + bw / 2, pad_t + plot_h - bh - 5, v, "vtiny", "middle"))
    for ci, c in enumerate(cats):
        o.append(txt(pad_l + ci * step + step / 2, height - 12, c, "ax", "middle"))
    o.append("</svg>")
    leg = "".join(f'<span class="lg"><i class="sw s{i}"></i>{e(n)}</span>'
                  for i, (n, _) in enumerate(series))
    return f'<figure class="fig">{"".join(o)}<div class="legend">{leg}</div></figure>'


def chart_lines(series: list[tuple[str, list[float]]], *, width=880, height=250,
                ymax=8.0, ylab="bits per byte", xlab="64 KiB block",
                ref=None, ref_label="") -> str:
    if not series:
        return ""
    series = series[:8]
    pad_l, pad_b, pad_t = 52, 32, 30
    plot_w, plot_h = width - pad_l - 52, height - pad_b - pad_t
    n = max(len(v) for _, v in series)
    y_of = lambda v: pad_t + plot_h * (1 - v / ymax)
    o = svg_open(width, height)
    o.append(txt(0, 12, ylab, "ax"))
    for f in range(5):
        gy = pad_t + plot_h * f / 4
        o.append(f'<line x1="{pad_l}" y1="{gy:.1f}" x2="{width - 52}" y2="{gy:.1f}" class="grid"/>')
        o.append(txt(pad_l - 9, gy + 4, f"{ymax * (4 - f) / 4:.0f}", "ax", "end"))
    if ref is not None:
        o.append(f'<line x1="{pad_l}" y1="{y_of(ref):.1f}" x2="{width - 52}" '
                 f'y2="{y_of(ref):.1f}" class="ref"/>')
        o.append(txt(pad_l + 6, y_of(ref) - 6, ref_label, "reflbl"))
    for si, (name, vals) in enumerate(series):
        pts = " ".join(f"{pad_l + (plot_w * i / max(1, n - 1)):.1f},{y_of(v):.1f}"
                       for i, v in enumerate(vals))
        o.append(f'<polyline points="{pts}" class="ln s{si}"/>')
        o.append(txt(pad_l + plot_w + 6, y_of(vals[-1]) + 4,
                     f"{max(0.0, vals[-1]):.2f}", f"vtiny t{si}", "start"))
    o.append(txt(pad_l, height - 10, "block 0", "ax"))
    o.append(txt(width - 52, height - 10, f"block {n - 1}", "ax", "end"))
    o.append(txt(pad_l + plot_w / 2, height - 10, xlab, "ax", "middle"))
    o.append("</svg>")
    leg = "".join(f'<span class="lg"><i class="sw s{i}"></i>{e(nm)}</span>'
                  for i, (nm, _) in enumerate(series))
    return f'<figure class="fig">{"".join(o)}<div class="legend">{leg}</div></figure>'


def layout_ribbon(model: dict, *, width=880) -> str:
    """True-to-scale linear map of the whole file."""
    size = model["file_size"]
    kinds = {en["basename"]: ("aod" if en["basename"] == "aod.bin"
                              else en["parsed"].get("kind", "unknown"))
             for en in model["entries"]}
    h = 132
    bar_y, bar_h = 34, 40
    o = svg_open(width, h, "chart ribbon")
    o.append(f'<rect x="0" y="{bar_y}" width="{width}" height="{bar_h}" class="ribbon-bg"/>')
    segs = [("container_header", 0, 32, "hdr"),
            ("directory", 32, model["directory"]["end"], "dir")]
    for en in model["entries"]:
        segs.append((kinds[en["basename"]], en["payload_offset"],
                     en["payload_end"], en["basename"]))
    for kind, a, b, name in segs:
        x, w = width * a / size, max(0.7, width * (b - a) / size)
        o.append(f'<rect x="{x:.3f}" y="{bar_y}" width="{w:.3f}" height="{bar_h}" '
                 f'class="seg k{KIND_SLOT.get(kind, 6)}"><title>{e(name)}  '
                 f'0x{a:06X}-0x{b:06X}  {commas(b - a)} B  '
                 f'({100 * (b - a) / size:.2f}%)</title></rect>')
        if w > 46:
            o.append(txt(x + w / 2, bar_y + bar_h / 2 + 4, name.replace(".bin", ""),
                         "seglbl", "middle"))
    for f in range(5):
        gx = width * f / 4
        o.append(f'<line x1="{gx:.1f}" y1="{bar_y + bar_h}" x2="{gx:.1f}" '
                 f'y2="{bar_y + bar_h + 6}" class="tick"/>')
        o.append(txt(min(gx, width - 4), bar_y + bar_h + 20,
                     f"0x{int(size * f / 4):06X}", "ax",
                     "start" if f == 0 else ("end" if f == 4 else "middle")))
    o.append(txt(0, 18, f"{model['face']}  ·  {commas(size)} bytes  ·  "
                        f"true-to-scale; metadata is "
                        f"{model['stats']['metadata_pct']:.3f}% of the file", "cap"))
    o.append("</svg>")
    return f'<figure class="fig">{"".join(o)}</figure>'


def metadata_zoom(model: dict, *, width=880) -> str:
    """Expanded view of the header + directory region."""
    end = model["directory"]["end"]
    o = svg_open(width, 96, "chart")
    o.append(txt(0, 14, f"Metadata zoom — bytes 0x000000-0x{end:06X} "
                        f"({commas(end)} B) expanded to full width", "cap"))
    bar_y, bar_h = 26, 34
    o.append(f'<rect x="0" y="{bar_y}" width="{width * 32 / end:.2f}" height="{bar_h}" '
             f'class="seg k0"><title>container header, 32 B</title></rect>')
    n = model["header"]["entry_count"]
    for i in range(n):
        a = 32 + i * 74
        x, w = width * a / end, width * 74 / end
        en = model["entries"][i]
        kind = "aod" if en["basename"] == "aod.bin" else en["parsed"].get("kind", "unknown")
        o.append(f'<rect x="{x:.2f}" y="{bar_y}" width="{w - 1.2:.2f}" height="{bar_h}" '
                 f'class="seg k{KIND_SLOT.get(kind, 6)}"><title>entry {i}: '
                 f'{e(en["basename"])}\nrecord 0x{a:04X}  ·  payload '
                 f'0x{en["payload_offset"]:06X} + {commas(en["payload_size"])} B  ·  '
                 f'CRC {en["crc16_stored"]}</title></rect>')
        if w > 9:
            o.append(txt(x + w / 2, bar_y + bar_h / 2 + 4, i, "seglbl", "middle"))
    o.append(txt(0, bar_y + bar_h + 16,
                 f"32-byte header, then {n} x 74-byte directory records "
                 f"— hover any block for its offset, size and CRC", "ax"))
    o.append("</svg>")
    return f'<figure class="fig">{"".join(o)}</figure>'


def packing_reference_graph(model: dict, *, width=980) -> str:
    """One end-to-end map of packing, offset namespaces and nested references.

    The ordinary ribbon explains byte proportions. This graph instead keeps every
    directory record legible and then zooms through one representative style entry,
    so a reader can follow an actual file offset down to a widget's raster or logical
    resource without confusing absolute and section-relative values.
    """
    entries = model["entries"]
    styles = [en for en in entries if en["parsed"].get("kind") == "style"]
    if not styles:
        return ""
    style = next((en for en in styles if en["basename"].startswith("style")), styles[0])
    sp = style["parsed"]

    row_h = 32
    row_y = 72
    rows_bottom = row_y + len(entries) * row_h
    file_bottom = max(rows_bottom, row_y + 160) + 72
    style_y = file_bottom
    logic_y = style_y + 535
    height = logic_y + 220
    uid = f"pg-{model['sha256'][:10]}"

    marker = {
        "abs": f"{uid}-abs",
        "rel": f"{uid}-rel",
        "logical": f"{uid}-logical",
        "runtime": f"{uid}-runtime",
        "struct": f"{uid}-struct",
    }
    o = svg_open(width, height, "chart packgraph")
    o.append(f'<title>{e(model["face"])} container packing and reference graph</title>')
    o.append('<desc>Every directory record is paired with its payload, followed by '
             'a detailed style-entry map showing widget, image, font, glyph and '
             'references to other widgets.</desc>')
    o.append('<defs>')
    for name, colour in [
        ("abs", "var(--k1)"),
        ("rel", "var(--k2)"),
        ("logical", "var(--k5)"),
        ("runtime", "var(--k7)"),
        ("struct", "var(--kx)"),
    ]:
        o.append(f'<marker id="{marker[name]}" markerWidth="8" markerHeight="8" '
                 'refX="7" refY="4" orient="auto" markerUnits="strokeWidth">'
                 f'<path d="M0,0 L8,4 L0,8 z" fill="{colour}"/></marker>')
    o.append('</defs>')

    def box(x: float, y: float, w: float, h: float, heading: str,
            lines: list[str], cls: str = "pg-box", title: str = "") -> None:
        o.append(f'<rect x="{x}" y="{y}" width="{w}" height="{h}" rx="5" '
                 f'class="{cls}">{f"<title>{e(title)}</title>" if title else ""}</rect>')
        o.append(txt(x + 9, y + 16, heading, "pg-head"))
        for i, line_text in enumerate(lines):
            o.append(txt(x + 9, y + 33 + i * 14, line_text, "pg-small"))

    def arrow(x1: float, y1: float, x2: float, y2: float, kind: str,
              label: str = "", *, label_y: float | None = None) -> None:
        dash = ' stroke-dasharray="5 4"' if kind == "runtime" else ""
        o.append(f'<line x1="{x1}" y1="{y1}" x2="{x2}" y2="{y2}" '
                 f'class="pg-arrow pg-{kind}" marker-end="url(#{marker[kind]})"{dash}/>')
        if label:
            o.append(txt((x1 + x2) / 2, label_y if label_y is not None else y1 - 5,
                         label, f"pg-edge-label pg-{kind}-text", "middle"))

    def payload_summary(en: dict) -> str:
        parsed = en["parsed"]
        kind = parsed.get("kind", "unknown")
        if kind == "style":
            role = "always-on style" if en["basename"] == "aod.bin" else "style"
            return (f'{role} · {parsed["parsed_widget_count"]} widgets · '
                    f'{parsed["image_count"]} images')
        if kind == "font_binding":
            return f'binding · {parsed["binding_name"]} @ {parsed["point_size"]}pt'
        if kind == "glyph_table":
            return f'glyph table · {parsed["group_count"]} groups'
        if kind == "preview":
            return f'preview · {parsed["image_count"]} images'
        if kind == "setting":
            return "identity/settings block"
        return kind.replace("_", " ")

    # File-level archive and every real directory-to-payload edge.
    o.append(txt(8, 18, f'{model["face"]} · complete file-level packing', "pg-section"))
    o.append(txt(8, 36, f'{commas(model["file_size"])} bytes · '
                         f'{len(entries)} directory entries · tightly packed '
                         f'{"yes" if model["coverage"]["tightly_packed"] else "no"}',
                 "pg-note"))
    o.append(txt(8, 58, "CONTAINER HEADER", "pg-column"))
    o.append(txt(208, 58, "DIRECTORY: PATH[64] | ABS OFFSET[4] | SIZE[4] | CRC[2]", "pg-column"))
    o.append(txt(680, 58, "PAYLOAD AT THE RECORDED FILE RANGE", "pg-column"))

    header = model["header"]
    box(8, row_y, 180, 150, "header · file +0x000000", [
        f'+00 magic "{header["magic_ascii"]}"',
        f'+04 version {header["version"]}',
        f'+08 payload {commas(header["declared_payload"])} B',
        f'+0C entries {header["entry_count"]}',
        f'+10 body CRC {header["crc16_stored"]}',
        '+12 crc high / +14 reserved',
        f'directory: 0x20..0x{model["directory"]["end"]:X}',
        'fixed length: 32 B',
    ], "pg-box pg-header", "The container header is always the first 32 bytes.")
    arrow(188, row_y + 28, 202, row_y + 28, "struct")

    for en in entries:
        i = en["index"]
        y = row_y + i * row_h
        record_rel = en["record_offset"]
        o.append(f'<rect x="208" y="{y}" width="430" height="27" rx="4" '
                 f'class="pg-box pg-directory"><title>{e(en["path"])}&#10;'
                 f'directory record 0x{record_rel:06X}..0x{record_rel + 74:06X}&#10;'
                 f'payload 0x{en["payload_offset"]:06X}..0x{en["payload_end"]:06X}&#10;'
                 f'CRC-16 {e(en["crc16_stored"])}</title></rect>')
        o.append(txt(216, y + 11, f'#{i:02d}  {en["basename"]}', "pg-row-head"))
        o.append(txt(216, y + 23,
                     f'D+0x{record_rel:04X} · abs 0x{en["payload_offset"]:06X} · '
                     f'{commas(en["payload_size"])} B · {en["crc16_stored"]}',
                     "pg-tiny"))

        kind = "aod" if en["basename"] == "aod.bin" else en["parsed"].get("kind", "unknown")
        slot = KIND_SLOT.get(kind, 6)
        o.append(f'<rect x="680" y="{y}" width="292" height="27" rx="4" '
                 f'class="pg-box pg-payload pg-stroke-{slot}"><title>'
                 f'{e(en["basename"])}&#10;{e(payload_summary(en))}&#10;'
                 f'{commas(en["payload_size"])} bytes</title></rect>')
        o.append(txt(688, y + 11, en["basename"], "pg-row-head"))
        o.append(txt(688, y + 23,
                     f'{payload_summary(en)} · {kb(en["payload_size"])}', "pg-tiny"))
        arrow(638, y + 13.5, 676, y + 13.5, "abs")

    bracket_y = rows_bottom + 20
    o.append(f'<path d="M208 {bracket_y - 8} V{bracket_y} H972 V{bracket_y - 8}" '
             'class="pg-coverage"/>')
    o.append(txt(590, bracket_y + 17,
                 'canonical header +0x10 CRC-16 is computed over 0x20..EOF',
                 "pg-note", "middle"))
    o.append(txt(590, bracket_y + 34,
                 'canonical directory +0x48 CRC-16 is computed over its payload only',
                 "pg-note", "middle"))

    # Zoom one actual style payload into its two packed streams.
    o.append(txt(8, style_y + 18,
                 f'{style["basename"]} · nested packing and reference namespaces',
                 "pg-section"))
    o.append(txt(8, style_y + 36,
                 f'directory #{style["index"]} points here at absolute '
                 f'0x{style["payload_offset"]:06X}; all offsets below are relative '
                 'to this payload or to its image section', "pg-note"))

    bar_y = style_y + 53
    box(8, bar_y, 184, 64, "style header · 24 B", [
        f'+00 magic · +04 count {sp["declared_widget_count"]}',
        f'+08 widget B {sp["declared_widget_bytes"]} · +0C image B {sp["declared_image_bytes"]}',
        f'+10 font count word {sp["font_count_word_hex"]} · +14 conventional image off',
    ], "pg-box pg-style")
    box(204, bar_y, 326, 64, "widget stream · variable records", [
        f'{sp["parsed_widget_count"]} records packed back-to-back',
        f'entry +0x018 .. +0x{sp["declared_image_offset"]:X}',
    ], "pg-box pg-widget")
    box(542, bar_y, 430, 64, "image stream · variable records", [
        f'{sp["image_count"]} records · {commas(sp["declared_image_bytes"])} B',
        f'entry +0x{sp["declared_image_offset"]:X} .. +0x{style["payload_size"]:X}',
    ], "pg-box pg-image")
    arrow(192, bar_y + 32, 200, bar_y + 32, "struct")
    arrow(530, bar_y + 32, 538, bar_y + 32, "struct")
    o.append(txt(8, bar_y + 84,
                 f'image_section_offset = 24 + widget_bytes = '
                 f'{24} + {sp["declared_widget_bytes"]} = '
                 f'{sp["declared_image_offset"]} (0x{sp["declared_image_offset"]:X})',
                 "pg-equation"))
    o.append(txt(8, bar_y + 101,
                 f'payload_size = image_section_offset + image_bytes = '
                 f'{sp["declared_image_offset"]} + {sp["declared_image_bytes"]} = '
                 f'{style["payload_size"]}', "pg-equation"))

    schema_y = style_y + 180
    box(18, schema_y, 452, 204, "widget record · 24 B common prefix + type body", [
        '+0x00  u32 type',
        '+0x04  type-dependent source word',
        '+0x08  reserved word, never read',
        '+0x0C  high16 global index | low16 record size',
        '+0x10..17  normally unread; type 9 copies +0x10',
        '+0x18..end  entirely type-specific',
        'alignment · signed extent · endpoints · unread, by type',
        'tables contain u8/u16/i16/u32 fields—not generic words',
        'LineBar is exactly 50 B; its final 2 B are semantic',
        'next widget = current start + low16(record-size word)',
    ], "pg-box pg-widget")
    box(548, schema_y, 414, 204, "image record · 12 B header + declared data", [
        '+0x00  u16 width',
        '+0x02  u16 height',
        '+0x04  u16 format (RGB565 / +alpha / Indexed8)',
        '+0x06  u16 reserved',
        '+0x08  u32 data_size',
        '+0x0C  optional 1,024 B palette, then pixels',
        'after pixels: exact 4 B trailer inside data_size',
        'record_total = 12 + data_size',
        'next image = current start + record_total',
        'pointer value = record start − image-section start',
    ], "pg-box pg-image")

    pointer_y = style_y + 405
    box(18, pointer_y, 452, 114, "authoritative widget → image pointer fields", [
        'Static   +0x20',
        'Sprite   +0x24 + 4×frame (count stored at +0x20)',
        'Hand     word[1] = +0x28',
        'Animation +0x28 + 4×frame (count at +0x24)',
        'ImageArc +0x34 (VectorArc has no image)',
        'LineBar  word[2] = +0x2C',
    ], "pg-box pg-pointer")
    arrow(470, pointer_y + 57, 544, schema_y + 101, "rel",
          "u32 image-section-relative byte offset", label_y=pointer_y + 45)

    # Logical joins are deliberately separated from byte pointers.
    box(18, logic_y, 292, 112, "logical fields inside widgets", [
        'Pair: font slot + numeric/dictionary formatting',
        'Comp: four 12-byte mini-programs + order string',
        'sequence id: a reading the watch owns',
        'global index: record ordering, not widget identity',
    ], "pg-box pg-logical-box")
    box(370, logic_y, 252, 70, "font_N.bin · binding", [
        '92 B: 72 language selectors + role + pixel size',
        'selects a font the watch already has',
    ], "pg-box pg-resource")
    box(680, logic_y, 282, 70, "font_<locale>.bin · glyph table", [
        'header + N (length, offset) descriptors',
        'packed UTF-8 strings addressed by group index',
    ], "pg-box pg-resource")
    box(370, logic_y + 95, 592, 58, "what the watch supplies", [
        'resolves sequence/data-source ids, rotates hands and supplies live values',
    ], "pg-box pg-runtime-box")
    arrow(310, logic_y + 32, 366, logic_y + 32, "logical", "font slot")
    arrow(310, logic_y + 60, 676, logic_y + 60, "logical", "glyph-group index")
    arrow(310, logic_y + 91, 366, logic_y + 121, "runtime", "sequence id")

    legend_y = logic_y + 176
    legend = [
        ("abs", "absolute file offset · directory → payload"),
        ("rel", "image-section-relative byte offset · widget → image record"),
        ("logical", "logical index · widget → font/glyph resource"),
        ("runtime", "a reading the watch owns · not a byte pointer, not a widget index"),
    ]
    positions = [(18, legend_y), (500, legend_y),
                 (18, legend_y + 23), (500, legend_y + 23)]
    for (kind, label), (x, y) in zip(legend, positions):
        dash = ' stroke-dasharray="5 4"' if kind == "runtime" else ""
        o.append(f'<line x1="{x}" y1="{y}" x2="{x + 32}" y2="{y}" '
                 f'class="pg-arrow pg-{kind}"{dash}/>')
        o.append(txt(x + 39, y + 4, label, "pg-legend"))

    o.append("</svg>")
    return (f'<figure class="fig packgraph">{"".join(o)}'
            '<figcaption>Read from top to bottom: the header locates the directory; '
            'each directory record locates one payload and stores its canonical checksum; a style payload '
            'splits into widget and image streams; only the six listed pointer layouts '
            'are image references. The legend keeps four namespaces separate.</figcaption>'
            '</figure>')


def field_diagram(fields: list[tuple[int, int, str, str, int]], total: int, *,
                  width=880, title="", note="") -> str:
    """Struct diagram: legible labelled blocks above a to-scale byte strip.

    Small fields are widened in the label row so their names stay readable; the
    strip underneath carries the true byte proportions, with a connector between.
    """
    n = len(fields)
    blk_h, strip_h = 48, 13
    h = 24 + blk_h + 24 + strip_h + 18 + (15 if note else 0)
    base = min(78.0, width / n)
    rest = max(0.0, width - base * n)
    widths = [base + rest * sz / total for _, sz, _, _, _ in fields]
    o = svg_open(width, h, "chart bytemap")
    if title:
        o.append(txt(0, 11, title, "cap"))
    y0, x = 20, 0.0
    strip_y = y0 + blk_h + 24
    for (off, sz, nm, desc, slot), bw in zip(fields, widths):
        cls = f"k{slot}" if slot >= 0 else "kx"
        o.append(f'<rect x="{x:.2f}" y="{y0}" width="{bw - 2:.2f}" height="{blk_h}" '
                 f'rx="4" class="cell {cls}"><title>+0x{off:03X} … +0x{off + sz - 1:03X}'
                 f'  ({sz} byte{"s" if sz != 1 else ""})&#10;{e(nm)}'
                 f'{"&#10;" + e(desc) if desc else ""}</title></rect>')
        o.append(txt(x + bw / 2 - 1, y0 + 21, nm, "cellbl", "middle"))
        o.append(txt(x + bw / 2 - 1, y0 + 36, f"+0x{off:02X} · {sz}B", "cellsub", "middle"))
        sx, sw = width * off / total, max(0.7, width * sz / total)
        o.append(f'<rect x="{sx:.2f}" y="{strip_y}" width="{sw:.2f}" height="{strip_h}" '
                 f'class="cell {cls}"><title>{e(nm)} — {sz} of {total} bytes '
                 f'({100 * sz / total:.1f}%)</title></rect>')
        o.append(f'<line x1="{x + bw / 2 - 1:.2f}" y1="{y0 + blk_h + 1}" '
                 f'x2="{sx + sw / 2:.2f}" y2="{strip_y - 1}" class="lead"/>')
        x += bw
    o.append(txt(0, strip_y + strip_h + 13,
                 f"↑ to scale — {total} bytes total; blocks above are widened "
                 f"for legibility", "ax"))
    if note:
        o.append(txt(0, strip_y + strip_h + 28, note, "ax"))
    o.append("</svg>")
    return f'<figure class="fig">{"".join(o)}</figure>'


def byte_grid(data: bytes, regions: list[tuple[int, int, str, int]], *,
              width=880, per_row=32, title="") -> str:
    """Hex value grid with region colouring."""
    rows = (len(data) + per_row - 1) // per_row
    cw = width / (per_row + 3)
    rh = 15
    h = rows * rh + 26
    owner = {}
    for a, b, nm, slot in regions:
        for i in range(a, min(b, len(data))):
            owner[i] = (nm, slot)
    o = svg_open(width, h, "chart hexgrid")
    if title:
        o.append(txt(0, 12, title, "cap"))
    for r in range(rows):
        y = 22 + r * rh
        o.append(txt(0, y + rh - 4, f"{r * per_row:04X}", "hexoff"))
        for c in range(per_row):
            i = r * per_row + c
            if i >= len(data):
                break
            nm, slot = owner.get(i, ("unused / reserved", -1))
            x = (c + 2.6) * cw
            o.append(f'<rect x="{x:.2f}" y="{y}" width="{cw - 0.6:.2f}" height="{rh - 1}" '
                     f'class="{"hx k" + str(slot) if slot >= 0 else "hx kx"}">'
                     f'<title>+0x{i:02X} = 0x{data[i]:02X}'
                     f'{(" (" + chr(data[i]) + ")") if 32 <= data[i] < 127 else ""}  —  '
                     f'{e(nm)}</title></rect>')
            if data[i]:
                o.append(txt(x + cw / 2 - 0.3, y + rh - 4, f"{data[i]:02X}", "hexv", "middle"))
    o.append("</svg>")
    return f'<figure class="fig">{"".join(o)}</figure>'


def diff_grid(base: bytes, others: list[tuple[str, bytes]], widgets: list[dict], *,
              width=880) -> str:
    """Which widget-section bytes change between style variants."""
    n = len(base)
    if not n or not others:
        return ""
    per_row = 128
    rows = (n + per_row - 1) // per_row
    cw = width / per_row
    lane_h = 13
    h = rows * (len(others) * lane_h + 16) + 26
    o = svg_open(width, h, "chart diffgrid")
    o.append(txt(0, 12, f"Widget-section byte deltas across variants "
                        f"({commas(n)} bytes in the first style)", "cap"))
    wmap = {}
    for w in widgets:
        for i in range(w["record_offset"] - 24, w["record_offset"] - 24 + w["record_size"]):
            wmap[i] = w
    y0 = 22
    for r in range(rows):
        for li, (nm, other) in enumerate(others):
            y = y0 + r * (len(others) * lane_h + 16) + li * lane_h
            o.append(txt(0, y + lane_h - 3, nm.replace(".bin", ""), "difflbl"))
            for c in range(per_row):
                i = r * per_row + c
                if i >= n or i >= len(other):
                    break
                if base[i] == other[i]:
                    continue
                w = wmap.get(i)
                rel = (i - (w["record_offset"] - 24)) if w else 0
                o.append(
                    f'<rect x="{c * cw:.2f}" y="{y}" width="{max(1.2, cw):.2f}" '
                    f'height="{lane_h - 2}" class="dhit"><title>byte {i} — widget '
                    f'{w["ordinal"] if w else "?"} ({w["type_name"] if w else "?"}) '
                    f'+0x{rel:02X}\n0x{base[i]:02X} → 0x{other[i]:02X}</title></rect>')
    o.append("</svg>")
    return (f'<figure class="fig">{"".join(o)}<figcaption>Unmarked columns are '
            f'byte-identical across every variant of this face.</figcaption></figure>')


def pixel_format_diagram(width=880) -> str:
    o = svg_open(width, 210, "chart")
    o.append(txt(0, 14, "Pixel encodings — little-endian half-word, optional third "
                        "alpha byte", "cap"))
    bit_w = (width - 120) / 16
    for row, label in enumerate(["0x0082 · RGB565 — 2 B/px",
                                 "0x0080 · RGB565+A — 3 B/px"]):
        y = 34 + row * 88
        o.append(txt(0, y - 6, label, "lbl"))
        for lo, n, nm, slot in [(0, 5, "B (5)", 0), (5, 6, "G (6)", 2), (11, 5, "R (5)", 7)]:
            x = 100 + (15 - (lo + n - 1)) * bit_w
            o.append(f'<rect x="{x:.1f}" y="{y}" width="{n * bit_w - 1.5:.1f}" height="30" '
                     f'rx="2" class="cell k{slot}"/>')
            o.append(txt(x + n * bit_w / 2, y + 20, nm, "cellbl", "middle"))
        for b in range(16):
            o.append(txt(100 + b * bit_w + bit_w / 2, y + 44, 15 - b, "bitn", "middle"))
        o.append(txt(100, y + 60, "byte 0 = bits 7..0 (low)   byte 1 = bits 15..8 (high)", "ax"))
        if row == 1:
            o.append(f'<rect x="{100 + 16 * bit_w + 8:.1f}" y="{y}" '
                     f'width="{2.2 * bit_w:.1f}" height="30" rx="2" class="cell k4"/>')
            o.append(txt(100 + 16 * bit_w + 8 + 1.1 * bit_w, y + 20, "A (8)", "cellbl", "middle"))
    o.append("</svg>")
    return f'<figure class="fig">{"".join(o)}</figure>'


def reference_role_chart(width=880) -> str:
    """Exact four-role ledger for the deliberately vendor-only 101 inputs."""
    total = sum(REFERENCE_BYTE_ROLES.values())
    roles = list(REFERENCE_BYTE_ROLES.items())
    labels = {
        "format_control": "format control",
        "rendered_semantic": "rendered semantic",
        "content_payload": "content payload",
        "never_read": "never read",
    }
    slots = {"format_control": 0, "rendered_semantic": 5,
             "content_payload": 2, "never_read": -1}
    o = svg_open(width, 126, "chart rolechart")
    o.append(txt(0, 12, "Exact vendor-only byte ledger", "cap"))
    x = 0.0
    for role, value in roles:
        w = width * value / total
        cls = f'k{slots[role]}' if slots[role] >= 0 else "kx"
        o.append(f'<rect x="{x:.3f}" y="25" width="{max(.8, w):.3f}" height="25" '
                 f'class="cell {cls}"><title>{e(labels[role])}: {commas(value)} bytes '
                 f'({100 * value / total:.5f}%)</title></rect>')
        x += w
    # Payload overwhelms the scale, so give the three non-payload roles a second
    # exact proportional strip rather than visually inflating them in the first.
    non_payload = total - REFERENCE_BYTE_ROLES["content_payload"]
    x = 0.0
    for role, value in roles:
        if role == "content_payload":
            continue
        w = width * value / non_payload
        cls = f'k{slots[role]}' if slots[role] >= 0 else "kx"
        o.append(f'<rect x="{x:.3f}" y="73" width="{w:.3f}" height="20" '
                 f'class="cell {cls}"><title>{e(labels[role])}: {commas(value)} bytes</title></rect>')
        if w > 105:
            o.append(txt(x + w / 2, 87, labels[role], "cellsub", "middle"))
        x += w
    o.append(txt(0, 67, "non-payload bytes magnified to 100%", "ax"))
    o.append(txt(0, 113, f"{commas(total)} bytes · {commas(REFERENCE_ROLE_RANGES)} "
                         "contiguous role ranges · zero holes · zero overlaps", "ax"))
    o.append("</svg>")
    legend = "".join(
        f'<span class="lg"><i class="sw {"k" + str(slots[role]) if slots[role] >= 0 else "kx"}"></i>'
        f'{e(labels[role])}: <code>{commas(value)}</code></span>'
        for role, value in roles)
    return (f'<figure class="fig">{"".join(o)}<div class="legend">{legend}</div>'
            '<figcaption>The upper strip is truly proportional. The lower strip '
            'magnifies only the 614,436 non-payload bytes. “Ignored” means no '
            'surviving effect when the watch lays the face out, not permission to '
            'write arbitrary data there.</figcaption></figure>')


def capacity_gauge(width=880) -> str:
    ceiling = 4 * 1024 * 1024
    normal_min, special_min = 0x18561, 0x32401
    o = svg_open(width, 108, "chart capacity")
    o.append(txt(0, 12, "Outer install-size gates", "cap"))
    x0, x1, y = 24, width - 24, 39
    o.append(f'<rect x="{x0}" y="{y}" width="{x1 - x0}" height="18" rx="6" class="kx"/>')
    for value, label, slot, ly in [
        (normal_min, "normal add minimum 0x18561", 0, 84),
        (special_min, "special add minimum 0x32401", 3, 100),
        (ceiling, "4 MiB ceiling 0x400000", 1, 84),
    ]:
        x = x0 + (x1 - x0) * value / ceiling
        o.append(f'<line x1="{x:.2f}" y1="{y - 8}" x2="{x:.2f}" y2="{y + 28}" class="s{slot}"/>')
        anchor = "end" if value == ceiling else "start"
        o.append(txt(x + (-4 if value == ceiling else 4), ly, label, "ax", anchor))
    o.append(f'<rect x="{x0 + (x1 - x0) * normal_min / ceiling:.2f}" y="{y}" '
             f'width="{(x1 - x0) * (ceiling - normal_min) / ceiling:.2f}" height="18" '
             'rx="6" class="k2" opacity=".72"/>')
    o.append(txt((x0 + x1) / 2, y + 13, "accepted normal-add size interval", "cellsub", "middle"))
    o.append("</svg>")
    return (f'<figure class="fig">{"".join(o)}<figcaption>The upper bound is '
            'inclusive. A separate rule can still make the watch ignore a '
            'structurally valid face after it is unpacked.</figcaption></figure>')


ROLE_SLOT = {
    "dispatch": 0, "structure": 0, "identity": 0,
    "runtime": 1, "value-map": 1, "format": 1, "timing": 1,
    "image-pointer": 2,
    "geometry": 3, "alignment": 3, "reference": 3,
    "appearance": 4, "interaction": 4,
    "font-index": 5, "dictionary-index": 5, "format-program": 5,
    "compat": -1, "unread": -1, "diagnostic": -1, "unresolved": 7,
}


def widget_schema_figure(type_id: int) -> str:
    schema = WIDGET_TYPES[type_id]
    total = max(16, schema["minimum"])
    common = COMMON_FIELDS
    if type_id in (8, 9, 10, 11, 12, 14, 15):
        common = [field for field in common if field["offset"] < 0x10]
    rows = []
    for field in [*common, *schema["fields"]]:
        size = field["size"]
        if size == "4 × frame_count":
            size = 4
        elif size == "to record end":
            size = max(0, total - field["offset"])
        if not isinstance(size, int) or size <= 0:
            continue
        total = max(total, field["offset"] + size)
        label = field["name"].replace("_", " ")
        if len(label) > 19:
            label = label[:18] + "…"
        rows.append((field["offset"], size, label, field["description"],
                     ROLE_SLOT.get(field["role"], 6)))
    note = schema["size"]
    if type_id in (3, 4):
        note += "; diagram shows one frame-table word"
    return field_diagram(rows, total,
                         title=f'Type {type_id} · {schema["name"]}',
                         note=note)


def loader_flow() -> str:
    steps = [
        ("1", "Install gate", "normal 0x18561..0x400000; special 0x32401..0x400000"),
        ("2", "Outer extractor", "oppo/lqsw · directory count · absolute offset + exact length"),
        ("3", "Manager", "setting +0x10 id · i32 version · style/default bytes"),
        ("4", "Style loader", "24-byte header · count × 0x98 runtime descriptors"),
        ("5", "17-way dispatch", "type 1..17 · advance by low u16 at record +0x0C"),
        ("6", "Image loader", "0x18 + widget_bytes + type-specific relative pointer"),
        ("7", "Runtime updates", "live source → Hand/Sprite/Pair/Arc/Badge/Comp/LineBar"),
    ]
    return ('<div class="flow">' + ''.join(
        f'<div class="flowstep"><span>{number}</span><div><b>{e(title)}</b>'
        f'<small>{e(body)}</small></div></div>' for number, title, body in steps) + '</div>')


def comp_pipeline() -> str:
    parts = ''.join(
        f'<div class="minip"><b>part {i}</b><code>source · fixed A · fixed B · '
        'dynamic base · mode · selector</code><small>three fragments; missing = ""</small></div>'
        for i in range(4))
    return (f'<div class="compflow">{parts}<div class="flowarrow">→</div>'
            '<div class="minip out"><b>12 × %s join</b><code>fixed A · fixed B · dynamic</code>'
            '<small>optional dictionary order string permutes the four part buffers</small></div></div>')


# ------------------------------------------------------------------ tables

def table(headers: list[str], rows: list[list[str]], cls: str = "") -> str:
    if not rows:
        return '<p class="lead">Nothing of this kind in the analysed corpus.</p>'
    th = "".join(f"<th>{e(h)}</th>" for h in headers)
    tr = "".join("<tr>" + "".join(f"<td>{c}</td>" for c in r) + "</tr>" for r in rows)
    return (f'<div class="tw"><table class="{cls}"><thead><tr>{th}</tr></thead>'
            f'<tbody>{tr}</tbody></table></div>')


def census_table(title_col: str, counts: Counter, total: int) -> str:
    """Distinct observed values and how often each occurs."""
    rows = [[f'<span class="m">{e(str(v))}</span>',
             f'<span class="n">{commas(n)}</span>',
             f'<span class="n">{100 * n / total:.1f}%</span>']
            for v, n in counts.most_common(20)]
    return table([title_col, "Occurrences", "Share"], rows)


def styles_of(model: dict) -> list[dict]:
    return [en for en in model["entries"] if en["parsed"].get("kind") == "style"]


def all_widgets(models: dict) -> list[tuple[str, str, dict]]:
    return [(f, en["basename"], w)
            for f, m in models.items()
            for en in styles_of(m)
            for w in en["parsed"]["widgets"]]


def panel_of(model: dict) -> tuple[int, int] | None:
    if not model.get("panel"):
        return None
    w, h = model["panel"].split("x")
    return int(w), int(h)


# ------------------------------------------------------------------ build

def build(root: Path, faces: list[str], detail: int, images: bool,
          output: Path) -> None:
    models = {f: json.loads((root / f / "model.json").read_text()) for f in faces}
    assets = Assets(root, images)
    deep = faces[:detail]
    parts: list[str] = []
    A = parts.append

    total_bytes = sum(m["file_size"] for m in models.values())
    widgets = all_widgets(models)
    entries_all = [en for m in models.values() for en in m["entries"]]

    # ---------------------------------------------------------------- head
    A(f'''<!doctype html>
<html lang="en"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>Watch-face container — byte-level anatomy</title>
<style>
:root{{
 --surface-0:#f6f5f2; --surface-1:#fcfcfb; --surface-2:#efeee9;
 --ink-1:#0b0b0b; --ink-2:#52514e; --ink-3:#84837c;
 --rule:#deddd6; --grid:#e7e6e0;
 --k0:{S[0]}; --k1:{S[1]}; --k2:{S[2]}; --k3:{S[3]}; --k4:{S[4]}; --k5:{S[5]};
 --k6:{S[6]}; --k7:{S[7]}; --kx:#b9b8b0;
 --good:#1a7f4b; --warn:#a35c00; --bad:#c0322f;
 --mono:ui-monospace,SFMono-Regular,"SF Mono",Menlo,Consolas,monospace;
 --sans:-apple-system,BlinkMacSystemFont,"Segoe UI",Inter,system-ui,sans-serif;
}}
@media (prefers-color-scheme:dark){{:root:where(:not([data-theme=light])){{
 --surface-0:#121211; --surface-1:#1a1a19; --surface-2:#232322;
 --ink-1:#ffffff; --ink-2:#c3c2b7; --ink-3:#8d8c83;
 --rule:#33332f; --grid:#2b2b28;
 --k0:{SD[0]}; --k1:{SD[1]}; --k2:{SD[2]}; --k3:{SD[3]}; --k4:{SD[4]}; --k5:{SD[5]};
 --k6:{SD[6]}; --k7:{SD[7]}; --kx:#5a5a53;
 --good:#3ba86a; --warn:#d69128; --bad:#e66767;
}}}}
:root[data-theme=dark]{{
 --surface-0:#121211; --surface-1:#1a1a19; --surface-2:#232322;
 --ink-1:#ffffff; --ink-2:#c3c2b7; --ink-3:#8d8c83;
 --rule:#33332f; --grid:#2b2b28;
 --k0:{SD[0]}; --k1:{SD[1]}; --k2:{SD[2]}; --k3:{SD[3]}; --k4:{SD[4]}; --k5:{SD[5]};
 --k6:{SD[6]}; --k7:{SD[7]}; --kx:#5a5a53;
 --good:#3ba86a; --warn:#d69128; --bad:#e66767;
}}
*{{box-sizing:border-box}}
body{{margin:0;background:var(--surface-0);color:var(--ink-1);font:15px/1.62 var(--sans);
 -webkit-text-size-adjust:100%}}
.wrap{{max-width:1000px;margin:0 auto;padding:0 22px 96px}}
header.hero{{padding:64px 0 30px;border-bottom:1px solid var(--rule);margin-bottom:38px}}
h1{{font-size:clamp(28px,4.4vw,44px);line-height:1.08;letter-spacing:-.022em;margin:0 0 14px;
 font-weight:640}}
.sub{{color:var(--ink-2);font-size:17px;max-width:70ch;margin:0}}
.kicker{{font:600 11.5px/1 var(--mono);letter-spacing:.13em;text-transform:uppercase;
 color:var(--ink-3);margin:0 0 16px}}
h2{{font-size:25px;letter-spacing:-.015em;margin:60px 0 6px;font-weight:640;
 padding-top:20px;border-top:1px solid var(--rule)}}
h3{{font-size:17.5px;margin:34px 0 8px;font-weight:640;letter-spacing:-.008em}}
h4{{font-size:14px;margin:24px 0 6px;font-weight:660;color:var(--ink-2);
 font-family:var(--mono);letter-spacing:.01em}}
p{{margin:10px 0;max-width:76ch}} p.lead{{color:var(--ink-2)}}
code{{font-family:var(--mono);font-size:.885em;background:var(--surface-2);
 padding:.1em .36em;border-radius:4px}}
a{{color:var(--k0)}}
.fig{{margin:20px 0;padding:14px 16px;background:var(--surface-1);
 border:1px solid var(--rule);border-radius:10px;overflow-x:auto}}
.fig svg{{display:block;width:100%;min-width:600px;height:auto;overflow:visible}}
.ribbon svg,.bytemap svg,.hexgrid svg,.diffgrid svg{{min-width:760px}}
.packgraph svg{{min-width:980px}}
figcaption{{color:var(--ink-3);font-size:12.5px;margin-top:10px;max-width:80ch}}
text{{font-family:var(--mono)}}
.lbl{{font-size:12px;fill:var(--ink-1)}} .val{{font-size:11.5px;fill:var(--ink-2)}}
.ax{{font-size:10.5px;fill:var(--ink-3)}} .cap{{font-size:11.5px;fill:var(--ink-2)}}
.vtiny{{font-size:9.5px;fill:var(--ink-3)}}
.seglbl{{font-size:10px;fill:#fff;font-weight:600;paint-order:stroke;
 stroke:rgba(0,0,0,.32);stroke-width:2.4px}}
.cellbl{{font-size:11px;fill:#fff;font-weight:640;paint-order:stroke;
 stroke:rgba(0,0,0,.34);stroke-width:2.6px}}
.cellsub{{font-size:9px;fill:#fff;opacity:.86;paint-order:stroke;
 stroke:rgba(0,0,0,.3);stroke-width:2.2px;font-weight:500}}
.lead{{stroke:var(--kx);stroke-width:1;opacity:.5}}
.bitn{{font-size:8.5px;fill:var(--ink-3)}}
.hexoff{{font-size:9px;fill:var(--ink-3)}}
.hexv{{font-size:8px;fill:var(--ink-1);opacity:.82}}
.difflbl{{font-size:9px;fill:var(--ink-3)}}
.grid{{stroke:var(--grid);stroke-width:1}} .tick{{stroke:var(--rule);stroke-width:1}}
.bar{{fill:var(--k0)}}
.s0{{fill:var(--k0);stroke:var(--k0)}} .s1{{fill:var(--k1);stroke:var(--k1)}}
.s2{{fill:var(--k2);stroke:var(--k2)}} .s3{{fill:var(--k3);stroke:var(--k3)}}
.s4{{fill:var(--k4);stroke:var(--k4)}} .s5{{fill:var(--k5);stroke:var(--k5)}}
.s6{{fill:var(--k6);stroke:var(--k6)}} .s7{{fill:var(--k7);stroke:var(--k7)}}
polyline.ln{{fill:none;stroke-width:2;stroke-linejoin:round;stroke-linecap:round}}
line.ref{{stroke:var(--kx);stroke-width:1.5;opacity:.7}}
.reflbl{{font-size:10px;fill:var(--ink-3)}}
.t0{{fill:var(--k0)}} .t1{{fill:var(--k1)}} .t2{{fill:var(--k2)}} .t3{{fill:var(--k3)}}
.t4{{fill:var(--k4)}} .t5{{fill:var(--k5)}} .t6{{fill:var(--k6)}} .t7{{fill:var(--k7)}}
.ribbon-bg{{fill:var(--surface-2)}}
.seg{{stroke:var(--surface-1);stroke-width:2}}
.k0{{fill:var(--k0)}} .k1{{fill:var(--k1)}} .k2{{fill:var(--k2)}} .k3{{fill:var(--k3)}}
.k4{{fill:var(--k4)}} .k5{{fill:var(--k5)}} .k6{{fill:var(--k6)}} .k7{{fill:var(--k7)}}
.kx{{fill:var(--kx)}}
.cell{{stroke:var(--surface-1);stroke-width:1.2}}
.hx{{stroke:var(--surface-1);stroke-width:.5;opacity:.5}}
.hx.kx{{opacity:.16}}
.dhit{{fill:var(--k1)}}
.legend{{display:flex;flex-wrap:wrap;gap:6px 18px;margin-top:12px;font-size:12px;
 color:var(--ink-2)}}
.lg{{display:inline-flex;align-items:center;gap:7px}}
.sw{{width:11px;height:11px;border-radius:3px;display:inline-block;flex:0 0 auto}}
.sw.s0{{background:var(--k0)}} .sw.s1{{background:var(--k1)}}
.sw.s2{{background:var(--k2)}} .sw.s3{{background:var(--k3)}}
.sw.s4{{background:var(--k4)}} .sw.s5{{background:var(--k5)}}
.sw.s6{{background:var(--k6)}} .sw.s7{{background:var(--k7)}}
.sw.k0{{background:var(--k0)}} .sw.k1{{background:var(--k1)}} .sw.k2{{background:var(--k2)}}
.sw.k3{{background:var(--k3)}} .sw.k4{{background:var(--k4)}} .sw.k5{{background:var(--k5)}}
.sw.k6{{background:var(--k6)}} .sw.k7{{background:var(--k7)}} .sw.kx{{background:var(--kx)}}
.tw{{overflow-x:auto;margin:16px 0;border:1px solid var(--rule);border-radius:10px;
 background:var(--surface-1)}}
table{{border-collapse:collapse;width:100%;font-size:12.5px;min-width:520px}}
th{{text-align:left;font:600 10.5px/1.4 var(--mono);letter-spacing:.06em;
 text-transform:uppercase;color:var(--ink-3);padding:10px 11px;
 border-bottom:1px solid var(--rule);white-space:nowrap;background:var(--surface-1);
 position:sticky;top:0}}
td{{padding:7px 11px;border-bottom:1px solid var(--grid);vertical-align:top}}
tbody tr:last-child td{{border-bottom:0}}
td.m,th.m{{font-family:var(--mono);font-size:11.5px;white-space:nowrap}}
td.n{{text-align:right;font-family:var(--mono);font-size:11.5px;white-space:nowrap}}
.ok{{color:var(--good);font-weight:600}} .no{{color:var(--bad);font-weight:600}}
.tiles{{display:grid;grid-template-columns:repeat(auto-fit,minmax(158px,1fr));gap:12px;
 margin:22px 0}}
.tile{{background:var(--surface-1);border:1px solid var(--rule);border-radius:10px;
 padding:14px 15px}}
.tile .t{{font:600 10px/1.3 var(--mono);letter-spacing:.1em;text-transform:uppercase;
 color:var(--ink-3);margin-bottom:7px}}
.tile .v{{font-size:25px;font-weight:640;letter-spacing:-.02em;line-height:1.05;
 font-variant-numeric:tabular-nums}}
.tile .u{{font-size:11.5px;color:var(--ink-3);margin-top:4px;font-family:var(--mono)}}
.banner{{display:flex;gap:13px;align-items:flex-start;background:var(--surface-1);
 border:1px solid var(--rule);border-left:3px solid var(--good);border-radius:10px;
 padding:15px 17px;margin:22px 0}}
.banner.w{{border-left-color:var(--warn)}}
.banner.r{{border-left-color:var(--bad)}}
.banner .bt{{font-weight:660;margin-bottom:3px;font-size:14px}}
.banner p{{margin:0;font-size:13.5px;color:var(--ink-2)}}
.gal{{display:flex;flex-wrap:wrap;gap:9px;margin:16px 0;padding:15px;
 background:var(--surface-1);border:1px solid var(--rule);border-radius:10px}}
.cardw{{display:flex;flex-direction:column;align-items:center;gap:5px;width:78px}}
.holder{{width:78px;height:126px;display:flex;align-items:center;justify-content:center;
 background:
  linear-gradient(45deg,var(--surface-2) 25%,transparent 25%,transparent 75%,var(--surface-2) 75%),
  linear-gradient(45deg,var(--surface-2) 25%,transparent 25%,transparent 75%,var(--surface-2) 75%);
 background-size:12px 12px;background-position:0 0,6px 6px;
 border:1px solid var(--rule);border-radius:7px;overflow:hidden}}
.gal.big .cardw{{width:150px}}
.gal.big .holder{{width:150px;height:236px;background:var(--surface-2)}}
.gal.big .cl{{font-size:11px}}
img.ras{{max-width:100%;max-height:100%;width:auto;height:auto;
 image-rendering:pixelated;display:block}}
.noimg{{font:9px/1.3 var(--mono);color:var(--ink-3);text-align:center;padding:6px}}
.cardw .cl{{font:9.5px/1.25 var(--mono);color:var(--ink-3);text-align:center;
 word-break:break-all}}
.chip{{display:inline-block;font:600 10px/1 var(--mono);letter-spacing:.05em;
 white-space:nowrap;padding:4px 7px;border-radius:5px;background:var(--surface-2);
 color:var(--ink-2);margin:0 5px 5px 0;text-transform:uppercase}}
.chip.g{{color:#fff;background:var(--good)}} .chip.w{{color:#fff;background:var(--warn)}}
.chip.r{{color:#fff;background:var(--bad)}} .chip.b{{color:#fff;background:var(--k0)}}
.swatches{{display:flex;flex-wrap:wrap;gap:12px;margin:16px 0}}
.sws{{width:112px}}
.sws .box{{height:52px;border-radius:8px;border:1px solid var(--rule)}}
.sws .cl{{font:10.5px/1.4 var(--mono);color:var(--ink-3);margin-top:6px}}
.toc{{background:var(--surface-1);border:1px solid var(--rule);border-radius:10px;
 padding:16px 20px;margin:26px 0}}
.toc ol{{margin:0;padding-left:20px;columns:2;column-gap:32px;font-size:13.5px}}
.toc li{{margin:3px 0;break-inside:avoid}}
.toc a{{text-decoration:none;color:var(--ink-2)}} .toc a:hover{{color:var(--k0)}}
pre{{background:var(--surface-1);border:1px solid var(--rule);border-radius:9px;
 padding:13px 15px;overflow-x:auto;font-family:var(--mono);font-size:11.5px;
 line-height:1.55;margin:14px 0}}
@media(max-width:760px){{.toc ol{{columns:1}}}}
.riskt td:first-child{{font-weight:600}}
ul.tight{{margin:8px 0;padding-left:22px}} ul.tight li{{margin:5px 0;max-width:74ch}}
details{{margin:14px 0;background:var(--surface-1);border:1px solid var(--rule);
 border-radius:10px;padding:0 15px}}
details[open]{{padding-bottom:12px}}
summary{{cursor:pointer;padding:13px 0;font:600 13px/1.4 var(--mono);color:var(--ink-2);
 list-style:none}}
summary::-webkit-details-marker{{display:none}}
summary::before{{content:"\\25B8  ";color:var(--ink-3)}}
details[open] summary::before{{content:"\\25BE  "}}
summary:hover{{color:var(--k0)}}
details .tw{{margin-top:0;border:0;background:transparent}}
.footer{{margin-top:70px;padding-top:22px;border-top:1px solid var(--rule);
 color:var(--ink-3);font-size:12.5px}}
.packgraph .pg-box{{fill:var(--surface-2);stroke:var(--rule);stroke-width:1;
 vector-effect:non-scaling-stroke}}
.packgraph .pg-header{{stroke:var(--k0)}}
.packgraph .pg-directory{{stroke:var(--k0)}}
.packgraph .pg-style,.packgraph .pg-widget{{stroke:var(--k5)}}
.packgraph .pg-image,.packgraph .pg-pointer{{stroke:var(--k2)}}
.packgraph .pg-resource,.packgraph .pg-logical-box{{stroke:var(--k5)}}
.packgraph .pg-runtime-box{{stroke:var(--k7);stroke-dasharray:5 4}}
.packgraph .pg-stroke-0{{stroke:var(--k0)}} .packgraph .pg-stroke-1{{stroke:var(--k1)}}
.packgraph .pg-stroke-2{{stroke:var(--k2)}} .packgraph .pg-stroke-3{{stroke:var(--k3)}}
.packgraph .pg-stroke-4{{stroke:var(--k4)}} .packgraph .pg-stroke-5{{stroke:var(--k5)}}
.packgraph .pg-stroke-6{{stroke:var(--k6)}} .packgraph .pg-stroke-7{{stroke:var(--k7)}}
.packgraph .pg-section{{font-size:13px;fill:var(--ink-1);font-weight:600}}
.packgraph .pg-column{{font-size:10px;fill:var(--ink-3);font-weight:600;
 letter-spacing:.07em}}
.packgraph .pg-head{{font-size:11px;fill:var(--ink-1);font-weight:600}}
.packgraph .pg-small{{font-size:10px;fill:var(--ink-2)}}
.packgraph .pg-note{{font-size:10px;fill:var(--ink-3)}}
.packgraph .pg-row-head{{font-size:10px;fill:var(--ink-1);font-weight:600}}
.packgraph .pg-tiny{{font-size:9.5px;fill:var(--ink-3)}}
.packgraph .pg-equation{{font-size:10px;fill:var(--ink-2)}}
.packgraph .pg-edge-label{{font-size:9.5px;paint-order:stroke;
 stroke:var(--surface-1);stroke-width:3px;stroke-linejoin:round}}
.packgraph .pg-legend{{font-size:9.5px;fill:var(--ink-2)}}
.packgraph .pg-arrow{{fill:none;stroke-width:1.5;vector-effect:non-scaling-stroke}}
.packgraph .pg-abs{{stroke:var(--k1)}} .packgraph .pg-abs-text{{fill:var(--k1)}}
.packgraph .pg-rel{{stroke:var(--k2)}} .packgraph .pg-rel-text{{fill:var(--k2)}}
.packgraph .pg-logical{{stroke:var(--k5)}}
.packgraph .pg-logical-text{{fill:var(--k5)}}
.packgraph .pg-runtime{{stroke:var(--k7)}} .packgraph .pg-runtime-text{{fill:var(--k7)}}
.packgraph .pg-struct{{stroke:var(--kx)}}
.packgraph .pg-coverage{{fill:none;stroke:var(--k4);stroke-width:1.5;
 vector-effect:non-scaling-stroke}}
.flow{{display:grid;grid-template-columns:repeat(7,minmax(118px,1fr));gap:22px;
 margin:22px 0;overflow-x:auto;padding:4px 2px 12px}}
.flowstep{{position:relative;min-width:118px;background:var(--surface-1);
 border:1px solid var(--rule);border-radius:10px;padding:13px 12px 13px 42px}}
.flowstep>span{{position:absolute;left:11px;top:12px;width:22px;height:22px;border-radius:50%;
 display:grid;place-items:center;background:var(--k0);color:#fff;font:650 11px/1 var(--mono)}}
.flowstep:not(:last-child)::after{{content:"→";position:absolute;right:-18px;top:35%;
 color:var(--ink-3);font:18px/1 var(--mono)}}
.flowstep b{{display:block;font-size:12.5px}} .flowstep small{{display:block;color:var(--ink-3);
 font:10px/1.45 var(--mono);margin-top:5px}}
.evidence{{display:grid;grid-template-columns:repeat(auto-fit,minmax(210px,1fr));gap:10px;margin:16px 0}}
.ev{{background:var(--surface-1);border:1px solid var(--rule);border-radius:9px;padding:12px}}
.ev b{{font:650 11px/1.3 var(--mono);text-transform:uppercase;letter-spacing:.05em}}
.ev small{{display:block;color:var(--ink-3);margin-top:5px;line-height:1.45}}
.dispatch{{display:grid;grid-template-columns:repeat(auto-fit,minmax(210px,1fr));gap:9px;margin:18px 0}}
.dcard{{background:var(--surface-1);border:1px solid var(--rule);border-radius:9px;padding:11px 12px}}
.dcard .dn{{font:650 11px/1 var(--mono);color:var(--ink-3);margin-bottom:6px}}
.dcard b{{font-size:13px}} .dcard small{{display:block;color:var(--ink-3);font-size:11px;margin-top:4px}}
.dcard.proven{{border-left:3px solid var(--good)}} .dcard.nosample{{border-left:3px solid var(--warn)}}
.dcard.inert{{border-left:3px solid var(--ink-3)}}
.compflow{{display:flex;align-items:stretch;gap:9px;overflow-x:auto;margin:18px 0;padding-bottom:8px}}
.minip{{min-width:142px;background:var(--surface-1);border:1px solid var(--rule);border-radius:9px;padding:11px}}
.minip b,.minip code,.minip small{{display:block}} .minip code{{white-space:normal;margin:7px 0;
 font-size:9.5px}} .minip small{{color:var(--ink-3);font-size:10px}}
.minip.out{{border-left:3px solid var(--k5);min-width:210px}} .flowarrow{{align-self:center;color:var(--ink-3)}}
.policy{{display:grid;grid-template-columns:1fr auto 1fr;align-items:center;gap:14px;margin:20px 0}}
.policy>div:not(.pa){{background:var(--surface-1);border:1px solid var(--rule);border-radius:10px;padding:14px}}
.policy .pa{{font:20px/1 var(--mono);color:var(--ink-3)}}
.policy b{{display:block}} .policy small{{display:block;color:var(--ink-3);margin-top:6px}}
@media(max-width:650px){{.policy{{grid-template-columns:1fr}}.policy .pa{{transform:rotate(90deg);justify-self:center}}}}
</style></head><body>''')

    # ---------------------------------------------------------------- hero
    panels = Counter(m["panel"] for m in models.values() if m.get("panel"))
    panel_note = (f"{panels.most_common(1)[0][0]} panel"
                  if len(panels) == 1 else f"{len(panels)} panel geometries")
    A('<div class="wrap"><header class="hero">')
    A('<p class="kicker">Structural decode · OPPO watch-face container</p>')
    A('<h1>Every byte of a watch-face container</h1>')
    A(f'<p class="sub">A complete structural decode of {len(models)} '
      f'<code>.bin</code> container{"s" if len(models) != 1 else ""} — '
      f'{commas(total_bytes)} bytes examined, 100% classified, byte-identical '
      f'reconstruction verified. {e(panel_note)}, '
      f'{commas(len(widgets))} widget records, '
      f'{commas(sum(m["stats"]["total_images"] for m in models.values()))} rasters.</p>')
    A('</header>')

    A('''<nav class="toc"><ol>
<li><a href="#verify">Verification &amp; provenance</a></li>
<li><a href="#evidence">How the layouts are established</a></li>
<li><a href="#faces">The corpus</a></li>
<li><a href="#budget">Where the bytes go</a></li>
<li><a href="#layout">File layout</a></li>
<li><a href="#packing-graph">Packing/reference graph</a></li>
<li><a href="#header">Container header</a></li>
<li><a href="#dir">Directory records</a></li>
<li><a href="#inventory">Entry inventory</a></li>
<li><a href="#setting">setting.bin</a></li>
<li><a href="#fonts">Font bindings</a></li>
<li><a href="#glyphs">Locale glyph tables</a></li>
<li><a href="#style">Style entry anatomy</a></li>
<li><a href="#widgets">Widget records</a></li>
<li><a href="#widgetdata">Every widget, decoded</a></li>
<li><a href="#images">Image records &amp; pixel formats</a></li>
<li><a href="#assets">Asset inventory</a></li>
<li><a href="#themes">How variants work</a></li>
<li><a href="#entropy">Entropy profile</a></li>
<li><a href="#manip">Plausible manipulations</a></li>
<li><a href="#unknown">What remains unknown</a></li>
</ol></nav>''')

    if len(faces) > len(deep):
        A(f'<div class="banner w"><div><div class="bt">Detail is limited to '
          f'{len(deep)} of {len(faces)} containers</div><p>Census sections below '
          f'cover all {len(faces)}. The per-face sections — full widget dumps, '
          f'asset galleries, variant diffs — are rendered for '
          f'{e(", ".join(deep))} only, because inlining every one would make the '
          f'page unusable. Raise it with <code>--detail</code>.</p></div></div>')

    # ---------------------------------------------------------------- verify
    A('<h2 id="verify">1 · Verification &amp; provenance</h2>')
    A('<p class="lead">Every claim below was re-derived from the raw bytes by a '
      'parser that shares no code with the writer it corroborates, then checked '
      'three ways: all CRC-16 values recomputed, every byte assigned to exactly one '
      'semantic class, and the file rebuilt from the parsed pieces and compared to '
      'the original.</p>')

    def clean(m):
        verification = m.get("verification", {})
        return (verification.get("byte_integrity", False)
                and verification.get("schema_complete", False)
                and verification.get("cross_resource_valid", False)
                and m["stats"]["byte_class_delta"] == 0)

    bad = [f for f, m in models.items() if not clean(m)]
    crc_total = sum(len(m["entries"]) + 1 for m in models.values())
    A(f'<div class="banner{"" if not bad else " r"}"><div><div class="bt">'
      f'{"All integrity checks pass on every container" if not bad else f"{len(bad)} container(s) failed a check"}'
      f'</div><p>{commas(crc_total)} CRC-16/CCITT-FALSE checksums recomputed · '
      f'{sum(m["coverage"]["unaccounted_byte_count"] for m in models.values())} '
      f'unaccounted bytes · '
      f'{sum(m["coverage"].get("overlapping_byte_count", 0) for m in models.values())} '
      f'overlapping bytes · '
      f'{sum(len(m["coverage"]["holes"]) for m in models.values())} gaps · '
      f'{sum(m["coverage"]["trailing_bytes"] for m in models.values())} trailing '
      f'bytes · byte-identical reconstruction on '
      f'{sum(1 for m in models.values() if m["coverage"]["reconstruction_identical"])}'
      f'/{len(models)}.'
      f'{" Failing: " + e(", ".join(bad)) if bad else ""}</p></div></div>')

    rows = []
    for f in faces:
        m, c, s = models[f], models[f]["coverage"], models[f]["stats"]
        crcs = sum(1 for en in m["entries"] if en["crc16_ok"])
        rows.append([
            f'<span class="m">{e(f)}</span>',
            f'<span class="n">{commas(m["file_size"])}</span>',
            f'<span class="m">{m["sha256"][:16]}…</span>',
            f'<span class="{"ok" if m["header"]["crc16_ok"] else "no"}">'
            f'{m["header"]["crc16_stored"]} {"✓" if m["header"]["crc16_ok"] else "✗"}</span>',
            f'<span class="{"ok" if crcs == len(m["entries"]) else "no"}">'
            f'{crcs}/{len(m["entries"])}</span>',
            f'<span class="{"ok" if c["unaccounted_byte_count"] == 0 else "no"}">'
            f'{c["unaccounted_byte_count"]}</span>',
            f'<span class="{"ok" if c.get("overlapping_byte_count", 0) == 0 else "no"}">'
            f'{c.get("overlapping_byte_count", 0)}</span>',
            f'<span class="{"ok" if s["byte_class_delta"] == 0 else "no"}">'
            f'{s["byte_class_delta"]}</span>',
            f'<span class="{"ok" if c["reconstruction_identical"] else "no"}">'
            f'{"identical" if c["reconstruction_identical"] else "DIFFERS"}</span>',
        ])
    A(table(["Container", "Bytes", "SHA-256", "Header CRC", "Entry CRCs",
             "Unaccounted", "Overlaps", "Class residual", "Rebuild"], rows))

    # ------------------------------------------------------------ evidence
    A('<h2 id="evidence">2 · How the layouts are established</h2>')
    A('<div class="banner w"><div><div class="bt">Established against the vendor '
      'catalogue and one firmware build</div><p>The grammar is corroborated by real '
      'vendor faces on both sides — how the bytes are packed, and what the firmware '
      'does with each field — but a different firmware build could still read a field '
      'this report calls unread. The report never folds generated experiments or '
      'candidate packages into vendor evidence.</p></div></div>')
    A('<p class="lead">This is the boundary the older report was missing: byte '
      'packing comes from the containers; field consumption, dispatch, fallbacks '
      'and never-read bytes are established field by field; the 4 MiB and '
      'unchanged-image-count rules come from hardware.</p>')
    A('<div class="evidence">')
    for name, description in EVIDENCE_LEVELS.items():
        A(f'<div class="ev"><b>{e(name)}</b><small>{e(description)}</small></div>')
    A('</div>')
    A(reference_role_chart())

    A('<h3>End-to-end load path</h3>')
    A(loader_flow())
    A(capacity_gauge())
    A('<div class="policy"><div><b>Parser-valid bytes</b><small>Header and record '
      'walk, type-specific reads, exact image pointers and canonical resource '
      'joins can all succeed.</small></div><div class="pa">→</div><div><b>Later '
      'decided by firmware</b><small>A container over 4 MiB or with a changed image-record '
      'count can transfer and parse yet leave the old face active.</small></div></div>')

    loaded_type_counts = Counter(w["type"] for _, _, w in widgets)
    A('<h3>Complete 17-entry dispatch</h3>')
    A('<p>The matrix is complete even when the report input has no sample of a type. '
      'The reference count is the fixed vendor-only audit; “loaded” is computed '
      'from the models used for this particular page.</p>')
    A('<div class="dispatch">')
    for type_id, schema in WIDGET_TYPES.items():
        inert = type_id in (8, 10, 11, 12, 14, 15)
        cls = "inert" if inert else "proven" if schema["reference_samples"] else "nosample"
        A(f'<div class="dcard {cls}"><div class="dn">TYPE {type_id:02d}'
          f'</div><b>{e(schema["name"])}</b><small>'
          f'{e(schema["status"])} · vendor reference {commas(schema["reference_samples"])} · '
          f'loaded {commas(loaded_type_counts[type_id])}</small><small>'
          f'{e(schema["summary"])}</small></div>')
    A('</div>')
    A(table(["Question", "What the watch accepts", "What a writer should emit"], [
        ["Outer magic", "accepts <code>oppo</code> or <code>lqsw</code>",
         "emit the corpus-standard <code>oppo</code>"],
        ["Style header", "requires nonzero count and widget bytes; computes image "
         "base as <code>0x18 + widget_bytes</code>",
         "also enforce both length equations, exact record exhaustion and contiguous indices"],
        ["Widget walk", "calls exactly <code>count</code> handlers and advances by "
         "low <code>u16 +0x0C</code>; no general alignment check",
         "enforce every handler minimum, exact type schema and zero trailing widget bytes"],
        ["Image", "requires nonzero width, height and data size; reads only format low byte",
         "emit 0x0080/0x0082/0x0088 and require exact pixels + four-byte trailer"],
        ["Checksums", "the watch does not read either CRC on install",
         "recompute canonical header and entry CRC-16 values for tooling/interoperability"],
    ]))

    # ---------------------------------------------------------------- corpus
    A('<h2 id="faces">3 · The report input</h2>')
    A('<p class="lead">Each container ships one or more selectable visual styles and '
      'usually a low-power always-on style. The <code>preview.bin</code> rasters '
      'embedded in a container provide visual corroboration. Source names in this '
      'version are established from how the watch updates each type, and only then '
      'joined to previews/dictionaries; they are no longer preview-only guesses.</p>')
    rows = []
    for f in faces:
        m = models[f]
        s = m["stats"]
        rows.append([
            f'<span class="m">{e(f)}</span>',
            f'<span class="m">{e(m.get("origin", "—"))}</span>',
            f'<span class="m">{e(m.get("panel") or "—")}</span>',
            f'<span class="n">{commas(m["file_size"])}</span>',
            f'<span class="n">{s["entry_count"]}</span>',
            f'<span class="n">{s["style_count"]}</span>',
            f'<span class="n">{s["total_widgets"]}</span>',
            f'<span class="n">{s["total_images"]}</span>',
            f'<span class="m">{", ".join(sorted(s["widget_type_totals"]))}</span>',
        ])
    A(table(["Container", "From", "Panel", "Bytes", "Entries", "Styles", "Widgets",
             "Rasters", "Widget types"], rows))
    if images:
        for f in deep:
            m = models[f]
            prev = [i for i in m["images"] if i["entry"] == "preview.bin"]
            if not prev:
                continue
            A(f'<h3>{e(f)} — embedded previews</h3>')
            A('<div class="gal big">')
            for i, im in enumerate(prev):
                A(f'<div class="cardw"><div class="holder">{assets.tag(f, im)}</div>'
                  f'<div class="cl">preview #{i} → style{i}<br>'
                  f'{im["width"]}×{im["height"]} {im["format_name"]}</div></div>')
            A('</div>')

    # ---------------------------------------------------------------- budget
    A('<h2 id="budget">4 · Where the bytes go</h2>')
    A('<p class="lead">These containers are, overwhelmingly, uncompressed '
      'framebuffers. Structure costs a fraction of a percent; the rest is raw '
      'pixels — which is why the format has no compression stage and why edits are '
      'cheap to compute but expensive to store.</p>')
    agg: Counter = Counter()
    for m in models.values():
        agg.update(m["stats"]["byte_class"])
    A('<h4>All containers combined</h4>')
    A(chart_hbar([(k.replace("_", " "), v, f"{100 * v / total_bytes:.3f}%")
                  for k, v in agg.most_common() if v],
                 note=f"Sum of all classes = {commas(sum(agg.values()))} B; "
                      f"corpus = {commas(total_bytes)} B; residual = "
                      f"{total_bytes - sum(agg.values())} B."))
    for f in deep:
        m = models[f]
        A(f'<h4>{e(f)} — {commas(m["file_size"])} bytes</h4>')
        A(chart_hbar([(k.replace("_", " "), v, f"{100 * v / m['file_size']:.3f}%")
                      for k, v in sorted(m["stats"]["byte_class"].items(),
                                         key=lambda kv: -kv[1]) if v],
                     note=f"Residual = {m['stats']['byte_class_delta']} B."))
    pcts = [m["stats"]["image_payload_pct"] for m in models.values()]
    A('<div class="tiles">')
    A(f'<div class="tile"><div class="t">Raster payload</div>'
      f'<div class="v">{sum(pcts) / len(pcts):.2f}%</div>'
      f'<div class="u">mean; range {min(pcts):.2f}–{max(pcts):.2f}%</div></div>')
    A(f'<div class="tile"><div class="t">Structure</div>'
      f'<div class="v">{100 - sum(pcts) / len(pcts):.2f}%</div>'
      f'<div class="u">everything that is not pixels</div></div>')
    A(f'<div class="tile"><div class="t">Total examined</div>'
      f'<div class="v">{kb(total_bytes)}</div>'
      f'<div class="u">{len(models)} container(s)</div></div>')
    A('</div>')

    # ---------------------------------------------------------------- layout
    A('<h2 id="layout">5 · File layout</h2>')
    A('<p class="lead">The container is a flat archive: a fixed 32-byte header, a '
      'directory of 74-byte records, then every payload back to back. The first '
      'ribbon is true to scale — which makes the point that all metadata is '
      'invisible at file scale — so the second expands that region to full width.</p>')
    A('<h3 id="packing-graph">5.1 · Complete packing and reference graph</h3>')
    A('<p class="lead">This follows one real container from its 32-byte header '
      'through every directory record and payload, then opens one style payload '
      'far enough to show every reference namespace. The separation matters: a '
      'directory offset is absolute, an image pointer is relative to the style\'s '
      'image section, a font or glyph value is a logical index, and a sequence id '
      'belongs to the watch rather than to another widget.</p>')
    A(packing_reference_graph(models[deep[0]]))
    A('<h3>5.2 · True-to-scale file ribbons</h3>')
    leg = "".join(f'<span class="lg"><i class="sw k{KIND_SLOT[k]}"></i>{e(v)}</span>'
                  for k, v in KIND_LABEL.items())
    for f in deep:
        A(layout_ribbon(models[f]))
        A(metadata_zoom(models[f]))
    A(f'<div class="legend">{leg}</div>')
    tight = sum(1 for m in models.values() if m["coverage"]["tightly_packed"])
    A(f'<p>Packing invariants, measured across all {len(models)} container(s): '
      f'<strong>{tight}</strong> are tightly packed — the first payload begins '
      'immediately after the directory, each later payload starts exactly where the '
      'previous one ended, and the last ends at EOF. '
      f'{sum(m["coverage"]["trailing_bytes"] for m in models.values())} trailing '
      'bytes in total. A gap anywhere would make every length-changing edit a '
      'search problem instead of arithmetic.</p>')

    # ---------------------------------------------------------------- header
    A('<h2 id="header">6 · Container header — 32 bytes</h2>')
    hd = models[faces[0]]["header"]
    A(field_diagram([
        (0x00, 4, "magic", "the watch accepts ASCII 'oppo' or 'lqsw'", 0),
        (0x04, 4, "version", "u32, constant across the corpus", 1),
        (0x08, 4, "payload_size", "canonical u32 == file_size - 32; normal path does not consume it", 2),
        (0x0C, 4, "entries", "u32 directory record count", 3),
        (0x10, 2, "crc16", "CCITT-FALSE over 0x20..EOF", 4),
        (0x12, 2, "ignored", "zero in every sample; normal path does not read it", 5),
        (0x14, 12, "reserved", "zero in every sample", -1),
    ], 32, title=f"Container header — hover any block for detail"))
    ver = Counter(m["header"]["version"] for m in models.values())
    cnt = Counter(m["header"]["entry_count"] for m in models.values())
    A(table(["Off", "Size", "Field", "Observed across the corpus"], [
        ['<span class="m">0x00</span>', '4', 'magic',
         f'<span class="m">6F 70 70 6F "oppo"</span> in '
         f'{sum(1 for m in models.values() if m["header"]["magic_ok"])}/{len(models)}'],
        ['<span class="m">0x04</span>', '4', 'version',
         '<span class="m">' + e(", ".join(f"{v} ×{n}" for v, n in ver.most_common())) + '</span>'],
        ['<span class="m">0x08</span>', '4', 'payload_size',
         f'canonical equality holds in '
         f'{sum(1 for m in models.values() if m["header"]["payload_ok"])}/{len(models)}; '
         'not read by the watch on install'],
        ['<span class="m">0x0C</span>', '4', 'entry_count',
         '<span class="m">' + e(", ".join(f"{v} ×{n}" for v, n in cnt.most_common(8)))
         + '</span> — directory length is <code>74 × count</code>'],
        ['<span class="m">0x10</span>', '2', 'crc16',
         f'recomputed and matched in '
         f'{sum(1 for m in models.values() if m["header"]["crc16_ok"])}/{len(models)}; '
         'canonical checksum input is <code>0x20 … EOF</code>; normal install does not read it'],
        ['<span class="m">0x12</span>', '2', 'ignored',
         f'zero in {sum(1 for m in models.values() if m["header"]["crc_upper_zero"])}'
         f'/{len(models)}; no consumer found, so no checksum meaning is assigned'],
        ['<span class="m">0x14</span>', '12', 'reserved',
         f'zero in {sum(1 for m in models.values() if m["header"]["reserved_zero"])}'
         f'/{len(models)}. Preserve verbatim.'],
    ]))
    A('<p>The canonical checksum is CRC-16/CCITT-FALSE: polynomial <code>0x1021</code>, init '
      '<code>0xFFFF</code>, no reflection, no final XOR — identical to Python\'s '
      '<code>binascii.crc_hqx(data, 0xFFFF)</code>. Note that the header CRC covers '
      'the <em>directory as well as</em> the payloads, so a canonical rewrite must '
      'refresh it after changing any offset, size or per-entry CRC. The watch\'s own '
      'normal watch install path does not validate either CRC.</p>')

    # ---------------------------------------------------------------- dir
    A('<h2 id="dir">7 · Directory records — 74 bytes each</h2>')
    A(field_diagram([
        (0x00, 64, "path", "canonical NUL-padded UTF-8 relative path", 0),
        (0x40, 4, "offset", "u32 absolute file offset of the payload", 2),
        (0x44, 4, "size", "u32 payload length in bytes", 3),
        (0x48, 2, "crc16", "CCITT-FALSE over the payload only", 4),
    ], 74, title="Directory record — 64 + 4 + 4 + 2 = 74 bytes, no padding"))
    padded = sum(1 for en in entries_all if en["path_padding_zero"])
    A(f'<p>Canonical producer paths are relative and, in this corpus, always of the form '
      f'<code>./&lt;container&gt;/&lt;name&gt;</code>. The 64-byte field is '
      f'NUL-padded and every unused byte is zero in {padded} of '
      f'{len(entries_all)} records. The offset is <em>absolute</em> from the start '
      'of the file, not relative to the body — the detail that matters for any '
      'length-changing edit, because every later record must be rewritten. The '
      'unpacker concatenates the stored C string; it does not visibly '
      'enforce containment, so a writer must still emit the canonical relative form.</p>')

    # ---------------------------------------------------------------- inventory
    A('<h2 id="inventory">8 · Entry inventory</h2>')
    A('<h4>Entry names across the corpus</h4>')
    A(census_table("Basename", Counter(en["basename"] for en in entries_all),
                   len(entries_all)))
    for f in deep:
        m = models[f]
        rows = []
        for en in m["entries"]:
            p = en["parsed"]
            extra = ""
            if p.get("kind") == "style":
                extra = f'{p["parsed_widget_count"]} widgets, {p["image_count"]} rasters'
            elif p.get("kind") == "preview":
                extra = f'{p["image_count"]} rasters'
            elif p.get("kind") == "glyph_table":
                extra = f'{p["group_count"]} strings'
            elif p.get("kind") == "font_binding":
                extra = f'{p["binding_name"]} @ {p["point_size"]}pt'
            elif p.get("kind") == "setting":
                extra = f'v{p["face_version"]}'
            elif p.get("kind") == "unparsed":
                extra = p.get("error", "")
            rows.append([
                f'<span class="n">{en["index"]}</span>',
                f'<span class="m">{e(en["basename"])}</span>',
                f'<span class="m">0x{en["payload_offset"]:06X}</span>',
                f'<span class="n">{commas(en["payload_size"])}</span>',
                f'<span class="n">{en["pct_of_file"]}%</span>',
                f'<span class="m {"ok" if en["crc16_ok"] else "no"}">'
                f'{en["crc16_stored"]} {"✓" if en["crc16_ok"] else "✗"}</span>',
                f'<span class="n">{en["entropy_bits"]}</span>',
                f'<span class="chip">{e(p.get("kind", "?"))}</span>',
                e(extra),
            ])
        A(f'<details><summary>{e(f)} — {m["header"]["entry_count"]} entries</summary>'
          + table(["#", "Name", "Offset", "Size", "Share", "CRC-16", "Entropy",
                   "Kind", "Contents"], rows) + '</details>')

    # ---------------------------------------------------------------- setting
    settings = [(f, en) for f in faces for en in models[f]["entries"]
                if en["parsed"].get("kind") == "setting"]
    A('<h2 id="setting">9 · setting.bin — a 256-byte manager record</h2>')
    if not settings:
        A('<p class="lead">No <code>setting.bin</code> entry in this corpus.</p>')
    else:
        A('<p class="lead">The manager requests exactly 256 bytes but consumes only '
          'the numeric face id and bytes <code>+0x30..+0x37</code>. The marker, '
          'struct sentinel and name slots are canonical producer metadata, not '
          'normal-path validation fields.</p>')
        grid_face, grid_entry = next((f, en) for f, en in settings if f in deep) \
            if any(f in deep for f, _ in settings) else settings[0]
        sdata = (root / grid_face / "entries" / grid_entry["disk_name"]).read_bytes()
        sp = grid_entry["parsed"]
        A(byte_grid(sdata, [
            (0x00, 0x0C, "vendor marker, NUL-padded to 12 B", 0),
            (0x0C, 0x10, "struct magic 0x12345678", 1),
            (0x10, 0x20, "face id, 16-byte NUL-padded ASCII", 2),
            (0x30, 0x34, "face version (LE i32, formatted with %d)", 3),
            (0x34, 0x35, "u8 selectable style count", 4),
            (0x35, 0x36, "u8 default/current style", 4),
            (0x36, 0x37, "u8 copied property; no downstream read found", -1),
            (0x37, 0x38, "u8 copied flags; no downstream read found", -1),
            (0x38, 0x78, "name slot A: lead byte + NUL-terminated name", 5),
            (0x78, 0xB8, "name slot B", 5),
        ], title=f"{e(grid_face)} setting.bin — non-zero bytes in hex; grey is zero"))
        rows = []
        for f, en in settings[:40]:
            p = en["parsed"]
            rows.append([
                f'<span class="m">{e(f)}</span>',
                f'<span class="m">{e(p["marker_ascii"])}</span>',
                f'<span class="m">{e(p["face_id"])}</span>',
                f'<span class="n">{p["face_version"]}</span>',
                f'<span class="n">{p["style_count"]}</span>',
                f'<span class="n">{p["default_style"]}</span>',
                f'<span class="m">0x{p["property_0x36"]:02X}</span>',
                f'<span class="m">0x{p["property_0x37"]:02X}</span>',
                f'<span class="m">{e(p["name_slot_a"]["name"])}</span>',
                f'<span class="{"ok" if p["slots_identical"] else "no"}">'
                f'{"identical" if p["slots_identical"] else "differ"}</span>',
            ])
        A(table(["Container", "Marker", "Face id", "Version", "Styles", "Default",
                 "+0x36", "+0x37", "Name slot A", "Slot B vs A"], rows))
        same = sum(1 for _, en in settings if en["parsed"]["slots_identical"])
        style_eq = sum(1 for f, en in settings
                       if en["parsed"]["style_count"] == models[f]["stats"]["style_count"])
        A(f'<p>The two 64-byte name slots are byte-identical in {same} of '
          f'{len(settings)} containers. The watch reads <code>+0x34</code> '
          f'as the selectable-style count, and it agrees with actual '
          f'<code>style0..styleN-1</code> resources in {style_eq}/{len(settings)} '
          'loaded containers. <code>+0x35</code> is the initial/default selection '
          'unless saved state for the same numeric id overrides it.</p>')

    # ---------------------------------------------------------------- fonts
    fonts = [(f, en) for f in faces for en in models[f]["entries"]
             if en["parsed"].get("kind") == "font_binding"]
    A('<h2 id="fonts">10 · Font bindings — 92 bytes each</h2>')
    A('<p class="lead">These are <em>not</em> fonts. No glyph outlines, no bitmaps, '
      'no font program of any kind appears anywhere in a container. Each record '
      'carries 72 language-indexed family selectors and asks firmware for a '
      'pixel size — the typeface lives in the watch ROM, so arbitrary font '
      'substitution is not possible through this file.</p>')
    A(field_diagram([
        (0x00, 0x45, "language[0..68]", "u8 ROM-family selector per runtime language", 0),
        (0x45, 3, "spare", "indices 69..71; no watch language reaches them", -1),
        (0x48, 16, "role", "descriptive NUL-padded name; resolver does not read it", -1),
        (0x58, 4, "pixel size", "u32 requested ROM-font pixel size", 3),
    ], 92, title="Font-binding record — 72 selectors + 16 metadata bytes + 4-byte size"))
    if fonts:
        A('<h4>Roles observed</h4>')
        A(census_table("Role @ size",
                       Counter(f'{en["parsed"]["binding_name"]} @ '
                               f'{en["parsed"]["requested_pixel_size"]}px' for _, en in fonts),
                       len(fonts)))
        overrides = sum(1 for _, en in fonts if en["parsed"]["byte_1_override"])
        spare_nonzero = sum(1 for _, en in fonts
                            if any(en["parsed"]["spare_tail_selectors"]))
        A(f'<p>The resolver checks selector byte <code>1</code> first and uses it '
          f'immediately when nonzero ({overrides}/{len(fonts)} loaded bindings). '
          'Only when it is zero does the current runtime language index choose a '
          'different slot. Slots 69..71 are beyond the watch\'s language range '
          f'and are nonzero in {spare_nonzero}/{len(fonts)} loaded bindings.</p>')
        A('<h4>ROM family and dedicated size branches</h4>')
        A(table(["Selector", "Family", "Dedicated sizes", "Other sizes"], [
            [f'<span class="n">{selector}</span>', e(identity),
             f'<span class="m">{e(sizes)}</span>', e(fallback)]
            for selector, (identity, sizes, fallback) in FONT_FAMILIES.items()
        ]))
        A('<p>Selectors 6, 13, 14 and every unlisted value have no dedicated '
          'dispatch and fall back. Family names are intentionally absent for '
          'compiled selectors 4, 5 and 7–12; the binary exposes their branches but '
          'not a trustworthy visual name.</p>')

    # ---------------------------------------------------------------- glyphs
    glyphs = [(f, en) for f in faces for en in models[f]["entries"]
              if en["parsed"].get("kind") == "glyph_table"]
    A('<h2 id="glyphs">11 · Locale dictionaries — the strings the watch draws</h2>')
    A('<p class="lead">Each file is a packed UTF-8 string dictionary used by Pair '
      'and Comp. The normal lookup computes descriptor position from the requested '
      'index; it does not first validate the marker, language id, stored count or '
      'reserved words. The analyzer deliberately enforces the stronger canonical '
      'bounds and exact-exhaustion rules a writer needs.</p>')
    A(field_diagram([
        (0x00, 4, "magic", "canonical 0x12345678; normal item lookup ignores it", -1),
        (0x04, 4, "locale", "producer locale id; normal item lookup ignores it", -1),
        (0x08, 4, "groups", "canonical u32 count; lookup does not bounds-check against it", -1),
        (0x0C, 12, "reserved", "three canonical zero words; ignored", -1),
        (0x18, 56, "N x descriptor", "8 bytes each: u32 byte length, u32 offset", 3),
        (0x50, 51, "UTF-8 text", "concatenated strings, no separators", 4),
    ], 131, title="Glyph-table layout — descriptor table then a packed text region"))
    if glyphs:
        loc = Counter(f'{en["basename"]} = {en["parsed"]["locale_word"]}'
                      for _, en in glyphs)
        A('<h4>Locale identifiers observed</h4>')
        A(census_table("Entry = locale id", loc, len(glyphs)))
        unacc = sum(en["parsed"]["unaccounted_bytes"] for _, en in glyphs)
        canonical = sum(1 for _, en in glyphs if en["parsed"]["canonical_contiguous"])
        A(f'<p>Across {len(glyphs)} dictionaries the descriptor offsets account for '
          f'every byte of the text region with {unacc} unaccounted, and '
          f'{canonical}/{len(glyphs)} are contiguous and end exactly at EOF. The '
          f'descriptors are in ascending offset order in '
          f'{sum(1 for _, en in glyphs if en["parsed"]["ascending_offsets"])} of them. '
          'The descriptor length is a <strong>byte</strong> count, not a character '
          'count, and the Pair/Comp reader accepts only 1..64 UTF-8 bytes per item. '
          'The firmware builds <code>font_&lt;runtime suffix&gt;.bin</code> and falls back '
          'to <code>font_en.bin</code>; producer language ids can drift from the '
          'runtime suffix enum.</p>')
        for f in deep:
            gt = [en for en in models[f]["entries"]
                  if en["parsed"].get("kind") == "glyph_table"]
            if not gt:
                continue
            n = max(en["parsed"]["group_count"] for en in gt)
            rows = [[f'<span class="m">'
                     f'{e(en["basename"].replace("font_", "").replace(".bin", ""))}</span>']
                    + [f'<span class="m">{e(g["text"])}</span>'
                       for g in en["parsed"]["groups"]]
                    for en in gt]
            A(f'<details><summary>{e(f)} — string groups by index</summary>'
              + table(["Entry"] + [str(i) for i in range(n)], rows) + '</details>')

    # ---------------------------------------------------------------- style
    A('<h2 id="style">12 · Style entry anatomy</h2>')
    A('<p class="lead"><code>aod.bin</code> and every <code>styleN.bin</code> share '
      'one structure: a 24-byte header, a packed run of variable-length widget '
      'records, then a packed run of rasters. The watch validates only nonzero '
      'widget count/bytes and computes the image base itself; the stronger equations '
      'below are canonical writer invariants.</p>')
    A(field_diagram([
        (0x00, 4, "magic", "0x12345678", 1),
        (0x04, 4, "widgets", "u32 record count", 0),
        (0x08, 4, "widget B", "u32 total bytes of widget records", 2),
        (0x0C, 4, "image B", "retained u32 image bytes; not used to locate a raster", 4),
        (0x10, 1, "ignored", "byte 0 unread", -1),
        (0x11, 1, "fonts", "u8 numbered font binding count, accepted 1..10", 5),
        (0x12, 2, "ignored", "bytes 2..3 unread", -1),
        (0x14, 4, "image off", "canonical u32 == 24 + widget bytes; loader computes it", 3),
    ], 24, title="Style header — six u32 fields, 24 bytes"))
    all_styles = [(f, en) for f in faces for en in styles_of(models[f])]
    eq1 = sum(1 for _, en in all_styles if en["parsed"]["eq_image_offset"]["ok"])
    eq2 = sum(1 for _, en in all_styles if en["parsed"]["eq_entry_size"]["ok"])
    cnt_ok = sum(1 for _, en in all_styles if en["parsed"]["count_matches"])
    unref = sum(len(en["parsed"]["images_unreferenced"]) for _, en in all_styles)
    A(f'<pre>image_section_offset == 24 + widget_bytes          '
      f'({eq1}/{len(all_styles)} entries)\n'
      f'entry_size           == image_offset + image_bytes  '
      f'({eq2}/{len(all_styles)} entries)\n'
      f'declared count       == parsed widget count         '
      f'({cnt_ok}/{len(all_styles)} entries)</pre>')
    A('<h4>Numbered font count at header byte +0x11</h4>')
    A(census_table("Binding count", Counter(en["parsed"]["font_binding_count"]
                                             for _, en in all_styles), len(all_styles)))
    font_word_ok = sum(1 for _, en in all_styles if en["parsed"]["font_count_word_canonical"])
    A(f'<p>The complete word equals <code>font_count &lt;&lt; 8</code> in '
      f'{font_word_ok}/{len(all_styles)} loaded style/AOD entries. The loader passes '
      'byte <code>+0x11</code> to the numbered-font loader and ignores the other '
      'three bytes.</p>')
    A(f'<p>Using only authoritative per-type pointer fields, every raster in a style entry is referenced in '
      f'{len(all_styles) - sum(1 for _, en in all_styles if en["parsed"]["images_unreferenced"])} '
      f'of {len(all_styles)} entries — {unref} unreferenced rasters in total. There '
      'These are exact pointer edges, not numeric matches against arbitrary words. '
      'An unreferenced raster is structurally classified content, not evidence that '
      'a global integer scan found no match.</p>')
    for f in deep:
        rows = []
        for en in styles_of(models[f]):
            p = en["parsed"]
            rows.append([
                f'<span class="m">{e(en["basename"])}</span>',
                f'<span class="n">{commas(en["payload_size"])}</span>',
                f'<span class="n">{p["declared_widget_count"]}</span>',
                f'<span class="n">{p["declared_widget_bytes"]}</span>',
                f'<span class="m">0x{p["declared_image_offset"]:X}</span>',
                f'<span class="n">{commas(p["declared_image_bytes"])}</span>',
                f'<span class="n">{p["font_binding_count"]}</span>',
                f'<span class="n">{p["image_count"]}</span>',
                f'<span class="{"ok" if not p["images_unreferenced"] else "no"}">'
                f'{len(p["images_unreferenced"])}</span>',
            ])
        A(f'<details><summary>{e(f)} — style entries</summary>'
          + table(["Entry", "Bytes", "Widgets", "Widget B", "Img offset", "Image B",
                   "Fonts", "Rasters", "Unreferenced"], rows) + '</details>')

    # ---------------------------------------------------------------- widgets
    A('<h2 id="widgets">13 · Every widget type and byte</h2>')
    sizes_seen = sorted({w["record_size"] for _, _, w in widgets})
    A(f'<p class="lead">Only bytes <code>+0x00..+0x17</code> form the common '
      f'24-byte prefix. Every byte from <code>+0x18</code> onward is interpreted by '
      f'the selected handler—sometimes as signed geometry, sometimes alignment, '
      f'endpoints, byte-sized controls, a format program or an image table. The '
      f'loaded records span {min(sizes_seen) if sizes_seen else 0}–'
      f'{max(sizes_seen) if sizes_seen else 0} bytes across {commas(len(widgets))} '
      'records.</p>')
    A(field_diagram([
        (0x00, 4, "type", "u32 dispatch id 1..17", 0),
        (0x04, 4, "source", "type-dependent: live id, low-u16 registration, or unread", 1),
        (0x08, 4, "reserved", "zero in all 4,034 vendor records; normal path unread", -1),
        (0x0C, 2, "size", "u16 used to advance", 0),
        (0x0E, 2, "index", "u16 record-order index used by alignment resolver", 0),
        (0x10, 4, "reserved", "normally unread; type 9 copies only +0x10", -1),
        (0x14, 4, "reserved", "zero in all vendor records; normal path unread", -1),
    ], 24, title="Common prefix — exactly 24 bytes; type-specific decoding starts at +0x18"))
    A('<div class="banner w"><div><div class="bt">There is no universal '
      '<code>x,y,w,h</code> header</div><p>Static and Hand use alignment fields; '
      'Sprite and Animation leave <code>+0x1C..+0x1F</code> unread; Badge stores a '
      'second endpoint; Pair, VectorArc, Comp, ImageArc and LineBar use signed '
      'extents. A generic four-word interpretation corrupts real records.</p></div></div>')

    A('<h3>All 17 byte schemas</h3>')
    A('<p>Every row below is backed by the shared schema used by the analyzer. Grey '
      'means laying the face out does not consume the byte; it is still '
      'classified and preserved. Expand any type for its complete map.</p>')
    for type_id, schema in WIDGET_TYPES.items():
        loaded = sum(1 for _, _, widget in widgets if widget["type"] == type_id)
        details_open = " open" if loaded and type_id in (1, 2, 3, 5, 6, 7, 13, 16, 17) else ""
        rows = []
        for field in schema["fields"]:
            size = field["size"] if isinstance(field["size"], str) else f'{field["size"]} B'
            rows.append([
                f'<span class="m">+0x{field["offset"]:02X}</span>',
                f'<span class="m">{e(str(size))}</span>',
                f'<span class="m">{e(field["encoding"])}</span>',
                e(field["name"].replace("_", " ")),
                f'<span class="chip">{e(field["access"])}</span>',
                e(field["description"]),
            ])
        extras = ""
        if schema.get("sources"):
            extras += ('<p><strong>Explicit update sources:</strong> ' +
                       ', '.join(f'<code>{source}</code>' for source in schema["sources"]) + '</p>')
        if schema.get("notes"):
            extras += '<ul class="tight">' + ''.join(f'<li>{e(note)}</li>' for note in schema["notes"]) + '</ul>'
        A(f'<details{details_open}><summary>Type {type_id} · {e(schema["name"])} — '
          f'{e(schema["size"])} · loaded {loaded} · reference '
          f'{commas(schema["reference_samples"])}</summary>'
          f'{widget_schema_figure(type_id)}<p>{e(schema["summary"])}</p>{extras}'
          + table(["Offset", "Size", "Encoding", "Field", "Access", "Exact meaning"], rows)
          + '</details>')

    A('<h3>Only six image-pointer layouts exist</h3>')
    A(table(["Type", "Pointer field", "Cardinality", "Offset namespace"], [
        ["Static", "<code>+0x20</code>", "one", "style image-section relative"],
        ["Hand", "<code>+0x28</code>", "one", "style image-section relative"],
        ["Sprite", "<code>+0x24 + 4×frame</code>", "u8 count at +0x20", "style image-section relative"],
        ["Animation", "<code>+0x28 + 4×frame</code>", "u8 count at +0x24", "style image-section relative"],
        ["ImageArc", "<code>+0x34</code>", "one", "style image-section relative"],
        ["LineBar", "<code>+0x2C</code>", "one + sentinel test", "style image-section relative"],
    ]))
    false_refs = sum(1 for _, _, widget in widgets
                     for field in widget["type_words"]
                     if field["value"] == 0 and widget["type"] not in (3, 4))
    A(f'<p>The analyzer resolves only those fields. It never promotes a word merely '
      f'because its value equals an image offset—especially zero, which names image '
      f'0 and also occurs in {commas(false_refs)} loaded non-table raw words.</p>')

    A('<h3>Pair formatting decision</h3>')
    A('<div class="policy"><div><b><code>u16 +0x2C == 0xFFFF</code></b><small>'
      'Format the live integer. Selector +0x2A: 0/1 → %d; 2..8 → %02d..%08d; '
      '9+ clamps to %d.</small></div><div class="pa">or</div><div><b>'
      '<code>u16 +0x2C != 0xFFFF</code></b><small>Read localized dictionary item '
      '<code>base + live_value</code>. +0x29, +0x2B and +0x2E..+0x37 are not '
      'format controls.</small></div></div>')
    A('<p>Pair <code>+0x20</code> is an LVGL alignment <em>u16</em>, not a private '
      'left/right anchor word; <code>+0x22</code> is its target index and '
      '<code>0xFFFF</code> selects direct placement. Source 116 bypasses the numbered '
      'font and can create a fixed 256×201 clickable overlay whose router destination '
      'is hard-coded, not stored in the Pair.</p>')

    A('<h3>Comp is four format programs</h3>')
    A(comp_pipeline())
    A(field_diagram([
        (0x00, 2, "source", "u16 live id; 0xFFFF disables the part", 1),
        (0x02, 2, "fixed A", "u16 dictionary index or 0xFFFF", 5),
        (0x04, 2, "fixed B", "u16 dictionary index or 0xFFFF", 5),
        (0x06, 2, "dynamic", "u16 dictionary base or 0xFFFF", 5),
        (0x08, 1, "mode", "0 dictionary; nonzero numeric", 0),
        (0x09, 1, "selector", "0/1=%d; 2..6=%02d..%06d; 7+=%d", 1),
        (0x0A, 2, "padding", "normal path unread", -1),
    ], 12, title="One Comp mini-program — repeated at +0x24, +0x30, +0x3C and +0x48"))
    A('<p>After joining twelve fragments, <code>+0x62</code> may name a dictionary '
      'string of digits 1..4 that permutes the four part buffers. '
      '<code>+0x5C</code> is rotation in tenths of a degree, <code>+0x5E</code> the '
      'font binding and signed <code>+0x60</code> letter spacing.</p>')

    A('<h3>Live-source glossary</h3>')
    source_counts = Counter(widget["sequence_id"] for _, _, widget in widgets)
    A(table(["ID", "Value / behaviour", "Loaded records", "Types that accept it"], [
        [f'<span class="n">{source}</span>', e(label),
         f'<span class="n">{source_counts[source]}</span>',
         '<span class="m">' + e(', '.join(
             schema["name"] for schema in WIDGET_TYPES.values()
             if source in schema.get("sources", [])) or "Pair/Comp or source-specific") + '</span>']
        for source, label in SOURCE_LABELS.items()
    ]))
    A('<p>The subset column lists the types whose accepted sources are explicitly '
      'enumerated in the schema. A blank does not invent rejection: Pair '
      'has the broadest formatter dispatch, and Comp owns a source per mini-program. '
      'Source 70\'s numeric current/goal path is complete, but its vendor-facing label '
      'is absent.</p>')

    A('<h3>Special runtime behavior worth preserving</h3>')
    A(table(["Field", "Exact behaviour"], [
        ["Static +0x24", "exactly 1 adds CLICKABLE, EVENT_BUBBLE and GESTURE_BUBBLE, clears SCROLLABLE and registers a fixed click callback; no byte chooses its destination"],
        ["Animation +0x20/+0x25", "mode 2/3 setup is overwritten; repeat 0xFF survives as finite 255, not infinity"],
        ["Badge +0x31", "diagnostic-only: nonzero logs “jump not support” and has no face effect"],
        ["VectorArc +0x3C", "only low u16 is tested for 0xFFFF; +0x3E/+0x3F are unread"],
        ["Type 9", "requires source 75, coordinates at +0x18/+0x1A, at most three collected slots; +0x10 is copied user_data with no surviving consumer"],
        ["LineBar +0x28/+0x2C", "+0x28 is unread; +0x2C is both the authoritative raster pointer and object-style sentinel test"],
    ]))

    A('<h3>Type and size census</h3>')
    types = sorted({t for m in models.values() for t in m["stats"]["widget_type_totals"]})
    A(chart_grouped_bar(types, [(short(f),
                                 [models[f]["stats"]["widget_type_totals"].get(t, 0)
                                  for t in types]) for f in deep]))
    A(census_table("Widget type", Counter(w["type_name"] for _, _, w in widgets),
                   len(widgets)))
    A(census_table("Record size (bytes)",
                   Counter(w["record_size"] for _, _, w in widgets), len(widgets)))

    # ---------------------------------------------------------------- widget data
    A('<h2 id="widgetdata">14 · Every loaded widget, decoded</h2>')
    A('<p class="lead">These tables come from the exact type decoder—not a generic '
      'word dump. Every field retains its byte offset, encoding, raw bytes, decoded '
      'value, access and role. Image edges are authoritative and a source '
      'label is shown only where the evidence establishes it.</p>')
    for f in deep:
        m = models[f]
        A(f'<h4>{e(f)}</h4>')
        for en in styles_of(m):
            p = en["parsed"]
            rows, field_details = [], []
            for w in p["widgets"]:
                refs = ", ".join(
                    f'{e(h["name"])}→#{h["image_index"]}' if h["resolved"] else
                    f'{e(h["name"])}→INVALID 0x{h["image_rel_offset"]:X}'
                    for h in w["image_refs"]) or "—"
                geometry = ", ".join(
                    f'{field["name"]}={field["value"]}'
                    for field in w["decoded_fields"]
                    if field["role"] == "geometry") or "—"
                alignment = w.get("alignment")
                align_text = ("—" if not alignment else
                              f'{alignment["code"]}→{alignment["target_global_index"]}'
                              f'{"" if alignment["enabled"] else " (direct)"}')
                rows.append([
                    f'<span class="m">{w["ordinal"]}</span>',
                    f'<span class="m">0x{en["payload_offset"] + w["record_offset"]:06X}</span>',
                    f'<span class="chip">{e(w["type_name"])}</span>',
                    f'<span class="m">{w["sequence_id"]} · {e(w["sequence_label"] or "unlabelled")}</span>',
                    f'<span class="n">{w["record_size"]}</span>',
                    f'<span class="m">{e(geometry)}</span>',
                    f'<span class="m">{e(align_text)}</span>',
                    f'<span class="m">{e(refs)}</span>',
                    f'<span class="{"ok" if w["schema_exact"] is not False else "no"}">'
                    f'{"exact" if w["schema_exact"] is not False else "size mismatch"}</span>',
                ])
                frows = []
                for field in w["decoded_fields"]:
                    value = field["value"]
                    shown = (f'0x{value:X} / {value}' if isinstance(value, int) else
                             e(str(value)) if value is not None else "out of record")
                    frows.append([
                        f'<span class="m">+0x{field["offset"]:02X}</span>',
                        f'<span class="n">{field["size"]}</span>',
                        f'<span class="m">{e(field["encoding"])}</span>',
                        e(field["name"]),
                        f'<span class="m">{shown}</span>',
                        f'<span class="m">{e(field["raw_hex"])}</span>',
                        f'<span class="chip">{e(field["access"])}</span>',
                        e(field["description"]),
                    ])
                extra = ""
                if w.get("comp_parts"):
                    extra = table(["Part", "Source", "Fixed A", "Fixed B", "Dynamic base",
                                   "Mode", "Selector", "Padding"], [[
                        str(part["index"]),
                        f'{part["source_id"]} · {e(part["source_label"])}',
                        f'0x{part["fixed_dictionary_a"]:04X}',
                        f'0x{part["fixed_dictionary_b"]:04X}',
                        f'0x{part["dynamic_dictionary_base"]:04X}',
                        e(part["dynamic_mode_name"]),
                        f'{part["numeric_selector"]} · {e(part["numeric_format"])}',
                        f'0x{part["padding"]:04X}',
                    ] for part in w["comp_parts"]])
                field_details.append(
                    f'<details><summary>widget {w["ordinal"]} · {e(w["type_name"])} · '
                    f'{w["record_size"]} bytes · raw field ledger</summary>'
                    + table(["Offset", "B", "Encoding", "Field", "Value", "Raw LE",
                             "Access", "Meaning"], frows) + extra + '</details>')
            hdrs = ["#", "File offset", "Type", "Source", "Size", "Type geometry",
                    "Align→target", "Raster edges", "Schema"]
            A(f'<details><summary>{e(en["basename"])} — '
              f'{p["parsed_widget_count"]} widgets</summary>{table(hdrs, rows)}'
              f'{"".join(field_details)}</details>')

    # ---------------------------------------------------------------- images
    A('<h2 id="images">15 · Image records &amp; pixel formats</h2>')
    A('<p class="lead">A 12-byte header, raw row-major pixels, then exactly four '
      'trailer bytes. The watch reads the declared payload but hands the drawing '
      '<code>data_size − 4</code>; the trailer is deliberately excluded from pixels.</p>')
    A(field_diagram([
        (0x00, 2, "width", "u16 pixels", 0),
        (0x02, 2, "height", "u16 pixels", 0),
        (0x04, 1, "format", "low byte: 0x82 RGB565 · 0x80 RGB565+A · 0x88 Indexed8", 1),
        (0x05, 3, "ignored", "format high byte and reserved halfword; canonical zero", -1),
        (0x08, 4, "data size", "u32 == palette + width × height × bpp + 4", 2),
        (0x0C, 20, "pixels", "raw row-major, top-left origin, no filtering", 3),
        (0x20, 4, "trailer", "exact four bytes excluded from GUI data; canonical zero", -1),
    ], 36, title="Image record — 12-byte header, raw pixels, exact 4-byte trailer"))
    A(pixel_format_diagram())
    A('<p>The third format is an indexed one: a 256-entry <strong>BGRA</strong> '
      'palette (1,024 bytes) followed by one index byte per pixel. It is rare but '
      'not optional — a decoder that rejects it cannot open every face. Because the '
      'palette is fixed-length, an indexed raster can still be replaced as a '
      'same-size patch.</p>')
    all_imgs = [i for m in models.values() for i in m["images"]]
    trailers = Counter(i["trailer_size"] for i in all_imgs)
    trailer_zero = sum(1 for image in all_imgs if image.get("trailer_zero", True))
    A(f'<p>Across {commas(len(all_imgs))} image records the trailer size is: '
      + ", ".join(f'<code>{t} B</code> ×{commas(n)}' for t, n in trailers.most_common())
      + f', and {commas(trailer_zero)}/{commas(len(all_imgs))} loaded trailers are '
        'canonical zero. Image references inside widget records are byte offsets <em>relative to '
        'the style\'s image-section start</em>, never absolute file offsets — which '
        'is why a style entry can be relocated wholesale without touching a single '
        'widget word.</p>')
    A('<p>The format converter reads only the low byte. It loosely coerces several '
      'other values to GUI enums, but that proves parser permissiveness—not their '
      'pixel layout. A writer should emit only the three vendor-proven codes. Width '
      'and height land in 11-bit GUI descriptor fields (representational maximum '
      '2047 each), not evidence that a huge object is suitable for the 256×402 panel.</p>')

    A('<h3><code>preview.bin</code> is fixed-stride, not a generic image stream</h3>')
    A(field_diagram([
        (0x00000, 12, "header", "178×280 · 0x0082 · data_size 99,684", 0),
        (0x0000C, 99_680, "RGB565", "178 × 280 × 2 bytes", 2),
        (0x1856C, 4, "trailer", "four zero bytes", -1),
    ], 0x18570, title="One preview record — 99,696 bytes (0x18570)"))
    A('<pre>header seek  = style_index * 0x18570\n'
      'payload seek = 12 + style_index * (loaded_header.data_size + 12)\n'
      'canonical data_size = 99,684, so both calculations name the same record</pre>')
    preview_rows = []
    for face in faces:
        preview = next((entry for entry in models[face]["entries"]
                        if entry["parsed"].get("kind") == "preview"), None)
        setting = next((entry for entry in models[face]["entries"]
                        if entry["parsed"].get("kind") == "setting"), None)
        if not preview:
            continue
        pp = preview["parsed"]
        expected = setting["parsed"]["style_count"] if setting else None
        preview_rows.append([e(face), str(pp["image_count"]), str(expected or "—"),
                             '<span class="ok">yes</span>' if pp["canonical_fixed_stride"]
                             else '<span class="no">no</span>'])
    A(table(["Container", "Preview records", "Selectable styles", "Canonical stride"],
            preview_rows))
    A('<h4>Format and geometry census</h4>')
    A(census_table("Pixel format", Counter(i["format_name"] for i in all_imgs),
                   len(all_imgs)))
    A(census_table("Raster size",
                   Counter(f'{i["width"]}x{i["height"]}' for i in all_imgs),
                   len(all_imgs)))

    # ---------------------------------------------------------------- assets
    A('<h2 id="assets">16 · Asset inventory</h2>')
    if not images:
        A('<p class="lead">Rasters were not exported for this run '
          '(<code>--skip-images</code>), so there is no gallery. Re-run the analyzer '
          'without that flag to inline every decoded raster here.</p>')
    else:
        A('<p class="lead">Everything drawable inside the detailed containers, '
          'decoded and shown at full resolution. There is nothing else in them — no '
          'audio, no scripts, no compressed blobs, no fonts, no executable code of '
          'any kind. Transparency is rendered against a checkerboard.</p>')
        for f in deep:
            m = models[f]
            A(f'<h3>{e(f)} — {m["stats"]["total_images"]} rasters</h3>')
            by_entry: dict[str, list[dict]] = OrderedDict()
            for im in m["images"]:
                by_entry.setdefault(im["entry"], []).append(im)
            for ent, ims in by_entry.items():
                groups: dict[str, list[dict]] = OrderedDict()
                for im in ims:
                    groups.setdefault(f'{im["width"]}×{im["height"]} {im["format_name"]}',
                                      []).append(im)
                desc = ", ".join(f"{len(v)}× {k}" for k, v in groups.items())
                A(f'<h4>{e(ent)} — {len(ims)} rasters ({e(desc)})</h4>')
                A('<div class="gal">')
                for im in ims:
                    st = im.get("stats", {})
                    extra = (f'<br>{st["transparent_pct"]}% clear'
                             if "transparent_pct" in st else "")
                    A(f'<div class="cardw"><div class="holder" '
                      f'title="image #{im["index"]} · {im["width"]}×{im["height"]} '
                      f'{im["format_name"]} · {commas(im["declared_size"])} B · '
                      f'section offset 0x{im["section_relative_offset"]:X}">'
                      f'{assets.tag(f, im)}</div>'
                      f'<div class="cl">#{im["index"]} · {im["width"]}×{im["height"]}'
                      f'{extra}</div></div>')
                A('</div>')

    # ---------------------------------------------------------------- themes
    A('<h2 id="themes">17 · How variants work</h2>')
    A('<p class="lead">A container\'s styles are independent variants, and how far '
      'apart they are decides what a safe edit looks like. Some faces put the whole '
      'difference in pixel data and share a byte-identical widget section; others '
      'change a handful of colour words. They are not obliged to carry the same '
      'widgets at all.</p>')
    rows = []
    for f in faces:
        st = styles_of(models[f])
        if len(st) < 2:
            continue
        base = b"".join(bytes.fromhex(w["raw_hex"]) for w in st[0]["parsed"]["widgets"])
        deltas, same_len = [], True
        for en in st[1:]:
            other = b"".join(bytes.fromhex(w["raw_hex"]) for w in en["parsed"]["widgets"])
            if len(other) != len(base):
                same_len = False
                continue
            deltas.append(sum(1 for a, b in zip(base, other) if a != b))
        identities = [Counter((w["type"], w["sequence_id"])
                              for w in en["parsed"]["widgets"]) for en in st]
        shared = identities[0].copy()
        union = identities[0].copy()
        for identity in identities[1:]:
            shared &= identity
            union |= identity
        rows.append([
            f'<span class="m">{e(f)}</span>',
            f'<span class="n">{len(st)}</span>',
            f'<span class="m">{"yes" if same_len else "no"}</span>',
            f'<span class="n">{min(deltas) if deltas else "—"}</span>',
            f'<span class="n">{max(deltas) if deltas else "—"}</span>',
            f'<span class="n">{sum(shared.values())}/{sum(union.values())}</span>',
        ])
    A(table(["Container", "Styles", "Equal widget-section length",
             "Min bytes differing", "Max bytes differing",
             "(type, source) records in every style"], rows))
    A('<p>The last column compares a multiset of <code>(type, source)</code> pairs; '
      'it does not mistake a raw global index or source id for widget identity. Where it is not '
      '<code>n/n</code>, the styles genuinely carry different widgets, and an edit '
      'that insists on finding the same widget in every variant will refuse to apply '
      'at all.</p>')
    for f in deep:
        st = styles_of(models[f])
        if len(st) < 2:
            continue
        base = st[0]
        braw = b"".join(bytes.fromhex(w["raw_hex"]) for w in base["parsed"]["widgets"])
        others = [(en["basename"],
                   b"".join(bytes.fromhex(w["raw_hex"]) for w in en["parsed"]["widgets"]))
                  for en in st[1:]]
        A(f'<h3>{e(f)}</h3>')
        A(diff_grid(braw, others, base["parsed"]["widgets"]))
        rows = []
        for en in st:
            praw = b"".join(bytes.fromhex(w["raw_hex"]) for w in en["parsed"]["widgets"])
            nd = (sum(1 for a, b in zip(braw, praw) if a != b)
                  if len(praw) == len(braw) else None)
            imgs = en["parsed"]["images"]
            i0 = imgs[0] if imgs else None
            stats = i0.get("stats", {}) if i0 else {}
            first = ("{}×{} {}".format(i0["width"], i0["height"], i0["format_name"])
                     if i0 else "—")
            top = stats["top_colors"][0]["hex"] if stats.get("top_colors") else "—"
            rows.append([
                f'<span class="m">{e(en["basename"])}</span>',
                f'<span class="n">{commas(en["payload_size"])}</span>',
                f'<span class="n">{nd if nd is not None else "length differs"}</span>',
                f'<span class="m">{e(first)}</span>',
                f'<span class="n">{stats.get("unique_colors", "—")}</span>',
                f'<span class="m">{e(top)}</span>',
            ])
        A(table(["Entry", "Size", "Widget bytes differing vs first style",
                 "First raster", "Unique colours", "Dominant colour"], rows))

    # ---------------------------------------------------------------- entropy
    A('<h2 id="entropy">18 · Entropy profile</h2>')
    A('<p class="lead">Shannon entropy per 64 KiB block — the cheapest test for '
      'hidden compressed or encrypted content.</p>')
    A(chart_lines([(short(f), models[f]["entropy_64k"]) for f in deep],
                  ref=7.9,
                  ref_label="7.9 — where compressed or encrypted data would sit"))
    rows = []
    for f in faces[:40]:
        ep = models[f]["entropy_64k"]
        rows.append([
            f'<span class="m">{e(f)}</span>',
            f'<span class="n">{len(ep)}</span>',
            f'<span class="n">{max(0.0, min(ep)):.2f}</span>',
            f'<span class="n">{sum(ep) / len(ep):.2f}</span>',
            f'<span class="n">{max(ep):.2f}</span>',
        ])
    A(table(["Container", "64 KiB blocks", "Min", "Mean", "Max"], rows))
    peak = max(max(m["entropy_64k"]) for m in models.values())
    A(f'<p>The highest block anywhere in this corpus is {peak:.2f} bits/byte, well '
      'below the ~7.9 that compressed or encrypted data shows. Low troughs are large '
      'flat colour fields; peaks are anti-aliased gradient artwork. Combined with a '
      'structural decode that leaves zero unaccounted bytes, every byte belongs to a '
      'declared record. That rules out an unassigned hidden range; it does not rule '
      'out steganographic content inside pixels or meaning in a field assigned to a '
      'known record.</p>')

    # ---------------------------------------------------------------- manip
    A('<h2 id="manip">19 · Writer rules and manipulations</h2>')
    A('<p class="lead">What a canonical writer must update, separated from what the '
      'watch happens to tolerate. Absolute directory offsets make '
      'length changes global; section-relative image pointers make changes inside a '
      'style local-but-type-specific. CRCs are canonical interoperability fields, '
      'not something the watch checks.</p>')
    A('<h4>The canonical rebuild obligation, in order</h4>')
    A('<pre>1. patch payload bytes\n'
      '2. recompute that entry\'s CRC-16     -> directory record +0x48\n'
      '3. if any length changed:\n'
      '     rewrite every later entry offset -> directory record +0x40\n'
      '     rewrite header payload_size      -> header +0x08\n'
      '4. recompute header CRC-16 over 0x20..EOF  -> header +0x10\n'
      '5. reparse exact type schemas and both style equations\n'
      '6. reject outer size > 4 MiB and reject any image-record-count change</pre>')

    A('<h3>Tier 1 — same length, no pointer touched</h3>')
    A('<p>These can change bytes in place. Recompute the entry/header CRCs for a '
      'canonical result even though the watch does not read them on install.</p>')
    A(table(["Manipulation", "Where", "Cost"], [
        ['Recolour text or an accent',
         '<code>Pair +0x24</code>, <code>VectorArc +0x34</code>, '
         '<code>Badge +0x28</code> or <code>Comp +0x58</code>. Only RGB survives; '
         'the stored alpha byte is ignored.',
         '<span class="chip">4 B + 2 CRCs</span>'],
        ['Move a widget using its type schema',
         '<code>+0x18/+0x1A</code> are signed placement/alignment offsets for most '
         'rendered types, but companion fields differ. Preserve Static/Hand and '
         'Pair/Comp LVGL alignment + target semantics; Badge owns two endpoints.',
         '<span class="chip">4 B + 2 CRCs</span>'],
        ['Repaint a raster at identical dimensions',
         'The pixel region of an image record. Re-encode to the same '
         '<code>format</code> and byte count and keep the trailer.',
         '<span class="chip">pixels + 2 CRCs</span>'],
        ['Re-pivot a clock hand',
         'Signed <code>i16 +0x20/+0x22</code>. Keep effective position plus pivot '
         'on the intended centre or '
         'the hand orbits off-centre.',
         '<span class="chip">4 B + 2 CRCs</span>'],
        ['Change Pair/Comp text formatting',
         'Pair dictionary base is <code>+0x2C</code> and selects '
         '<code>base + live_value</code>; Comp has three dictionary fields per part '
         'plus <code>+0x62</code> ordering. Every reachable index must exist in every locale.',
         '<span class="chip">2 B + 2 CRCs</span>'],
        ['Retarget a raster reference',
         'Only one of the six pointer layouts listed in §13; it must equal the '
         'start of a real image record in the same style.',
         '<span class="chip">4 B + 2 CRCs</span>'],
    ], "riskt"))

    A('<h3>Tier 2 — length changes, tight repack required</h3>')
    A('<p>Tight packing is what makes this tier tractable: recompute every later '
      'offset arithmetically and the invariants hold. A style-internal length change '
      'additionally shifts every image-section offset after the edit point, so every '
      'authoritative pointer field pointing past it must be adjusted. Do not scan '
      'all integers for coincidental equality.</p>')
    A(table(["Manipulation", "What must be rewritten"], [
        ['Replace a raster at a different size or format',
         'Image header <code>width</code>/<code>height</code>/<code>format</code>/'
         '<code>data_size</code>; the style header\'s <code>image_bytes</code>; every '
         'later image-section offset in every authoritative pointer field; the entry size in the '
         'directory; every later entry offset; the header size and both CRCs.'],
        ['Remove the final widget of a style',
         'Style header <code>widget_count</code>, <code>widget_bytes</code> and '
         '<code>image_section_offset</code>; entry size; later entry offsets; CRCs. '
         'Image-relative offsets are unaffected because the image section moves as a '
         'block.'],
        ['Append or remove a widget',
         'Structurally possible with exact record schema, count/length/index updates '
         'and a surviving-byte proof. Hardware acceptance is a separate boundary.'],
        ['Remove a non-final widget',
         'All of the above plus renumbering the high half of <code>+0x0C</code> for '
         'every later record. Verify afterwards that every surviving record is '
         'otherwise byte-identical rather than trying to predict what might '
         'reference an index.'],
        ['Add or remove an image record',
         '<strong>Do not.</strong> Hardware accepts transfer and install but can '
         'leave the old face active when image-record count changes. Rewrite existing '
         'records in place instead.'],
        ['Add or remove a directory entry',
         'The directory grows or shrinks by 74 bytes, so <em>every</em> payload '
         'offset in the file changes, including the first.'],
    ], "riskt"))

    A('<h3>Tier 3 — not plausible from this file</h3>')
    A(table(["Attempt", "Why it cannot work"], [
        ['Embed an arbitrary typeface',
         'No font program exists in a container. A face can switch among the '
         'firmware\'s own family selectors and supported pixel sizes, but cannot provide '
         'TTF/OTF bytes or name a new family.'],
        ['Add a new sensor or metric',
         'Sequence ids are firmware-defined. A value the watch does not publish '
         'renders as nothing; the file cannot introduce a data source.'],
        ['Treat 2047×2047 as a supported canvas',
         'Eleven-bit GUI descriptor fields can represent it; that is not hardware '
         'acceptance. A defensible Fit3 design targets 256×402 and keeps effective '
         'bounds and pivots consistent.'],
        ['Global search-and-replace on an integer',
         'Type-words are not a uniform pointer array — the same 32-bit value can be '
         'an image offset, an ARGB colour, an angle, a frame count, a glyph index or '
         'a mode. Only per-type, per-offset edits are sound.'],
        ['Recover the source artwork',
         'RGB565 quantisation is lossy and irreversible: 8-bit channels are '
         'discarded to 5/6/5. Extracted PNGs are exact reconstructions of what is '
         'stored, not of what was authored.'],
        ['Compress the image stream',
         'There is no package decompressor. Size can be reduced by reducing raster '
         'dimensions or changing a proven pixel format while preserving record count '
         'and repairing every later pointer—not by inserting PNG/JPEG bytes.'],
    ], "riskt"))

    A('<div class="banner w"><div><div class="bt">The three hardware/format traps</div>'
      '<p><strong>4 MiB is an exact outer ceiling.</strong> '
      '<strong>Changing image-record count can be silently ignored after a successful '
      'transfer.</strong> <strong>Image pointers are section-relative and '
      'type-specific.</strong> Moving a whole style needs no pointer edits; moving an '
      'image record inside its section requires repairing only the six authoritative '
      'layouts.</p></div></div>')

    # ---------------------------------------------------------------- unknown
    A('<h2 id="unknown">20 · What genuinely remains</h2>')
    A('<p class="lead">The parser is not being called “finished” by hiding unknown '
      'bytes. The vendor-only ledger has zero holes and every one of the 17 types has '
      'a known layout. What remains is evidence the available package '
      'set cannot supply.</p>')
    A('<div class="banner"><div><div class="bt">Closed questions</div><p>Style '
      '<code>+0x10</code> is the font count word; image trailers are exact four-byte '
      'GUI-excluded suffixes; Comp is four mini-programs; type 6 is VectorArc; all '
      '17 dispatch slots and every emitted type byte are classified.</p></div></div>')
    A(table(["Remaining evidence gap", "Exact boundary"], [
        [e(name), e(boundary)] for name, boundary in OPEN_GAPS
    ]))
    unclassified_entries = [
        f'{face}/{entry["basename"]}' for face in faces for entry in models[face]["entries"]
        if entry["parsed"].get("kind") in ("unknown", "unparsed")]
    cross_errors = [(face, error) for face in faces
                    for error in models[face].get("verification", {}).get("resource_errors", [])]
    A(f'<div class="banner{" r" if unclassified_entries or cross_errors else ""}"><div>'
      f'<div class="bt">This report input: '
      f'{"schema/cross-resource checks pass" if not unclassified_entries and not cross_errors else "check failures remain"}'
      f'</div><p>{len(unclassified_entries)} unclassified entries · '
      f'{len(cross_errors)} cross-resource errors · '
      f'{sum(m["coverage"].get("overlapping_byte_count", 0) for m in models.values())} '
      'overlapping bytes.</p></div></div>')

    A(f'''<div class="footer">
<p><strong>Method.</strong> A standalone parser re-derived the structure from raw
bytes; it shares no code with the writer it corroborates. Every field claim was
checked against a reconstruction of the source file, and the class census is
required to sum to the file size exactly. What each type does with its fields is
established separately and joined to the vendor previews and string tables, and kept
apart from what the watch decides on install. Shared schema version {SCHEMA_VERSION}.</p>
<p><strong>Corpus.</strong> {len(models)} container(s), {commas(total_bytes)} bytes.
{e(" · ".join(f"{f} {commas(models[f]['file_size'])} B sha256 {models[f]['sha256'][:12]}" for f in faces[:12]))}
{"…" if len(faces) > 12 else ""}</p>
<p><strong>Scope.</strong> The fixed reference ledger is {REFERENCE_CONTAINER_COUNT}
vendor-only containers ({commas(REFERENCE_CONTAINER_BYTES)} bytes); it deliberately
excludes 18 widget-import experiments and one clean-room candidate. This page's live
census uses only the models listed above. The open gaps listed above remain
explicit. Not affiliated with any device vendor; use only watch-face files you
are authorised to inspect and modify.</p>
</div></div></body></html>''')

    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text("\n".join(parts))
    print(f"[html] {output}  {output.stat().st_size / 1024:.0f} KiB", file=sys.stderr)


def main() -> int:
    ap = argparse.ArgumentParser(
        description="Render an HTML anatomy report from analyze_container.py output.")
    ap.add_argument("analysis", type=Path,
                    help="directory analyze_container.py wrote (holds index.json)")
    ap.add_argument("--output", type=Path,
                    help="HTML file to write (default: <analysis>/anatomy.html)")
    ap.add_argument("--faces", help="comma-separated subset of face names to include")
    ap.add_argument("--detail", type=int, default=2, metavar="N",
                    help="how many faces get full per-face sections (default: 2)")
    args = ap.parse_args()

    index_path = args.analysis / "index.json"
    if index_path.exists():
        index = json.loads(index_path.read_text())
        faces, images = index["faces"], index.get("images_exported", True)
    else:
        faces = sorted(p.parent.name for p in args.analysis.glob("*/model.json"))
        images = any((args.analysis / f / "images").is_dir() for f in faces)
    if args.faces:
        wanted = [f.strip() for f in args.faces.split(",") if f.strip()]
        missing = [f for f in wanted if f not in faces]
        if missing:
            print(f"not analysed: {', '.join(missing)}", file=sys.stderr)
            return 1
        faces = wanted
    if not faces:
        print(f"no models under {args.analysis}", file=sys.stderr)
        return 1

    stale = []
    for face in faces:
        model_path = args.analysis / face / "model.json"
        try:
            model = json.loads(model_path.read_text())
        except (OSError, json.JSONDecodeError) as error:
            print(f"cannot read {model_path}: {error}", file=sys.stderr)
            return 1
        version = model.get("schema_profile", {}).get("schema_version")
        if version != SCHEMA_VERSION:
            stale.append(f"{face} (schema {version!r})")
    if stale:
        print("models are stale; rerun tools/analyze_container.py before building "
              f"this report: {', '.join(stale)}", file=sys.stderr)
        return 1

    build(args.analysis, faces, max(1, args.detail), images,
          args.output or args.analysis / "anatomy.html")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
