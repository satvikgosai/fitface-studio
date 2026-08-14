# The container format, byte by byte

How the SM-R390 watch-face container is laid out, derived from two files and then
checked against the whole catalogue. This is the reference `:core:format` is
written against; [architecture.md](architecture.md) covers how the app uses it,
and [editing.md](editing.md) covers which edits are safe and why.

The derivation below analyses two specific files:

```text
<corpusRoot>/SM-R390_00046/assets/SM-R390_00046_256x402.bin   1,611,265 bytes
<corpusRoot>/SM-R390_00106/assets/SM-R390_00106_256x402.bin   1,880,368 bytes
```

`<corpusRoot>` is the uncommitted corpus directory described in
[development.md](development.md#the-test-corpus).

```text
00046  sha256 b71f60a843048d78151799936d1f030fd5ff1691ef8d7db1a67fca01cd27f755
00106  sha256 af34ca4ded7e49311fbe522f9e07112ffd8e8ee2bcb50743eb5fc67113dedffc
```

This report accounts for every byte of these two files, extracts every embedded
asset, and separates what is proven from what is guessed.
[§11](#11-fields-this-analysis-established) collects the fields whose reading was
established or pinned down here, with the record counts each was verified against,
because those are the findings the implementation relies on. [§14](#14-related-work)
lists the other public work on this format.

## Contents

1. [Method and verification](#1-method-and-verification)
2. [What these two files are](#2-what-these-two-files-are)
3. [Byte budget](#3-byte-budget)
4. [Container layer](#4-container-layer)
5. [Metadata entries](#5-metadata-entries)
6. [Style entries](#6-style-entries)
7. [Widget records](#7-widget-records)
8. [Rasters](#8-rasters)
9. [Theme mechanics](#9-theme-mechanics)
10. [Plausible manipulations](#10-plausible-manipulations)
11. [Fields this analysis established](#11-fields-this-analysis-established)
12. [What remains unknown](#12-what-remains-unknown)
13. [Reproducing this analysis](#13-reproducing-this-analysis)
14. [Related work](#14-related-work)

---

## 1 · Method and verification

The parse was re-derived from the raw bytes by a standalone analyzer that shares
no code with the Android app, so agreement between the two is independent
corroboration rather than a tautology.

Three checks had to pass before any claim in this document was allowed to stand.

| Check | `00046` | `00106` |
| --- | --- | --- |
| Header CRC-16 recomputed and matched | `0x9626` ✓ | `0x8E5B` ✓ |
| Entry CRC-16 recomputed and matched | 17/17 ✓ | 19/19 ✓ |
| Declared payload size == `file_size − 32` | ✓ | ✓ |
| Bytes not covered by any parsed region | 0 | 0 |
| Gaps between payloads | 0 | 0 |
| Trailing bytes after the last payload | 0 | 0 |
| Semantic class census residual | 0 | 0 |
| File rebuilt from parsed pieces | byte-identical | byte-identical |

The class census is the strongest of these. Every byte is assigned to exactly one
of ten named classes (container header, directory, image headers, image pixels,
image trailers, style headers, widget records, setting, font bindings, glyph
tables), and the sum of those classes is required to equal the file size exactly.
Both files reconcile to zero. There is no slack anywhere in either container
where undocumented data could hide.

The **proven** / **supported** / **unknown** labels used throughout are defined in
[README.md](README.md#honesty-about-evidence) and mean the same thing in every
document here.

Semantic readings of sequence IDs deserve particular caution. They are firmware
constants; nothing in the file names them. Where this report labels one, the
label comes from the widget's own geometry agreeing with the rendered
`preview.bin` raster — never from a lookup table.

## 2 · What these two files are

Both are OPPO-format containers for a 256 × 402 panel, holding four selectable
visual styles plus a low-power always-on style.

| | `00046` | `00106` |
| --- | --- | --- |
| Name (`en_US`) | Minimalist | Fitness pro 3 |
| Category | Classic | Informative |
| Design | Analog, Roman numerals, three hands | Digital, four corner metrics |
| Directory entries | 17 | 19 |
| Widget records | 28 | 81 |
| Rasters | 24 | 147 |
| Widget types used | Static, Hand, Pair | Static, Sprite, Pair, Badge, Comp |

The four `preview.bin` rasters in each file are what makes semantic decoding
possible at all: they show the face fully rendered with live values, so a widget
at a known coordinate can be matched to the thing drawn there.

`00106`'s previews read `3457 steps`, `89 bpm`, `☀ 25°`, `a.m.`, `10:08`,
`2023-12-28`, `350 kcal`, `53 active time`. Every one of those is traceable to a
specific widget record and, for the static labels, to a specific glyph-table
group.

**A metadata caveat.** Both `bandface_info.json` files declare
`"_screen": "402x256"` and name a payload `SM-R390_<id>_402x256.bin`, while the
actual member is `256x402` and every raster is stored 256 wide by 402 tall.
`00106`'s description also reads "A simple and refined analog watch face", which
describes `00046`, not the digital face it ships with. The JSON is a loose label,
not a specification.

## 3 · Byte budget

These containers are uncompressed framebuffers with a thin index bolted on.

| Class | `00046` | share | `00106` | share |
| --- | ---: | ---: | ---: | ---: |
| Image pixels | 1,606,640 | 99.713% | 1,869,368 | 99.415% |
| Widget records | 1,304 | 0.081% | 5,212 | 0.277% |
| Directory | 1,258 | 0.078% | 1,406 | 0.075% |
| Glyph tables | 1,087 | 0.067% | 1,254 | 0.067% |
| Image headers | 288 | 0.018% | 1,764 | 0.094% |
| `setting.bin` | 256 | 0.016% | 256 | 0.014% |
| Font bindings | 184 | 0.011% | 368 | 0.020% |
| Style headers | 120 | 0.007% | 120 | 0.006% |
| Image trailers | 96 | 0.006% | 588 | 0.031% |
| Container header | 32 | 0.002% | 32 | 0.002% |
| **Total** | **1,611,265** | **100%** | **1,880,368** | **100%** |

All structure in `00046` fits in 4,529 bytes; in `00106`, 10,412 bytes. Two
consequences follow directly and shape everything in §10:

- File size is a pure function of pixel count. There is no compression stage to
  tune and, as §8 shows, no unreferenced raster to delete.
- Structural edits are cheap to compute but every length change cascades,
  because the index is tiny relative to what it indexes.

Shannon entropy per 64 KiB block peaks at 2.53 bits/byte (`00046`) and 1.85
(`00106`), against ~7.9 for compressed or encrypted data. Combined with the
zero-residual class census, there is no hidden payload in either file.

## 4 · Container layer

### Header — 32 bytes

| Offset | Size | Field | `00046` | `00106` |
| ---: | ---: | --- | --- | --- |
| `0x00` | 4 | magic | `6F 70 70 6F` (`oppo`) | same |
| `0x04` | 4 | version | 4 | 4 |
| `0x08` | 4 | payload size | 1,611,233 | 1,880,336 |
| `0x0C` | 4 | entry count | 17 | 19 |
| `0x10` | 2 | CRC-16 | `0x9626` | `0x8E5B` |
| `0x12` | 2 | unknown | `00 00` | `00 00` |
| `0x14` | 12 | reserved | all zero | all zero |

CRC-16/CCITT-FALSE: polynomial `0x1021`, init `0xFFFF`, no reflection, no final
XOR — equivalent to `binascii.crc_hqx(data, 0xFFFF)`.

**The header CRC covers bytes `0x20 … EOF`, which includes the directory.** This
is the single most important fact for any writer: changing an entry offset, an
entry size, or a per-entry CRC also invalidates the header CRC. Fixing only the
entry CRC produces a file that fails validation.

### Directory records — 74 bytes

| Offset | Size | Field |
| ---: | ---: | --- |
| `0x00` | 64 | path, NUL-padded UTF-8 |
| `0x40` | 4 | payload offset, absolute from file start |
| `0x44` | 4 | payload size |
| `0x48` | 2 | CRC-16 over the payload only |

Paths take the form `./SM-R390_<face>_256x402/<name>`. Every unused byte of the
64-byte field is zero in both files.

The offset is **absolute**, not relative to the body. Since the directory itself
sits between the header and the first payload, adding or removing an entry
changes the position of *every* payload including the first.

### Packing

Both files are perfectly tight-packed:

```text
first payload offset == 0x20 + 74 × entry_count
entry[i].offset      == entry[i-1].offset + entry[i-1].size
last payload end     == EOF
```

Entry order is AOD → font bindings → glyph tables → `preview.bin` →
`setting.bin` → `styleN.bin`. Tight packing is what makes the Tier-2 edits in
§10 tractable: later offsets can be recomputed arithmetically.

## 5 · Metadata entries

### `setting.bin` — 256 bytes, 61 non-zero

| Offset | Size | Field | `00046` | `00106` |
| ---: | ---: | --- | --- | --- |
| `0x00` | 12 | marker | `"LQ_WF"` + NUL pad | same |
| `0x0C` | 4 | struct magic | `0x12345678` | `0x12345678` |
| `0x10` | 16 | face ID, ASCII | `"00046"` | `"00106"` |
| `0x20` | 16 | reserved | zero | zero |
| `0x30` | 4 | face version | 40000 | 40000 |
| `0x34` | 2 | u16 | 4 | 4 |
| `0x36` | 2 | u16 | `0xFFFF` | `0xFFFF` |
| `0x38` | 64 | name slot A | lead byte + `"SM-R390_00046_256x402"` | + `"SM-R390_00106_256x402"` |
| `0x78` | 64 | name slot B | byte-identical copy of A | byte-identical copy of A |
| `0xB8` | 72 | tail | zero | zero |

The two 64-byte name slots each begin with one `0x00` lead byte before the
NUL-terminated string, which is why the names appear at `0x39` and `0x79` in a
hex dump. `0x12345678` is not a file-type magic — the same sentinel opens style
entries and glyph tables. It marks a vendor struct.

`0x34` equals the style count in both files, but both files have four styles, so
this is **unknown**, not supported.

### Font bindings — 92 bytes

| Offset | Size | Field |
| ---: | ---: | --- |
| `0x00` | 1 | firmware font-family index |
| `0x01` | 71 | opaque |
| `0x48` | 16 | role name, NUL-padded ASCII |
| `0x58` | 4 | point size, u32 |

| Face | Entry | Role | Family | Size | Non-zero opaque bytes |
| --- | --- | --- | ---: | ---: | --- |
| `00046` | `font_0.bin` | `WF_DATE` | 2 | 20 | `+0x01`, `+0x03`, `+0x1C`, `+0x2E` all = 2 |
| `00046` | `font_1.bin` | `WF_WEEK` | 0 | 22 | none |
| `00106` | `font_0.bin` | `WF_COUNT` | 3 | 40 | `+0x01`, `+0x03`, `+0x1C`, `+0x2E` all = 3 |
| `00106` | `font_1.bin` | `WF_TEM` | 0 | 24 | none |
| `00106` | `font_2.bin` | `WF_TIME` | 0 | 20 | none |
| `00106` | `font_3.bin` | `WF_AM_PM` | 0 | 30 | none |

**These are not fonts.** No glyph outlines, no bitmaps, no font program of any
kind exists anywhere in either container. A record names a text role and requests
a size; the typeface is in watch ROM. Arbitrary font substitution is impossible
through this file.

The opaque pattern is consistent: when `family` is non-zero the same value
repeats at `+0x01`, `+0x03`, `+0x1C` and `+0x2E` and every other byte is zero;
when `family` is 0 the whole 71-byte region is zero. That suggests four
sub-structures each carrying a family selector, but with only two non-zero
examples it is **supported**, not proven.

### Glyph tables — the strings the watch draws

Eight locale entries per face:

| Offset | Size | Field |
| ---: | ---: | --- |
| `0x00` | 4 | magic `0x12345678` |
| `0x04` | 4 | locale ID |
| `0x08` | 4 | group count N |
| `0x0C` | 12 | reserved, zero |
| `0x18` | 8×N | descriptors: u32 byte length, u32 entry-relative offset |
| after | rest | concatenated UTF-8 text, no separators |

Locale IDs, identical in both faces: `font_cn0` = 0, `font_en` = 1,
`font_cn2` = 3, `font_fr` = 22, `font_ko` = 28, `font_pt_rPT` = 46,
`font_ja` = 47, `font_it` = 66.

The descriptor length field counts **bytes, not characters** — CJK groups are
9 bytes for 3 characters. Every table parses with zero unaccounted bytes and
strictly ascending text offsets.

`00046` carries 7 groups per locale: the weekday names. `00106` carries 10:

| Group | `en` | `ja` | `it` |
| ---: | --- | --- | --- |
| 0 | `0123456789` | `0123456789` | `0123456789` |
| 1 | `°` | `°` | `°` |
| 2 | `-` | `-` | `-` |
| 3 | `a.m.` | `午前` | `a.m.` |
| 4 | `p.m.` | `午後` | `p.m.` |
| 5 | `steps` | `歩` | `Passi` |
| 6 | `bpm` | `bpm` | `bpm` |
| 7 | `kcal` | `kcal` | `kcal` |
| 8 | `active time` | `活動時間` | `Tempo attiv.` |
| 9 | `1234` | `1234` | `3214` |

**This is the decisive join.** In `00106`, the four `Pair` widgets that carry no
sensor ID store `0x00010005`, `0x00010006`, `0x00010007`, `0x00010008` in their
third type-word. The low half-word is the glyph group index: 5 = *steps*,
6 = *bpm*, 7 = *kcal*, 8 = *active time* — exactly the four labels in the preview
raster, at exactly the four screen corners those widgets occupy. The am/pm widget
stores group 3 (*a.m.*) and the temperature composite stores group 1 (*°*).
`0xFFFF` in that field means "numeric, no static label".

Group 9 is not display text. It holds `"1234"` in seven locales and `"3214"` in
Italian — a field-order permutation for the date composite, encoded as a
pseudo-string.

Note `00046`'s `font_en.bin` group 0 is `"Monday "` — seven bytes including a
trailing space, which is in the asset, not a parse artifact.

## 6 · Style entries

`aod.bin` and every `styleN.bin` share one structure.

| Offset | Size | Field | `00046` | `00106` |
| ---: | ---: | --- | --- | --- |
| `0x00` | 4 | magic | `0x12345678` | `0x12345678` |
| `0x04` | 4 | widget count | 4–6 | 5–19 |
| `0x08` | 4 | widget bytes | 168–284 | 300–1,228 |
| `0x0C` | 4 | image bytes | — | — |
| `0x10` | 4 | unknown | `0x200` | `0x400` |
| `0x14` | 4 | image section offset | — | — |

Two equations hold in all ten style entries examined:

```text
image_section_offset == 24 + widget_bytes
entry_size           == image_section_offset + image_bytes
```

The word at `+0x10` is the only style-header field with no reading. It is
`0x200` in every `00046` entry and `0x400` in every `00106` entry — it tracks the
face, not the style, the size, or the widget count.

**Every raster in every style entry is referenced by at least one widget.** There
is no dead image data in either file and no hidden asset in the image sections.

## 7 · Widget records

A 36-byte fixed head followed by N 32-bit type-specific words.

| Offset | Size | Field | Status |
| ---: | ---: | --- | --- |
| `0x00` | 4 | widget type | proven |
| `0x04` | 4 | sequence / data-source ID | proven |
| `0x08` | 4 | opaque | zero in all 109 records |
| `0x0C` | 4 | `global_index << 16 \| record_size` | proven |
| `0x10` | 8 | opaque | zero in all 109 records |
| `0x18` | 2 | x, int16 | proven |
| `0x1A` | 2 | y, int16 | proven |
| `0x1C` | 2 | width, or x2 for Badge | proven |
| `0x1E` | 2 | height, or y2 for Badge | proven |
| `0x20` | 4 | type-dependent word A | see below |
| `0x24` | 4×N | type-specific words | see below |

Observed record sizes: 40, 44, 48, 52, 56, 60, 76, 100, 132 — all 4-byte
aligned, all fully accounted for by 36 + 4N with no leftover tail. Type census:

> **Later correction, from a wider corpus.** `0x1C`/`0x1E` hold a true width and
> height in *these two* files, but that does not generalise. Face `00079` stores
> width 1 for digit sprites whose frames are 52 px wide, and `00022` stores height
> 20 for frames that are 136 px tall. For any widget that addresses a raster, the
> raster's own dimensions are authoritative and the stored extent may be a
> placeholder; the fields only have to be read for Pair, Comp, Badge, Arc and
> LineBar, which address none.
>
> Two related corrections, same cause — both `00046` and `00106` happen to open
> every style with a full-panel background raster, which is not a rule. Faces
> `00022` (all styles) and `00108` (styles 0–3) carry **no** panel-sized raster and
> paint onto the watch's black panel, and almost every `aod.bin` opens with a digit
> sprite. So "raster 0 is the background" is false in general, and the panel
> geometry has to come from the container's declared name (`_256x402`) rather than
> from raster 0. Likewise a Static's image pointer is `+0x20` **only**: `words[0]`
> is `0x0` in every Static observed, and `0x0` is the background raster's own
> relative offset, so treating the type-word list as a pointer search aliases
> unrelated widgets onto the background.

| Type | Name | `00046` | `00106` |
| ---: | --- | ---: | ---: |
| 1 | Static | 6 | 9 |
| 2 | Hand | 14 | — |
| 3 | Sprite | — | 24 |
| 5 | Pair | 8 | 36 |
| 7 | Badge | — | 4 |
| 13 | Comp | — | 8 |

Three readings of `+0x20` were derived from one record each, then tested against
every matching record in both files.

### Hand: `+0x20` is a rotation pivot — **proven, 14/14**

Reading `+0x20` as `(pivot_y << 16) | pivot_x` and adding it to the record's
`x,y` lands on `(128, 201)` — the exact centre of the 256 × 402 panel — for every
Hand record in the corpus.

| Entry | Seq | x, y | `+0x20` | pivot | sum |
| --- | ---: | --- | --- | --- | --- |
| `style0` | 1 | 120, 125 | `0x004C0008` | 8, 76 | **128, 201** |
| `style0` | 9 | 120, 85 | `0x00740008` | 8, 116 | **128, 201** |
| `style0` | 13 | 120, 81 | `0x00780008` | 8, 120 | **128, 201** |

The first type-word is `0x01680000` in all 14 records: `0x168` = 360, the sweep
in degrees. The second is the hand sprite's image-section offset.

Hand lengths above the pivot are 76, 116 and 120 px, and the 120 px sprite is the
thin red one. With the preview showing an hour hand pointing left of 10 and a
long minute hand upper-right, that orders as hour = seq 1, minute = seq 9,
second = seq 13.

### Sprite: `+0x20` is a frame count — **proven, 24/24**

`+0x20` exactly equals the number of trailing type-words, and each of those words
is a valid image-section offset. The counts are self-evidently right:

| Seq | Frames | Unique rasters | Reading |
| ---: | ---: | ---: | --- |
| 2 | 3 | 3 | hour tens — 0, 1, 2 |
| 3 | 10 | 10 | hour ones — 0–9 |
| 10 | 6 | 6 | minute tens — 0–5 |
| 11 | 10 | 10 | minute ones — 0–9 |
| 69 | 24 | 21 | weather icon set (3 frames reuse a raster) |

A digit sprite is a frame table, not an atlas: each frame is a separate 50 × 90
image record.

### Pair: `+0x20` is an anchor mode — **proven, 9/9 in `00106`**

Mode 1 always accompanies a non-negative `x`; mode 3 always accompanies a
negative `x`. Read as: 1 = anchor left, `x` is an inset from the left edge;
3 = anchor right, `x` is a negative inset from the right edge.

| Seq | x, y | Mode | Corner | Preview shows |
| ---: | --- | ---: | --- | --- |
| 29 | 16, 20 | 1 | top-left | `3457` |
| 41 | −17, 20 | 3 | top-right | `89` |
| 48 | 16, 328 | 1 | bottom-left | `350` |
| 71 | −14, 328 | 3 | bottom-right | `53` |

Combined with the label groups in §5, that fixes seq 29 = steps, 41 = bpm,
48 = kcal, 71 = active time, 5 = am/pm. Both `00046` Pair widgets use mode 0 with
positive coordinates, so mode 0 reads as plain absolute placement.

Note this corrects two plausible-looking guesses: 41 is *not* calories and 48 is
*not* battery, even though those are common IDs elsewhere. Position plus label
group settles it.

### Badge: geometry is a line segment — **proven for the one record**

The single Badge stores `x=14, y=256, w=242, h=256`. As width/height that is
nonsense — 242 × 256 overflows the panel. As a second endpoint `(242, 256)` it is
a horizontal segment from `(14, 256)` to `(242, 256)`, symmetric in a 256-wide
panel, with a final type-word of 4 for thickness and three ARGB words for colour.
That is the divider rule under the clock.

### Comp: a composite of sub-fields — **partially supported**

100-byte records holding 16 type-words that decompose into four triplets plus
four trailing words. The packing `(glyph_group << 16) | sequence_id` is confirmed
by the temperature composite: its first triplet is `0xFFFF003E` — sequence 62, no
group — followed by `0x00000001`, and glyph group 1 is `°`. That renders `25°`,
which is what the preview shows. The date composite's triplets reference group 2
(`-`) between two numeric fields, matching `2023-12-28`.

The precise prefix-versus-suffix role of each word in a triplet is **not** pinned
down. Treat Comp internals as read-only.

## 8 · Rasters

A 12-byte header, raw row-major pixels, a 4-byte trailer. No compression, no
palette, no filtering.

| Offset | Size | Field |
| ---: | ---: | --- |
| `0x00` | 2 | width |
| `0x02` | 2 | height |
| `0x04` | 2 | format |
| `0x06` | 2 | reserved, zero in all 171 records |
| `0x08` | 4 | data size == `w × h × bpp + 4` |
| `0x0C` | var | pixels |
| after | 4 | opaque trailer |

Formats: `0x0082` = RGB565, 2 bytes/px, little-endian half-word with R in bits
15–11, G in 10–5, B in 4–0. `0x0080` = the same half-word plus one alpha byte,
3 bytes/px.

**All 171 records in both files have a trailer of exactly four bytes** —
`data_size − w × h × bpp == 4` with no exceptions.

Image references in widget records are byte offsets **relative to the style's
image-section start**, never absolute file offsets. That is why a style entry can
be relocated wholesale without touching a single widget word.

### Inventory

| Face | Rasters | RGB565 | RGB565+A | Distinct sizes |
| --- | ---: | ---: | ---: | ---: |
| `00046` | 24 | 8 | 16 | 7 |
| `00106` | 147 | 63 | 84 | 5 |

`00046`: 5 × 256×402 backgrounds, 4 × 178×280 previews, and the hand sprites —
16×81 (hour), 16×121 (minute), 16×138 (second), plus 16×124 and a 16×16 centre
cap in the AOD style.

`00106`: 4 × 256×402 backgrounds, 4 × 178×280 previews, 84 × 26×26 weather icons
(21 unique per style × 4 styles), 50 × 50×90 digits, 5 × 30×90 colon separators.
The AOD style has no background raster at all — only ten digits and a colon.

The visual report renders all 171 decoded rasters with per-image dimensions,
format, transparency percentage and section offset. The full-resolution PNGs are
also written to disk by the analyzer (see §13).

RGB565 quantisation is lossy and irreversible — 8-bit channels are discarded to
5/6/5. The extracted PNGs are exact reconstructions of what is *stored*, not of
what was authored.

## 9 · Theme mechanics

The two faces solve variants in completely different ways, and this determines
what a safe edit looks like.

### `00046` — variants live in the pixels

| Entry | Size | Widget bytes differing vs `style0` | Background |
| --- | ---: | ---: | --- |
| `style0.bin` | 222,516 | — | 256×402 RGB565, white dial |
| `style1.bin` | 222,516 | **0** | 256×402 RGB565, pink dial |
| `style2.bin` | 222,516 | 6 | 256×402 RGB565, black dial |
| `style3.bin` | 325,428 | 12 | 256×402 **RGB565+A**, dark dial |

`style0` and `style1` have **byte-identical widget sections** — they differ only
in background pixel data.

`style2` changes 6 bytes: the ARGB word of the two `Pair` widgets, flipping text
from black to white for the dark dial.

`style3` changes 12: the same colour words plus all three Hand image offsets. That
last part is *forced*. Its background is RGB565+A rather than RGB565, so the
image section carries an extra alpha plane of exactly 256 × 402 = 102,912 bytes —
and `325,428 − 222,516 = 102,912`. Every raster after the background shifts by
that amount, so every widget word pointing past it had to be rewritten. This is
the format's pointer dependency demonstrated by the vendor's own asset.

### `00106` — variants are 21 bytes

All four styles are 345,592 bytes and differ in exactly 21 widget-section bytes,
concentrated in seven 32-bit words:

- `Comp` w5 `+0x58` — accent colour of the temperature composite
- `Comp` w12 `+0x58` — accent colour of the date composite
- `Badge` w13 `+0x20`, `+0x24`, `+0x28`, `+0x2C` — divider colours
- `Pair` w14 `+0x24` — am/pm colour

| Style | Accent | File offset of the `Comp` w5 accent word |
| --- | --- | --- |
| `style0.bin` | `#75D8D6` teal | `0x079AC8` |
| `style1.bin` | `#D1E953` lime | `0x0CE0C0` |
| `style2.bin` | `#F6CAB8` peach | `0x1226B8` |
| `style3.bin` | `#B8AEFF` lavender | `0x176CB0` |

Every `00106` background is 256×402 RGB565 with only **two** distinct colours:
black plus the theme accent used for the divider line. Those four offsets plus
the matching Badge and Pair words and the two-colour background are the entire
theme palette — a complete recolour that changes no length and breaks no pointer.

## 10 · Plausible manipulations

Ranked by how much of the file has to move. The governing constraint is from §4:
payload offsets are absolute and the header CRC covers the directory.

### The checksum obligation, in order

```text
1. patch payload bytes
2. recompute that entry's CRC-16          -> directory record +0x48
3. if any length changed:
     rewrite every later entry offset     -> directory record +0x40
     rewrite header payload_size          -> header +0x08
4. recompute header CRC-16 over 0x20..EOF -> header +0x10
5. reparse and verify both style equations from §6
```

### Tier 1 — same length, no pointer touched

One entry CRC plus the header CRC. Nothing moves. Structurally safe by
construction.

| Manipulation | Where | Confidence |
| --- | --- | --- |
| Recolour text or accent | ARGB type-word of a `Pair`/`Badge`/`Comp` record; for `00106` the four offsets in §9 | device-proven |
| Move a widget | `+0x18`/`+0x1A` int16 `x,y`; respect the anchor mode at `+0x20` — negative `x` only with mode 3 | device-proven |
| Repaint a raster, same dimensions | pixel region; re-encode to the same `format` and byte count, keep the 4-byte trailer | device-proven |
| Re-pivot a clock hand | `+0x20` as `(pivot_y<<16)\|pivot_x`; keep `x+pivot_x = 128` and `y+pivot_y = 201` or the hand orbits off-centre | schema-proven, untested |
| Swap a static label | glyph-group index in the third `Pair` type-word; must name an existing group in *every* locale table | schema-proven, untested |
| Retarget a raster reference | any type-word holding a section-relative offset; must equal the start of a real image record in the same style | schema-proven, untested |
| Bump the face version | `setting.bin +0x30`; lowering it makes the companion app offer an update again | observed |

### Tier 2 — length changes, tight repack required

Both files are perfectly tight-packed, which is what makes this tractable.
Style-internal length changes additionally shift every image-section offset after
the edit point — `00046`'s `style3` is the vendor's own worked example.

| Manipulation | What must be rewritten | Risk |
| --- | --- | --- |
| Replace a raster at a different size or format | image header `width`/`height`/`format`/`data_size`; style `image_bytes`; every later section offset in every widget word; entry size; every later entry offset; header size; both CRCs | medium — proven for background and Sprite on `00106` |
| Remove the final widget of a style | style `widget_count`, `widget_bytes`, `image_section_offset`; entry size; later entry offsets; CRCs. Section-relative offsets are unaffected because the image section moves as a block | medium — proven once on `00106` |
| Append a duplicate widget | as above, plus a new global index that collides with no opaque word already holding that value | high — never device-tested |
| Remove a non-final widget | as above, plus renumbering the high half of `+0x0C` in every later record; any unknown field referencing one of those indices breaks silently | high — never device-tested |
| Add or remove a directory entry | the directory grows or shrinks by 74 bytes, so *every* payload offset changes including the first | high — untested |

### Tier 3 — not plausible from this file

| Attempt | Why it cannot work |
| --- | --- |
| Change a typeface | No font program exists in either container (§5). Glyphs are in watch ROM. |
| Add a new sensor or metric | Sequence IDs are firmware-defined. A value the watch does not publish renders as nothing; the file cannot introduce a data source. |
| Change panel geometry | 256 × 402 is baked into every background raster, the Hand pivot arithmetic, the container name, `setting.bin`, and the filename the plugin matches on. |
| Global search-and-replace on an integer | Type-words are not a uniform pointer array. The same 32-bit value can be an image offset, an ARGB colour, an angle, a frame count, a glyph index, or a mode. Only per-type, per-offset edits are sound. |
| Recover source artwork | RGB565 quantisation is irreversible (§8). |
| Reduce file size meaningfully | No compression stage, and no unreferenced raster in either file (§6). |

### The two traps

1. **The header CRC covers the directory.** Fixing an entry CRC without
   recomputing the header CRC leaves a file that fails validation.
2. **Image references are section-relative, not absolute.** Moving a whole style
   needs no widget edits at all; changing anything *inside* a style's image
   section before the last raster needs all of them.

## 11 · Fields this analysis established

The container, directory, style, widget and image layers are described in full
above. The rows below are the individual fields whose reading this work established
or pinned to a specific verification count, gathered in one place because these are
the findings the implementation actually relies on.

| Field | What it holds, and what pins it |
| --- | --- |
| Hand `+0x20` | `(pivot_y<<16)\|pivot_x`, verified 14/14 against the panel centre `(128, 201)`; first type-word is a 360° sweep constant |
| Sprite `+0x20` | Frame count, verified 24/24; equals the length of the image-offset array in every record |
| Pair `+0x20` | Anchor mode: 1 = left inset, 3 = right inset with negative `x`, verified 9/9; mode 0 = absolute |
| Pair label field | Third type-word low half is a glyph-group index; `0xFFFF` = numeric. Confirmed against rendered labels |
| Badge geometry | Start/end points plus thickness, confirmed numerically on the one available record, with the thickness word identified |
| Comp | Four triplets + four trailing words; `(group<<16)\|seq` packing confirmed via the temperature composite |
| Glyph descriptors | A **byte** count, not a character count |
| Glyph group 9 | A field-order permutation (`"1234"`, Italian `"3214"`), not display text |
| `setting.bin` `0x38`/`0x78` | Two 64-byte slots, each a lead byte plus a NUL-terminated name, byte-identical |
| Style `+0x10` | Tracks the face, not the style or size: `0x200` for all `00046`, `0x400` for all `00106`. Observed range `0x200`–`0x500` |
| Font binding opaque region | Family value mirrored at `+0x01`, `+0x03`, `+0x1C`, `+0x2E`; all zero when family is 0 |
| Sequence IDs 41, 48 | Position + label group give 41 = bpm and 48 = kcal in `00106` |
| `style3` alpha cascade | The RGB565→RGB565+A background adds exactly 102,912 bytes and forces all three Hand offsets |

**One case these two files cannot show.** Every one of the 109 widget records here is
4-byte aligned with zero leftover tail, so nothing in this derivation exercises a
record carrying a two-byte tail after its complete 32-bit words. The parser keeps a
tail-preserving path regardless: absence across two containers is not evidence that
no face has one, and a writer that dropped such a tail would corrupt it silently.

Per-face totals, for reference: `00046` has 17 entries, 4 styles, 28 widget records
and 24 rasters; `00106` has 19 entries, 4 styles, 81 widget records and 147 rasters.

## 12 · What remains unknown

Every item is preserved byte-for-byte by the analysis and should be by any
writer.

| Field | Observed | Status |
| --- | --- | --- |
| Header `+0x12`, 2 B | zero in both files | possibly the high half of a 32-bit checksum; never non-zero |
| Style header `+0x10` | `0x200` / `0x400` | tracks the face; purpose unknown |
| Image trailer, 4 B × 171 | present on every record | always exactly four bytes; contents uninterpreted |
| Widget `+0x08`, `+0x10`, `+0x14` | zero in all 109 records | cannot be characterised from data that never varies |
| `Comp` triplet grammar | 4 triplets + 4 trailing words | packing confirmed; per-word role not |
| `Arc` (16), `LineBar` (17) | absent from these faces | no evidence available here |
| Hand rotation modes | only the 360° constant appears | no variation to learn from |
| Sequence-ID namespace | IDs 0–71 observed | firmware-defined; readings here come from previews and geometry |
| `setting.bin +0x34` | 4 in both files | equals the style count in two samples that both have four styles |

## 13 · Reproducing this analysis

The two scripts that produced it are in [`tools/`](../tools/) — standard library
only, and they take any container or package rather than the two faces this
document happens to describe:

```sh
python3 tools/analyze_container.py corpus/packages --out out
python3 tools/build_report.py out --output out/anatomy.html
```

The report is the visual companion to this document — field diagrams, layout
ribbons and the whole decoded asset gallery on one self-contained HTML page. What
the scripts do, what they refuse to guess at, and where each output lands is in
[`tools/README.md`](../tools/README.md).

The analyzer shares no code with `:core:format`, so agreement between the two is
independent corroboration rather than a tautology. Run over the whole live
catalogue it reproduces the numbers this project relies on elsewhere: 99
containers, 4,038 widget records, 7,720 rasters, every CRC matched and every file
rebuilt byte-identically. The zero-residual class census in §1 is recomputed on
every run and the exit status reflects it, so a regression in the parse cannot
pass silently.

## 14 · Related work

**[Ahmadjerj/galaxy-fit3-parser](https://github.com/Ahmadjerj/galaxy-fit3-parser)**
— an independent, read-only Python parser for this same OPPO container: it walks
the `.bin`, extracts the RGB565 and RGB565+A rasters, and reconstructs whole
rendered previews, including the multi-style and AOD variants, the font bindings
and the locale-aware glyph groups. It reads all eight widget types this document
describes (Static, Hand, Sprite, Pair, Badge, Comp, Arc, LineBar) and, being a
reader, never writes a container back.

It is worth knowing about for two reasons. It reaches `Arc` and `LineBar`, which
[§12](#12-what-remains-unknown) records as absent from the two files derived here.
And it is the only other public implementation of this format this project is aware
of, which makes it the obvious second opinion on anything labelled *supported*
rather than *proven*.

No code, data or documentation from it is used here, and nothing in this document
is derived from it — the derivation above predates the reference and comes from the
bytes and the [`tools/`](../tools/) analyzer. The two have not been run against each
other, so this is a pointer, not a corroboration: treat any disagreement between
them as an open question about the format rather than as a verdict on either one.

---

**Scope.** The derivation above rests on two containers from one watch model.
Structural conclusions are strong — they rest on arithmetic invariants that hold
across every matching record and on byte-identical reconstruction, and the
catalogue-wide sweep has since confirmed them. Semantic readings of
firmware-defined IDs are inferences, however well corroborated by the embedded
previews. One device result does not establish universal firmware compatibility.

Not affiliated with or endorsed by Samsung or OPPO. Use only watch-face files you
are authorized to inspect and modify.
