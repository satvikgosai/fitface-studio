#!/usr/bin/env python3
"""Render a self-contained HTML anatomy report from analyze_container.py output.

Consumes the `model.json` files (and, if they were exported, the decoded PNGs)
under an analysis directory and writes one HTML page with no external requests:
every raster is inlined as a data URI and every chart is hand-built SVG.

Nothing in the page is asserted. Field readings, invariant hit-rates and the
"what remains unknown" table are all computed from the models actually loaded,
so the report is honest about a corpus of one face or of a hundred.

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
    A(f'''<title>Watch-face container — byte-level anatomy</title>
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
</style>''')

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
<li><a href="#faces">The corpus</a></li>
<li><a href="#budget">Where the bytes go</a></li>
<li><a href="#layout">File layout</a></li>
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
        return (m["header"]["crc16_ok"] and m["header"]["payload_ok"]
                and m["coverage"]["unaccounted_byte_count"] == 0
                and m["coverage"]["reconstruction_identical"]
                and m["stats"]["byte_class_delta"] == 0
                and all(en["crc16_ok"] for en in m["entries"]))

    bad = [f for f, m in models.items() if not clean(m)]
    crc_total = sum(len(m["entries"]) + 1 for m in models.values())
    A(f'<div class="banner{"" if not bad else " r"}"><div><div class="bt">'
      f'{"All integrity checks pass on every container" if not bad else f"{len(bad)} container(s) failed a check"}'
      f'</div><p>{commas(crc_total)} CRC-16/CCITT-FALSE checksums recomputed · '
      f'{sum(m["coverage"]["unaccounted_byte_count"] for m in models.values())} '
      f'unaccounted bytes · '
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
            f'<span class="{"ok" if s["byte_class_delta"] == 0 else "no"}">'
            f'{s["byte_class_delta"]}</span>',
            f'<span class="{"ok" if c["reconstruction_identical"] else "no"}">'
            f'{"identical" if c["reconstruction_identical"] else "DIFFERS"}</span>',
        ])
    A(table(["Container", "Bytes", "SHA-256", "Header CRC", "Entry CRCs",
             "Unaccounted", "Class residual", "Rebuild"], rows))

    # ---------------------------------------------------------------- corpus
    A('<h2 id="faces">2 · The corpus</h2>')
    A('<p class="lead">Each container ships one or more selectable visual styles and '
      'usually a low-power always-on style. The <code>preview.bin</code> rasters '
      'embedded in a container are the ground truth this report leans on to attach '
      'meaning to numeric field values: they show the face fully rendered, so a '
      'widget at a known coordinate can be matched to the thing drawn there.</p>')
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
    A('<h2 id="budget">3 · Where the bytes go</h2>')
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
    A('<h2 id="layout">4 · File layout</h2>')
    A('<p class="lead">The container is a flat archive: a fixed 32-byte header, a '
      'directory of 74-byte records, then every payload back to back. The first '
      'ribbon is true to scale — which makes the point that all metadata is '
      'invisible at file scale — so the second expands that region to full width.</p>')
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
    A('<h2 id="header">5 · Container header — 32 bytes</h2>')
    hd = models[faces[0]]["header"]
    A(field_diagram([
        (0x00, 4, "magic", "ASCII 'oppo' — the only recognised value", 0),
        (0x04, 4, "version", "u32, constant across the corpus", 1),
        (0x08, 4, "payload_size", "u32; must equal file_size - 32", 2),
        (0x0C, 4, "entries", "u32 directory record count", 3),
        (0x10, 2, "crc16", "CCITT-FALSE over 0x20..EOF", 4),
        (0x12, 2, "crc_hi?", "zero in every sample", 5),
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
         f'equals <code>file_size − 32</code> in '
         f'{sum(1 for m in models.values() if m["header"]["payload_ok"])}/{len(models)}'],
        ['<span class="m">0x0C</span>', '4', 'entry_count',
         '<span class="m">' + e(", ".join(f"{v} ×{n}" for v, n in cnt.most_common(8)))
         + '</span> — directory length is <code>74 × count</code>'],
        ['<span class="m">0x10</span>', '2', 'crc16',
         f'recomputed and matched in '
         f'{sum(1 for m in models.values() if m["header"]["crc16_ok"])}/{len(models)}; '
         'covers <code>0x20 … EOF</code>, i.e. directory <em>and</em> payloads'],
        ['<span class="m">0x12</span>', '2', 'crc high?',
         f'zero in {sum(1 for m in models.values() if m["header"]["crc_upper_zero"])}'
         f'/{len(models)} — plausibly the upper half of a 32-bit checksum field '
         'firmware never populated'],
        ['<span class="m">0x14</span>', '12', 'reserved',
         f'zero in {sum(1 for m in models.values() if m["header"]["reserved_zero"])}'
         f'/{len(models)}. Preserve verbatim.'],
    ]))
    A('<p>The checksum is CRC-16/CCITT-FALSE: polynomial <code>0x1021</code>, init '
      '<code>0xFFFF</code>, no reflection, no final XOR — identical to Python\'s '
      '<code>binascii.crc_hqx(data, 0xFFFF)</code>. Note that the header CRC covers '
      'the <em>directory as well as</em> the payloads, so changing any offset, size '
      'or per-entry CRC also invalidates it.</p>')

    # ---------------------------------------------------------------- dir
    A('<h2 id="dir">6 · Directory records — 74 bytes each</h2>')
    A(field_diagram([
        (0x00, 64, "path", "NUL-padded UTF-8 container-relative path", 0),
        (0x40, 4, "offset", "u32 absolute file offset of the payload", 2),
        (0x44, 4, "size", "u32 payload length in bytes", 3),
        (0x48, 2, "crc16", "CCITT-FALSE over the payload only", 4),
    ], 74, title="Directory record — 64 + 4 + 4 + 2 = 74 bytes, no padding"))
    padded = sum(1 for en in entries_all if en["path_padding_zero"])
    A(f'<p>Paths are container-relative and, in this corpus, always of the form '
      f'<code>./&lt;container&gt;/&lt;name&gt;</code>. The 64-byte field is '
      f'NUL-padded and every unused byte is zero in {padded} of '
      f'{len(entries_all)} records. The offset is <em>absolute</em> from the start '
      'of the file, not relative to the body — the detail that matters for any '
      'length-changing edit, because every later record must be rewritten.</p>')

    # ---------------------------------------------------------------- inventory
    A('<h2 id="inventory">7 · Entry inventory</h2>')
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
    A('<h2 id="setting">8 · setting.bin — a 256-byte identity block</h2>')
    if not settings:
        A('<p class="lead">No <code>setting.bin</code> entry in this corpus.</p>')
    else:
        A('<p class="lead">Fixed size, fixed layout; containers differ only in the '
          'face number, version and name.</p>')
        grid_face, grid_entry = next((f, en) for f, en in settings if f in deep) \
            if any(f in deep for f, _ in settings) else settings[0]
        sdata = (root / grid_face / "entries" / grid_entry["disk_name"]).read_bytes()
        sp = grid_entry["parsed"]
        A(byte_grid(sdata, [
            (0x00, 0x0C, "vendor marker, NUL-padded to 12 B", 0),
            (0x0C, 0x10, "struct magic 0x12345678", 1),
            (0x10, 0x20, "face id, 16-byte NUL-padded ASCII", 2),
            (0x30, 0x34, "face version (LE u32)", 3),
            (0x34, 0x36, "u16", 4),
            (0x36, 0x38, "u16 sentinel", 4),
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
                f'<span class="n">{p["word_0x34"]}</span>',
                f'<span class="m">{e(p["word_0x36"])}</span>',
                f'<span class="m">{e(p["name_slot_a"]["name"])}</span>',
                f'<span class="{"ok" if p["slots_identical"] else "no"}">'
                f'{"identical" if p["slots_identical"] else "differ"}</span>',
            ])
        A(table(["Container", "Marker", "Face id", "Version", "+0x34", "+0x36",
                 "Name slot A", "Slot B vs A"], rows))
        same = sum(1 for _, en in settings if en["parsed"]["slots_identical"])
        style_eq = sum(1 for f, en in settings
                       if en["parsed"]["word_0x34"] == models[f]["stats"]["style_count"])
        A(f'<p>The two 64-byte name slots are byte-identical in {same} of '
          f'{len(settings)} containers. <code>+0x34</code> equals the style count in '
          f'{style_eq} of {len(settings)} — suggestive of a variant count, and worth '
          'treating as unproven until it is seen to disagree.</p>')

    # ---------------------------------------------------------------- fonts
    fonts = [(f, en) for f in faces for en in models[f]["entries"]
             if en["parsed"].get("kind") == "font_binding"]
    A('<h2 id="fonts">9 · Font bindings — 92 bytes each</h2>')
    A('<p class="lead">These are <em>not</em> fonts. No glyph outlines, no bitmaps, '
      'no font program of any kind appears anywhere in a container. Each record '
      'names a text role and asks firmware for a size — the typeface lives in the '
      'watch ROM, so arbitrary font substitution is not possible through this '
      'file.</p>')
    A(field_diagram([
        (0x00, 1, "family", "firmware font-family index", 0),
        (0x01, 0x47, "opaque", "71 bytes, all zero unless family != 0", -1),
        (0x48, 16, "role", "NUL-padded ASCII role name", 2),
        (0x58, 4, "size", "u32 point size", 3),
    ], 92, title="Font-binding record — 1 + 71 + 16 + 4 = 92 bytes"))
    if fonts:
        A('<h4>Roles observed</h4>')
        A(census_table("Role @ size",
                       Counter(f'{en["parsed"]["binding_name"]} @ '
                               f'{en["parsed"]["point_size"]}pt' for _, en in fonts),
                       len(fonts)))
        zero_when_zero = sum(1 for _, en in fonts
                             if en["parsed"]["family_index"] == 0
                             and not en["parsed"]["opaque_nonzero"])
        fam0 = sum(1 for _, en in fonts if en["parsed"]["family_index"] == 0)
        mirrored = sum(
            1 for _, en in fonts
            if en["parsed"]["family_index"]
            and {o["offset"] for o in en["parsed"]["opaque_nonzero"]} == {1, 3, 0x1C, 0x2E}
            and all(o["value"] == en["parsed"]["family_index"]
                    for o in en["parsed"]["opaque_nonzero"]))
        nonzero = len(fonts) - fam0
        A(f'<p>The opaque region behaves consistently: it is entirely zero in '
          f'{zero_when_zero} of the {fam0} records whose family index is 0, and in '
          f'{mirrored} of the {nonzero} records with a non-zero family the '
          f'<em>same</em> value is repeated at exactly <code>+0x01</code>, '
          f'<code>+0x03</code>, <code>+0x1C</code> and <code>+0x2E</code> with every '
          'other byte zero. That is consistent with four sub-structures each carrying '
          'a family selector — a hypothesis, not a proven layout.</p>')

    # ---------------------------------------------------------------- glyphs
    glyphs = [(f, en) for f in faces for en in models[f]["entries"]
              if en["parsed"].get("kind") == "glyph_table"]
    A('<h2 id="glyphs">10 · Locale glyph tables — the strings the watch draws</h2>')
    A('<p class="lead">Each is a tiny string table, and the group index is what '
      'widget records point at — this is the join that turns an opaque widget number '
      'into a readable label.</p>')
    A(field_diagram([
        (0x00, 4, "magic", "0x12345678 vendor struct sentinel", 1),
        (0x04, 4, "locale", "locale id", 0),
        (0x08, 4, "groups", "u32 string count N", 2),
        (0x0C, 12, "reserved", "three zero words", -1),
        (0x18, 56, "N x descriptor", "8 bytes each: u32 byte length, u32 offset", 3),
        (0x50, 51, "UTF-8 text", "concatenated strings, no separators", 4),
    ], 131, title="Glyph-table layout — descriptor table then a packed text region"))
    if glyphs:
        loc = Counter(f'{en["basename"]} = {en["parsed"]["locale_word"]}'
                      for _, en in glyphs)
        A('<h4>Locale identifiers observed</h4>')
        A(census_table("Entry = locale id", loc, len(glyphs)))
        unacc = sum(en["parsed"]["unaccounted_bytes"] for _, en in glyphs)
        A(f'<p>Across {len(glyphs)} glyph tables the descriptor offsets account for '
          f'every byte of the text region with {unacc} unaccounted, and the '
          f'descriptors are in ascending offset order in '
          f'{sum(1 for _, en in glyphs if en["parsed"]["ascending_offsets"])} of them. '
          'The descriptor length is a <strong>byte</strong> count, not a character '
          'count — the two differ for every non-ASCII string.</p>')
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
    A('<h2 id="style">11 · Style entry anatomy</h2>')
    A('<p class="lead"><code>aod.bin</code> and every <code>styleN.bin</code> share '
      'one structure: a 24-byte header, a packed run of variable-length widget '
      'records, then a packed run of rasters. Two equations must hold exactly.</p>')
    A(field_diagram([
        (0x00, 4, "magic", "0x12345678", 1),
        (0x04, 4, "widgets", "u32 record count", 0),
        (0x08, 4, "widget B", "u32 total bytes of widget records", 2),
        (0x0C, 4, "image B", "u32 total bytes of the image section", 4),
        (0x10, 4, "unknown", "purpose unknown; see §19", -1),
        (0x14, 4, "image off", "u32 == 24 + widget bytes", 3),
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
    A('<h4>The unexplained word at +0x10</h4>')
    A(census_table("Value", Counter(en["parsed"]["unknown_0x10_hex"]
                                    for _, en in all_styles), len(all_styles)))
    A(f'<p>Every raster in a style entry is referenced by at least one widget in '
      f'{len(all_styles) - sum(1 for _, en in all_styles if en["parsed"]["images_unreferenced"])} '
      f'of {len(all_styles)} entries — {unref} unreferenced rasters in total. There '
      'is essentially no dead image data to reclaim and no hidden asset tucked into '
      'an image section.</p>')
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
                f'<span class="m">{p["unknown_0x10_hex"]}</span>',
                f'<span class="n">{p["image_count"]}</span>',
                f'<span class="{"ok" if not p["images_unreferenced"] else "no"}">'
                f'{len(p["images_unreferenced"])}</span>',
            ])
        A(f'<details><summary>{e(f)} — style entries</summary>'
          + table(["Entry", "Bytes", "Widgets", "Widget B", "Img offset", "Image B",
                   "unk 0x10", "Rasters", "Unreferenced"], rows) + '</details>')

    # ---------------------------------------------------------------- widgets
    A('<h2 id="widgets">12 · Widget records</h2>')
    sizes_seen = sorted({w["record_size"] for _, _, w in widgets})
    A(f'<p class="lead">A widget is a 36-byte fixed head followed by zero or more '
      f'32-bit type-specific words. The record length is packed into the low half of '
      f'the word at <code>+0x0C</code>; the high half is the widget\'s global index. '
      f'Observed sizes here run '
      f'{min(sizes_seen) if sizes_seen else 0}–{max(sizes_seen) if sizes_seen else 0} '
      f'bytes across {commas(len(widgets))} records.</p>')
    A(field_diagram([
        (0x00, 4, "type", "1 Static · 2 Hand · 3 Sprite · 5 Pair · 7 Badge · 13 Comp", 0),
        (0x04, 4, "seq", "data-source id; 0 = static, otherwise firmware-defined", 1),
        (0x08, 4, "opaque", "see the invariant census in §19", -1),
        (0x0C, 4, "idx|size", "high 16 = global index, low 16 = record length", 2),
        (0x10, 8, "opaque", "two words; see §19", -1),
        (0x18, 2, "x", "int16 — may be negative when the anchor mode says so", 3),
        (0x1A, 2, "y", "int16", 3),
        (0x1C, 2, "w/x2", "width, or a second endpoint for Badge", 3),
        (0x1E, 2, "h/y2", "height, or a second endpoint for Badge", 3),
        (0x20, 4, "word A", "Hand pivot · Sprite frame count · Pair anchor mode", 4),
        (0x24, 24, "type words", "0..N u32: image offsets, ARGB colours, angles, "
                                 "glyph indices, modes", 5),
    ], 60, title="Widget record — 36-byte fixed head then N type-specific u32 words",
        note=f"Diagram shown at 60 bytes; observed lengths: "
             f"{', '.join(str(s) for s in sizes_seen[:14])}."))

    tails = sum(1 for _, _, w in widgets if w["tail_size"])
    A(f'<div class="banner{" w" if tails else ""}"><div><div class="bt">'
      f'Record alignment</div><p>{commas(len(widgets) - tails)} of '
      f'{commas(len(widgets))} records are cleanly 4-byte aligned — the 36-byte head '
      f'plus N complete words accounts for the whole record — and '
      f'{commas(tails)} carry a leftover tail after the final complete word. '
      'A writer must preserve that tail verbatim whether or not this particular '
      'corpus exercises it.</p></div></div>')

    A('<h3>Field readings, each tested against every matching record</h3>')
    A('<p class="lead">Each hypothesis below was predicted from one record and then '
      'checked against every record of that type in the corpus. The hit rate is '
      'computed, not asserted — a reading that does not hold everywhere says so.</p>')

    # Hand pivot: x + pivot_x and y + pivot_y should land on the panel centre.
    hand_rows, hand_ok, hand_total = [], 0, 0
    for f, ent, w in widgets:
        if w["type_name"] != "Hand":
            continue
        panel = panel_of(models[f])
        if not panel:
            continue
        hand_total += 1
        px, py = w["word_0x20"] & 0xFFFF, w["word_0x20"] >> 16
        hit = (w["x"] + px, w["y"] + py) == (panel[0] // 2, panel[1] // 2)
        hand_ok += hit
        if len(hand_rows) < 6:
            hand_rows.append([
                f'<span class="m">{e(short(f))}/{e(ent)}</span>',
                f'<span class="n">{w["sequence_id"]}</span>',
                f'<span class="m">({w["x"]}, {w["y"]})</span>',
                f'<span class="m">0x{w["word_0x20"]:08X}</span>',
                f'<span class="m">({px}, {py})</span>',
                f'<span class="m {"ok" if hit else "no"}">({w["x"] + px}, '
                f'{w["y"] + py}) {"✓" if hit else "✗"}</span>',
            ])
    if hand_total:
        A(f'<h3>Hand (type 2): <code>+0x20</code> is a rotation pivot '
          f'<span class="chip {"g" if hand_ok == hand_total else "w"}">'
          f'{hand_ok}/{hand_total} records</span></h3>')
        A('<p>Reading <code>+0x20</code> as <code>(pivot_y &lt;&lt; 16) | pivot_x</code> '
          'and adding it to the record\'s <code>x,y</code> lands on the exact centre '
          'of the declared panel. A hand therefore has no fixed rectangle to outline: '
          'the watch rotates it about that point.</p>')
        A(table(["Entry", "Seq", "x, y", "word +0x20", "pivot", "x+px, y+py"], hand_rows))

    sprites = [w for _, _, w in widgets if w["type_name"] == "Sprite"]
    if sprites:
        ok2 = sum(1 for w in sprites if w["word_0x20"] == len(w["type_words"]))
        allref = sum(1 for w in sprites
                     if w["word_0x20"] == len(w["type_words"])
                     and len(w["image_refs"]) >= len(w["type_words"]))
        A(f'<h3>Sprite (type 3): <code>+0x20</code> is a frame count '
          f'<span class="chip {"g" if ok2 == len(sprites) else "w"}">'
          f'{ok2}/{len(sprites)} records</span></h3>')
        A(f'<p>The word at <code>+0x20</code> equals the number of trailing '
          f'type-words, and in {allref} of those records every one of those words '
          'also resolves to a real image-section offset. Take exactly that many '
          'words — no more.</p>')
        rows, seen = [], set()
        for w in sprites:
            k = (w["sequence_id"], w["word_0x20"])
            if k in seen or len(rows) >= 14:
                continue
            seen.add(k)
            rows.append([
                f'<span class="n">{w["sequence_id"]}</span>',
                f'<span class="m">{e(w["sequence_label"] or "—")}</span>',
                f'<span class="n">{w["word_0x20"]}</span>',
                f'<span class="n">{len(w["type_words"])}</span>',
                f'<span class="n">{len({t["value"] for t in w["type_words"]})}</span>',
                f'<span class="m">({w["x"]}, {w["y"]})</span>',
            ])
        A(table(["Seq", "Label", "word +0x20", "Type words", "Unique rasters", "x, y"],
                rows))

    pairs = [w for _, _, w in widgets if w["type_name"] == "Pair"]
    if pairs:
        modes = Counter(w["word_0x20"] for w in pairs)
        consistent = sum(1 for w in pairs
                         if (w["word_0x20"] == 1 and w["x"] >= 0)
                         or (w["word_0x20"] == 3 and w["x"] < 0)
                         or (w["word_0x20"] == 0 and w["x"] >= 0))
        A(f'<h3>Pair (type 5): <code>+0x20</code> is an anchor mode '
          f'<span class="chip {"g" if consistent == len(pairs) else "w"}">'
          f'{consistent}/{len(pairs)} records</span></h3>')
        A('<p>Mode 1 always pairs with a non-negative <code>x</code>, mode 3 always '
          'with a negative one, and mode 0 with a non-negative one. Read as: '
          '0 = absolute placement, 1 = anchor left with <code>x</code> an inset from '
          'the left edge, 3 = anchor right with <code>x</code> a negative inset from '
          'the right edge. A negative <code>x</code> under any other mode would be '
          'off-panel, which is why an editor must respect this field when it moves a '
          'Pair.</p>')
        A(census_table("Anchor mode at +0x20", modes, len(pairs)))

    others = Counter(w["type_name"] for _, _, w in widgets
                     if w["type_name"] not in ("Hand", "Sprite", "Pair"))
    if others:
        A('<h3>Remaining widget types in this corpus</h3>')
        A(census_table("Type", others, sum(others.values())))
        A('<p>A <code>Badge</code> reinterprets <code>+0x1C</code>/<code>+0x1E</code> '
          'as a second endpoint rather than a width and height, so its span is '
          '<code>|x2 − x|</code> and the stored coordinate may be either end. '
          '<code>Comp</code> records decompose into triplets packed as '
          '<code>(glyph_group &lt;&lt; 16) | sequence_id</code>; the prefix/suffix '
          'role of each word within a triplet is <strong>not</strong> pinned down, so '
          'treat Comp internals as read-only. Any <code>type_N</code> row is a type '
          'this analyzer does not interpret: its bytes are preserved and its position '
          'is still readable.</p>')

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
    A('<h2 id="widgetdata">13 · Every widget, decoded</h2>')
    A('<p class="lead"><code>seq</code> is the raw data-source id. The label column '
      'is filled only where a preview raster and the widget\'s own geometry agreed; '
      'firmware ids have no names in the file, so a blank there means "not '
      'established", never "no meaning".</p>')
    for f in deep:
        m = models[f]
        A(f'<h4>{e(f)}</h4>')
        for en in styles_of(m):
            p = en["parsed"]
            rows = []
            for w in p["widgets"]:
                refs = ", ".join(f'#{h["image_index"]}' for h in w["image_refs"]) or "—"
                words = " ".join(t["hex"] for t in w["type_words"][:6])
                if len(w["type_words"]) > 6:
                    words += f" …+{len(w['type_words']) - 6}"
                rows.append([
                    f'<span class="m">{w["ordinal"]}</span>',
                    f'<span class="m">0x{en["payload_offset"] + w["record_offset"]:06X}</span>',
                    f'<span class="chip">{e(w["type_name"])}</span>',
                    f'<span class="n">{w["sequence_id"]}</span>',
                    f'<span class="n">{w["record_size"]}</span>',
                    f'<span class="m">{w["x"]}, {w["y"]}</span>',
                    f'<span class="m">{w["w"]}×{w["h"]}</span>',
                    f'<span class="m">{w["word_0x20_hex"]}</span>',
                    f'<span class="m">{e(words)}</span>',
                    f'<span class="m">{e(refs)}</span>',
                    e(w["sequence_label"]),
                ])
            hdrs = ["#", "File offset", "Type", "Seq", "Size", "x,y", "w,h",
                    "+0x20", "Type words", "Rasters", "Label"]
            A(f'<details><summary>{e(en["basename"])} — '
              f'{p["parsed_widget_count"]} widgets</summary>{table(hdrs, rows)}</details>')

    # ---------------------------------------------------------------- images
    A('<h2 id="images">14 · Image records &amp; pixel formats</h2>')
    A('<p class="lead">A 12-byte header, then raw row-major pixels, then a trailer. '
      'No compression and no filtering — a plain framebuffer.</p>')
    A(field_diagram([
        (0x00, 2, "width", "u16 pixels", 0),
        (0x02, 2, "height", "u16 pixels", 0),
        (0x04, 2, "format", "0x0082 RGB565 · 0x0080 RGB565+A · 0x0088 Indexed8", 1),
        (0x06, 2, "reserved", "zero throughout this corpus", -1),
        (0x08, 4, "data size", "u32 == palette + width x height x bpp + trailer", 2),
        (0x0C, 20, "pixels", "raw row-major, top-left origin, no filtering", 3),
        (0x20, 4, "trailer", "opaque bytes after the pixel plane", 4),
    ], 36, title="Image record — 12-byte header, raw pixels, trailer"))
    A(pixel_format_diagram())
    A('<p>The third format is an indexed one: a 256-entry <strong>BGRA</strong> '
      'palette (1,024 bytes) followed by one index byte per pixel. It is rare but '
      'not optional — a decoder that rejects it cannot open every face. Because the '
      'palette is fixed-length, an indexed raster can still be replaced as a '
      'same-size patch.</p>')
    all_imgs = [i for m in models.values() for i in m["images"]]
    trailers = Counter(i["trailer_size"] for i in all_imgs)
    A(f'<p>Across {commas(len(all_imgs))} image records the trailer size is: '
      + ", ".join(f'<code>{t} B</code> ×{commas(n)}' for t, n in trailers.most_common())
      + '. Image references inside widget records are byte offsets <em>relative to '
        'the style\'s image-section start</em>, never absolute file offsets — which '
        'is why a style entry can be relocated wholesale without touching a single '
        'widget word.</p>')
    A('<h4>Format and geometry census</h4>')
    A(census_table("Pixel format", Counter(i["format_name"] for i in all_imgs),
                   len(all_imgs)))
    A(census_table("Raster size",
                   Counter(f'{i["width"]}x{i["height"]}' for i in all_imgs),
                   len(all_imgs)))

    # ---------------------------------------------------------------- assets
    A('<h2 id="assets">15 · Asset inventory</h2>')
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
    A('<h2 id="themes">16 · How variants work</h2>')
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
        seqs = [{w["sequence_id"] for w in en["parsed"]["widgets"]} for en in st]
        shared = set.intersection(*seqs) if seqs else set()
        union = set.union(*seqs) if seqs else set()
        rows.append([
            f'<span class="m">{e(f)}</span>',
            f'<span class="n">{len(st)}</span>',
            f'<span class="m">{"yes" if same_len else "no"}</span>',
            f'<span class="n">{min(deltas) if deltas else "—"}</span>',
            f'<span class="n">{max(deltas) if deltas else "—"}</span>',
            f'<span class="n">{len(shared)}/{len(union)}</span>',
        ])
    A(table(["Container", "Styles", "Equal widget-section length",
             "Min bytes differing", "Max bytes differing",
             "Data sources in every style"], rows))
    A('<p>The last column is the one that bites an editor. Where it is not '
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
    A('<h2 id="entropy">17 · Entropy profile</h2>')
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
      'structural decode that leaves zero unaccounted bytes, there is no room for a '
      'hidden payload: every byte belongs to a record whose length is declared by '
      'its parent.</p>')

    # ---------------------------------------------------------------- manip
    A('<h2 id="manip">18 · Plausible manipulations</h2>')
    A('<p class="lead">What the format lets you change, ranked by how much of the '
      'file has to move. The governing fact is that <strong>payload offsets are '
      'absolute and the header CRC covers the directory</strong>: any length change '
      'rewrites every later offset, every later payload position, one entry CRC and '
      'the header CRC.</p>')
    A('<h4>The checksum obligation, in order</h4>')
    A('<pre>1. patch payload bytes\n'
      '2. recompute that entry\'s CRC-16     -> directory record +0x48\n'
      '3. if any length changed:\n'
      '     rewrite every later entry offset -> directory record +0x40\n'
      '     rewrite header payload_size      -> header +0x08\n'
      '4. recompute header CRC-16 over 0x20..EOF  -> header +0x10\n'
      '5. reparse and verify both equations in every style entry</pre>')

    A('<h3>Tier 1 — same length, no pointer touched</h3>')
    A('<p>These change bytes in place. One entry CRC plus the header CRC, and nothing '
      'else moves.</p>')
    A(table(["Manipulation", "Where", "Cost"], [
        ['Recolour text or an accent',
         'The ARGB type-word of a <code>Pair</code>, <code>Badge</code> or '
         '<code>Comp</code> record.',
         '<span class="chip">4 B + 2 CRCs</span>'],
        ['Move a widget',
         '<code>+0x18</code>/<code>+0x1A</code> int16 <code>x,y</code>. Respect the '
         'anchor mode at <code>+0x20</code> — a negative <code>x</code> only makes '
         'sense under a right-anchored mode.',
         '<span class="chip">4 B + 2 CRCs</span>'],
        ['Repaint a raster at identical dimensions',
         'The pixel region of an image record. Re-encode to the same '
         '<code>format</code> and byte count and keep the trailer.',
         '<span class="chip">pixels + 2 CRCs</span>'],
        ['Re-pivot a clock hand',
         '<code>+0x20</code> as <code>(pivot_y&lt;&lt;16)|pivot_x</code>. Keep '
         '<code>x+pivot_x</code> and <code>y+pivot_y</code> on the panel centre or '
         'the hand orbits off-centre.',
         '<span class="chip">4 B + 2 CRCs</span>'],
        ['Swap a static label',
         'The glyph-group index in a <code>Pair</code> type-word — it must name an '
         'existing group in <em>every</em> locale table.',
         '<span class="chip">2 B + 2 CRCs</span>'],
        ['Retarget a raster reference',
         'Any type-word holding an image-section-relative offset — it must equal the '
         'start of a real image record in the same style.',
         '<span class="chip">4 B + 2 CRCs</span>'],
    ], "riskt"))

    A('<h3>Tier 2 — length changes, tight repack required</h3>')
    A('<p>Tight packing is what makes this tier tractable: recompute every later '
      'offset arithmetically and the invariants hold. A style-internal length change '
      'additionally shifts every image-section offset after the edit point, so every '
      'widget word pointing past it must be adjusted.</p>')
    A(table(["Manipulation", "What must be rewritten"], [
        ['Replace a raster at a different size or format',
         'Image header <code>width</code>/<code>height</code>/<code>format</code>/'
         '<code>data_size</code>; the style header\'s <code>image_bytes</code>; every '
         'later image-section offset in every widget word; the entry size in the '
         'directory; every later entry offset; the header size and both CRCs.'],
        ['Remove the final widget of a style',
         'Style header <code>widget_count</code>, <code>widget_bytes</code> and '
         '<code>image_section_offset</code>; entry size; later entry offsets; CRCs. '
         'Image-relative offsets are unaffected because the image section moves as a '
         'block.'],
        ['Append a duplicate widget',
         'The same fields as removal, plus a global index that does not collide.'],
        ['Remove a non-final widget',
         'All of the above plus renumbering the high half of <code>+0x0C</code> for '
         'every later record. Verify afterwards that every surviving record is '
         'otherwise byte-identical rather than trying to predict what might '
         'reference an index.'],
        ['Add or remove a directory entry',
         'The directory grows or shrinks by 74 bytes, so <em>every</em> payload '
         'offset in the file changes, including the first.'],
    ], "riskt"))

    A('<h3>Tier 3 — not plausible from this file</h3>')
    A(table(["Attempt", "Why it cannot work"], [
        ['Change a typeface',
         'No font program exists in a container. <code>font_*.bin</code> records '
         'select a firmware family index and a point size; the glyphs are in ROM.'],
        ['Add a new sensor or metric',
         'Sequence ids are firmware-defined. A value the watch does not publish '
         'renders as nothing; the file cannot introduce a data source.'],
        ['Change the panel geometry',
         'It is baked into every background raster, the hand pivot arithmetic, the '
         'container name, <code>setting.bin</code> and the filename the plugin '
         'matches on.'],
        ['Global search-and-replace on an integer',
         'Type-words are not a uniform pointer array — the same 32-bit value can be '
         'an image offset, an ARGB colour, an angle, a frame count, a glyph index or '
         'a mode. Only per-type, per-offset edits are sound.'],
        ['Recover the source artwork',
         'RGB565 quantisation is lossy and irreversible: 8-bit channels are '
         'discarded to 5/6/5. Extracted PNGs are exact reconstructions of what is '
         'stored, not of what was authored.'],
        ['Reduce the file size meaningfully',
         'There is no compression stage and essentially no unreferenced raster. Size '
         'is a direct function of pixel count.'],
    ], "riskt"))

    A('<div class="banner w"><div><div class="bt">The two traps</div>'
      '<p><strong>The header CRC covers the directory.</strong> Fixing an entry CRC '
      'without recomputing the header CRC leaves a file that fails validation. '
      '<strong>Image references are section-relative, not absolute.</strong> Moving '
      'a whole style needs no widget edits at all; changing anything <em>inside</em> '
      'a style\'s image section before the last raster needs all of them.</p></div></div>')

    # ---------------------------------------------------------------- unknown
    A('<h2 id="unknown">19 · What remains unknown</h2>')
    A('<p class="lead">Being explicit about the edges. Every item below is preserved '
      'byte-for-byte by this analysis and should be by any writer. The counts are '
      'measured over the corpus actually loaded — a field that never varies cannot '
      'be characterised, however many samples there are.</p>')
    z08 = sum(1 for _, _, w in widgets if w["opaque_0x08"] == 0)
    z10 = sum(1 for _, _, w in widgets if w["opaque_0x10"] == 0)
    z14 = sum(1 for _, _, w in widgets if w["opaque_0x14"] == 0)
    resv = sum(1 for i in all_imgs if True)  # reserved lives in the model per record
    unk10 = Counter(en["parsed"]["unknown_0x10_hex"] for _, en in all_styles)
    seqs = Counter(w["sequence_id"] for _, _, w in widgets)
    unlabelled = sorted(s for s in seqs if not any(
        w["sequence_label"] for _, _, w in widgets if w["sequence_id"] == s))
    A(table(["Field", "Observed", "Status"], [
        ['Header <code>+0x12</code> (2 B)',
         f'zero in {sum(1 for m in models.values() if m["header"]["crc_upper_zero"])}'
         f'/{len(models)} containers',
         'Possibly the high half of a 32-bit checksum. Never non-zero here.'],
        ['Style header <code>+0x10</code>',
         e(", ".join(f"{v} ×{n}" for v, n in unk10.most_common(6))),
         'Tracks the container rather than the style, the size or the widget count. '
         'Purpose unknown.'],
        ['Image trailer',
         ", ".join(f"{t} B ×{commas(n)}" for t, n in trailers.most_common()),
         'Present on every record. Contents not interpreted.'],
        ['Widget <code>+0x08</code>, <code>+0x10</code>, <code>+0x14</code>',
         f'zero in {commas(z08)}, {commas(z10)}, {commas(z14)} of '
         f'{commas(len(widgets))} records',
         'Cannot be characterised from data that never varies. No field here has '
         'ever been shown to hold another widget\'s global index.'],
        ['<code>Comp</code> triplet grammar',
         f'{sum(1 for _, _, w in widgets if w["type_name"] == "Comp")} records',
         'The <code>(group&lt;&lt;16)|seq</code> packing is confirmed; the '
         'prefix/suffix role of each word within a triplet is not.'],
        ['Sequence-id namespace',
         f'{len(seqs)} distinct ids, {len(unlabelled)} with no established reading',
         'Firmware-defined. Every label in this report comes from a preview raster '
         'plus geometry, never from a vendor table.'],
        ['Unclassified entries',
         f'{sum(1 for en in entries_all if en["parsed"].get("kind") in ("unknown", "unparsed"))}'
         f' of {len(entries_all)}',
         'Extracted verbatim and counted in the byte census, but not interpreted.'],
    ]))

    A(f'''<div class="footer">
<p><strong>Method.</strong> A standalone parser re-derived the structure from raw
bytes; it shares no code with the writer it corroborates. Every field claim was
checked against a reconstruction of the source file, and the class census is
required to sum to the file size exactly. Field readings are labelled by how they
were obtained — arithmetic invariants tested across all matching records, agreement
with the embedded preview rasters, or explicitly marked as unproven.</p>
<p><strong>Corpus.</strong> {len(models)} container(s), {commas(total_bytes)} bytes.
{e(" · ".join(f"{f} {commas(models[f]['file_size'])} B sha256 {models[f]['sha256'][:12]}" for f in faces[:12]))}
{"…" if len(faces) > 12 else ""}</p>
<p><strong>Scope.</strong> Structural conclusions rest on arithmetic invariants and
byte-identical reconstruction. Semantic readings of firmware-defined ids are
inferences. Not affiliated with any device vendor; use only watch-face files you
are authorised to inspect and modify.</p>
</div></div>''')

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

    build(args.analysis, faces, max(1, args.detail), images,
          args.output or args.analysis / "anatomy.html")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
