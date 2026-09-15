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
12. [Fields this later analysis resolved, and what remains](#12--fields-this-later-analysis-resolved-and-what-remains)
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
constants; nothing in the file names them. The initial two-file derivation used
widget geometry and the rendered `preview.bin`, and a later pass over the whole
catalogue confirmed which reading each ID supplies and which widget types accept
it. A user-facing name is left absent where no available evidence contains one.

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
`2023-12-28`, `350 kcal`, `53 floors`. Every one of those is traceable to a
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
| `0x12` | 14 | reserved; no effect on install | all zero | all zero |

CRC-16/CCITT-FALSE: polynomial `0x1021`, init `0xFFFF`, no reflection, no final
XOR — equivalent to `binascii.crc_hqx(data, 0xFFFF)`.

**The header CRC covers bytes `0x20 … EOF`, which includes the directory.** A
canonical writer must therefore update it after changing an entry offset, size,
or per-entry CRC. The repository's validator enforces both CRC layers. The two
watch does not read either CRC field when it installs a face, but that
permissiveness is not a reason to emit a noncanonical package.

### Directory records — 74 bytes

| Offset | Size | Field |
| ---: | ---: | --- |
| `0x00` | 64 | path, NUL-padded UTF-8 |
| `0x40` | 4 | payload offset, absolute from file start |
| `0x44` | 4 | payload size |
| `0x48` | 2 | CRC-16 over the payload only |

Paths take the form `./SM-R390_<face>_256x402/<name>`. Every unused byte of the
64-byte field is zero in both files. The extractor's C-string use stops at the
first NUL: the UTF-8 bytes before it affect the destination, that terminator is
structural, and every later padding byte has no effect. Writers must still zero
the padding.

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
| `0x30` | 4 | face version, little-endian `i32` formatted as signed decimal | 40000 | 40000 |
| `0x34` | 1 | selectable style count | 4 | 4 |
| `0x35` | 1 | default style index | 0 | 0 |
| `0x36` | 1 | copied manager property, unused by normal renderer | `0xFF` | `0xFF` |
| `0x37` | 1 | copied manager property, unused by normal renderer | `0xFF` | `0xFF` |
| `0x38` | 64 | name slot A | lead byte + `"SM-R390_00046_256x402"` | + `"SM-R390_00106_256x402"` |
| `0x78` | 64 | name slot B | byte-identical copy of A | byte-identical copy of A |
| `0xB8` | 72 | tail | zero | zero |

The two 64-byte name slots each begin with one `0x00` lead byte before the
NUL-terminated string, which is why the names appear at `0x39` and `0x79` in a
hex dump. `0x12345678` is not a file-type magic — the same sentinel opens style
entries and glyph tables. It marks a vendor struct.

The watch reads the numeric ASCII ID, the version, the style count and the
default style exactly as shown. If it already has a saved style for this face,
that choice wins over `+0x35`. It copies `+0x36/+0x37` into its own record and
never reads the copies. No other byte of the 256 is read; emit the catalogue's
markers, names, `0xFF,0xFF` properties and zero tail for compatibility anyway.

### Font bindings — 92 bytes

| Offset | Size | Field |
| ---: | ---: | --- |
| `0x00` | 72 | `family_for_language[72]` selector bytes |
| `0x48` | 16 | descriptive role name, NUL-padded ASCII; not read by the resolver |
| `0x58` | 4 | requested pixel size, u32 |

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

The firmware uses selector byte `+0x01` first when it is nonzero; otherwise it
indexes this table by its current language ID. Selector 0 maps supported sizes
to MiSans. Selectors 1, 2 and 3 are the firmware's Roboto Regular, Bold and
Medium tables, and selector 15 at size 32 is LED Digital. Selectors 4, 5 and
7–12 pick fonts that carry no name anywhere in the evidence; selector 6 selects
nothing. Unsupported selector/size pairs fall back rather than failing the
widget. A scratch writer should use selector 0 with a supported even size such
as 20, unless it deliberately copies a corpus-proven selector/size combination.

The firmware's language table covers IDs 0–68; its key joins include 0=`cn0`,
1=`en`, 2/3=`cn2`, 22=`fr`, 28=`ko`, 46=`pt_rPT`, 47=`ja`, 65=`it`, 66=`hi`,
67=`in`, and 68=`vi`. Binding bytes 69–71 are beyond that range and are zero in
all 198 catalogue bindings; keep them zero.

The producer's numeric metadata is not the same enum: it writes 66 in
`font_it.bin` and populates binding slot 66, even though the firmware's table
maps Italian to 65. The firmware never reads a dictionary's header locale ID.
Moreover, all 198 corpus bindings with any language-specific byte populated
also have byte 1 populated, so the resolver's byte-1-first rule prevents every
other populated slot from being consulted. There are zero corpus bindings with
byte 1 zero and another slot nonzero. For a new face, set the intended family in
byte 1 and leave the remaining 71 bytes zero unless a language-specific device
test establishes a stronger rule.

The decoded selector/size branches are exact; “linked” is narrower than saying
every unnamed fixed pointer in that group has the named typeface:

| Selector | Family | Dedicated sizes | Other sizes |
| ---: | --- | --- | --- |
| 0 | MiSans static enum | 10, 12, 16, 18, 20, 22, 24, 26, 28, 32, 34, 36, 40, 44, 48 | MiSans-30/common fallback |
| 1 | Roboto Regular-linked | 12, 14, 16, 20, 22, 24, 26, 28, 30, 32, 34, 36, 40, 48, 50; runtime slot 86 | selector-1 fallback |
| 2 | Roboto Bold-linked | 20, 22, 24, 26, 28, 30, 32, 34, 36, 40, 42, 44, 48, 60 | selector-2 fallback |
| 3 | Roboto Medium-linked | 16, 20, 23, 24, 28, 30, 32, 36, 40, 44, 48; runtime slot 86 | selector-3 fallback |
| 4 | compiled family; name absent | 40 | selector-4 fallback |
| 5 | compiled family; name absent | 24, 38 | selector-5 fallback |
| 7 | compiled family; name absent | 40 | selector-7 fallback |
| 8 | compiled family; name absent | 44 | common fallback |
| 9 | compiled family; name absent | 20, 24 | selector-9 fallback |
| 10 | compiled family; name absent | 24 | selector-10 fallback |
| 11 | compiled family; name absent | 30 | common fallback |
| 12 | compiled family; name absent | 34 | selector-12 fallback |
| 15 | LED Digital | runtime slot 32 | selector-12 fallback |
| 6, 13, 14, other | not dispatched | none | common fallback |

### Glyph tables — the strings the watch draws

Eight locale entries per face:

| Offset | Size | Field |
| ---: | ---: | --- |
| `0x00` | 4 | magic `0x12345678` |
| `0x04` | 4 | producer locale ID; the watch ignores it |
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

To fetch item *n* the watch reads the descriptor at `(n + 3) * 8`, takes the
`(length, absolute_offset)` pair, and reads the payload. It checks neither the
marker, the locale ID, the stored count nor the reserved words first, and it does
not bound the requested index against the stored count. Whatever reads the string
then requires it to be 1..64 UTF-8 bytes; the audited corpus contains 6,134 items
ranging from 1 to 27 bytes. Writers must keep the stronger table bounds, the
item-length rule and exact exhaustion even though the lookup itself is this
permissive.

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
| 8 | `floors` | `活動時間` | `Tempo attiv.` |
| 9 | `1234` | `1234` | `3214` |

**This is the decisive join.** In `00106`, the four `Pair` widgets that carry no
sensor ID store `0x00010005`, `0x00010006`, `0x00010007`, `0x00010008` in their
third type-word. The low half-word is the glyph group index: 5 = *steps*,
6 = *bpm*, 7 = *kcal*, 8 = *floors* — exactly the four labels in the preview
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
| `0x10` | 4 | numbered-font count shifted left 8 | `0x200` | `0x400` |
| `0x14` | 4 | image section offset | — | — |

Two equations hold in all ten style entries examined:

```text
image_section_offset == 24 + widget_bytes
entry_size           == image_section_offset + image_bytes
```

The renderer reads byte `+0x11` and loads exactly that many consecutive 92-byte
bindings named `font_0.bin` through `font_(count-1).bin`; accepted counts are
1–10. Across all 509 selectable/AOD styles in the 99-face corpus, the whole word
equals `numbered_font_file_count << 8` with no exception. Bytes `+0x10` and
`+0x12/+0x13` have no effect.

The header validator itself only requires nonzero widget count and widget-byte
length. Image lookup computes `0x18 + widget_bytes + relative_offset`; it does
not use `+0x14`, and does not bound each image against `+0x0C`. Those stronger
equalities remain mandatory writer checks.

**Every raster in every style entry is referenced by at least one widget.** There
is no dead image data in either file and no hidden asset in the image sections.

## 7 · Widget records

A 24-byte common prefix followed by type-specific bytes. Most producer records
are 4-byte aligned, but the loader advances by the low `u16` at `+0x0C` and the
50-byte LineBar proves alignment is not a parser rule.

| Offset | Size | Field | Status |
| ---: | ---: | --- | --- |
| `0x00` | 4 | widget type | proven |
| `0x04` | 4 | live-data source for live types; Static registers only its low `u16`; Animation and Comp do not consume it | proven |
| `0x08` | 4 | never read | zero in all 4,034 corpus records |
| `0x0C` | 4 | `global_index << 16 \| record_size` | proven |
| `0x10` | 8 | no rendered effect; type 9 copies low byte `+0x10` into child-object `user_data`, but its callback/updater never consumes it | zero in all 4,034 corpus records |
| `0x18` onward | variable | type-specific layout | see below |

All multi-byte values are little-endian. Signed coordinates, endpoints, angles
and spacing use two's-complement `i16`/`i8`. In the packed `+0x0C` word, bits
0–15 are the record byte size and bits 16–31 are the global index. Widget
colours written as `0xAARRGGBB` are consequently stored as bytes `BB GG RR AA`.
For Pair, vector Arc, Badge and Comp the watch consumes only the RGB components
(on disk `BB GG RR`) and ignores the stored alpha byte. Keep
the producer's canonical `AA` value for compatibility, but changing it alone
does not change the rendered colour.
Other per-byte selectors and booleans are described by their exact type schema;
there is no generic array of 32-bit “widget words.”

The two initial faces contain sizes 40, 44, 48, 52, 56, 60, 76, 100 and 132.
The full corpus adds 50, 64, 80 and 140. Every observed byte is accounted for
by its type schema. Type census:

> **Later correction, from a wider corpus.** `0x1C`/`0x1E` hold a true width and
> height in *these two* files, but that does not generalise. Face `00079` stores
> width 1 for digit sprites whose frames are 52 px wide, and `00022` stores height
> 20 for frames that are 136 px tall. For any widget that addresses a raster, the
> raster's own dimensions are authoritative and the stored extent may be a
> placeholder. Pair, Comp, Badge, vector Arc, image Arc and LineBar constructors
> do consume explicit geometry; image Arc and LineBar additionally reference a
> raster.
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

All 17 widget types are now accounted for:

| Type | Constructor role | Corpus samples |
| ---: | --- | ---: |
| 1 | Static raster | 681 |
| 2 | rotating Hand raster | 469 |
| 3 | live Sprite frame table | 1,518 |
| 4 | timed Animation frame table | 0; layout known |
| 5 | Pair/live text | 734 |
| 6 | vector Arc | 75 |
| 7 | progress line (historical `Badge`) | 84 |
| 8 | reserved/no-op | 0 |
| 9 | source-75 three-slot complication group | 0; layout known |
| 10–12 | reserved/no-op | 0 |
| 13 | four-part text Comp | 427 |
| 14–15 | reserved/no-op | 0 |
| 16 | image-backed Arc | 30 |
| 17 | image-backed LineBar | 16 |

Types 8, 10–12 and 14–15 are accepted and then do nothing at all — they build
no object and never update — rather than merely lacking samples. Type 4 consumes `position`, mode, frame count, repeat
count, timer period and a variable image-offset table; its mode word is read
but overwritten before playback. A temporary `0xFF`-to-`-1` repeat conversion
is overwritten too, so raw repeat `0xFF` survives as finite 255 rather than an
infinite mode. Type 9 requires source 75 and consumes only
global index, the low byte at `+0x10`, and signed `x/y`; the low byte becomes
child-object `user_data`, but neither its click callback nor updater consumes
it, and update validity masks come from global runtime state rather than the
record. The inert handlers read no raw-record byte; `0x0E` is their loader-
proven minimum, while `0x10` is the safer writer convention because it retains
the ordinary global-index halfword. No vendor producer sample exists for type
4, type 9, or an inert type. The exact byte schemas for every observed
type are implemented and JVM-tested in the format layer, and the per-type
behaviour below supersedes the original two-face guesses.

The live-data source at common offset `+0x04` selects a reading the watch owns; a
package cannot define a new one. The map is:

| ID | Value the watch supplies |
| ---: | --- |
| 0 | constant zero |
| 1; 2/3 | hour; hour tens/units |
| 5 | AM/PM |
| 9; 10/11 | minute; minute tens/units |
| 13; 14/15 | second; second tens/units |
| 17 | weekday |
| 18/19/20 | day; day tens/units |
| 21; 22/23 | month; month tens/units (Sprite 21 is zero-based artwork) |
| 24; 25/26/27/28 | year; year thousands/hundreds/tens/units |
| 29 | steps |
| 37 | battery percentage |
| 41 | heart rate |
| 48 | calories |
| 55 | distance |
| 62 | weather temperature |
| 69 | weather icon/condition index |
| 70 | fourth daily-activity goal metric; numeric behaviour known, UI name absent |
| 71 | active time/minutes |
| 72 | floors |
| 102 | blood-oxygen/SpO2 percentage |
| 104 | sleep duration |
| 106/107; 109/110 | second-zone hour tens/units; minute tens/units |
| 115 | water intake |
| 116 | configured secondary city/time-zone name |
| 117/118/119 | current local hour plus 2/4/6, with AM/PM rollover |
| 120 | weather-description string |
| 122/123/124 | second-zone month/weekday/day |
| 125 | configured secondary-zone AM/PM |

Each constructor accepts only a subset of this table. Hand accepts 1, 9, 13,
17, 21, 29, 37, 41, 48, 70 and 71. Vector/image Arc accept 29, 37, 41, 48,
70, 71, 104 and 115. Badge and LineBar accept the same set without 104. Pair,
Sprite and Comp have their own broader source switches and formatting rules.
Sprite's complete non-default switch is 1, 2, 3, 5, 9, 10, 11, 13, 14, 15,
17, 19–23, 25–29, 37, 41, 48, 69–71, 106, 107, 109, 110, 115, 117–119 and
125; 9 and 13 only log that 60-picture minute/second artwork is unsupported,
while the other listed IDs reach selector logic.

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

### Pair: `+0x20` is an alignment code — **proven, 9/9 in `00106`**

Mode 1 always accompanies a non-negative `x`; mode 3 always accompanies a
negative `x`. Read as: 1 = anchor left, `x` is an inset from the left edge;
3 = anchor right, `x` is a negative inset from the right edge.

That reading is right for these nine records and is the special case of a
general rule — see [the alignment fields](#alignment-a-widget-can-be-positioned-against-another-widget)
below. The halfword is an alignment *code*, the one beside it names the widget
the offsets are measured from, and every face in these two containers happens to
align everything to a full-panel background sitting at the origin, which is
exactly when "inset from the panel edge" and "inset from the target's edge" are
the same arithmetic.

| Seq | x, y | Mode | Corner | Preview shows |
| ---: | --- | ---: | --- | --- |
| 29 | 16, 20 | 1 | top-left | `3457` |
| 41 | −17, 20 | 3 | top-right | `89` |
| 48 | 16, 328 | 1 | bottom-left | `350` |
| 72 | −14, 328 | 3 | bottom-right | `53` |

Combined with the label groups in §5, that fixes seq 29 = steps, 41 = bpm,
48 = kcal, 72 = floors, 5 = am/pm. Both `00046` Pair widgets use mode 0 with
positive coordinates, so mode 0 reads as plain absolute placement.

A later pass over the whole catalogue independently confirms those readings and
also settles 37 = battery and 71 = active time. Position plus label group had
already settled the four IDs present here.

### Alignment: a widget can be positioned against another widget

Four types carry two extra halfwords — an alignment code and the global index of
another widget — at `+0x1C`/`+0x1E` for Static and Hand, and at `+0x20`/`+0x22`
for Pair and Comp. `0xFFFF` in the code disables the pair and makes `x`/`y`
panel coordinates. Anything else makes them **offsets from the named widget's
rectangle**:

| Code | Where the offsets are measured from |
| ---: | --- |
| 0, 1 | the target's top-left corner |
| 2 | the middle of the target's top edge, so `x` shifts a centred widget |
| 3 | the target's top-right corner, so a negative `x` insets it |

Two things make this field easy to get wrong, and both have been got wrong here:

* **It is always in use.** Not one of the 2,311 Static, Hand, Pair and Comp
  records in the catalogue sets the disabling sentinel. Codes 0, 1, 2 and 3 are
  the only ones that occur, 1,537 / 651 / 37 / 86 respectively.
* **A reference either names widget 0 or names nothing.** 1,914 records name
  index 0, which in those styles is always a full-panel background raster at the
  origin. The other 397 store 5, 10, 20, 30 or 40, which match no record in the
  style at all — a producer convention, not damage. An unresolvable reference
  falls back to the whole face, so it renders the same as panel placement, and a
  writer must leave those values alone rather than "fixing" them into references.

The consequence for editing is in
[`editing.md`](editing.md): a structural edit that renumbers records has to
renumber these references with them, and a widget that others are positioned
against cannot simply be removed.

### Badge: geometry is a line segment — **proven for the one record**

The single Badge stores `x=14, y=256, w=242, h=256`. As width/height that is
nonsense — 242 × 256 overflows the panel. As a second endpoint `(242, 256)` it is
a horizontal segment from `(14, 256)` to `(242, 256)`, symmetric in a 256-wide
panel, with a final type-word of 4 for thickness and three ARGB words for colour.
That is the divider rule under the clock.

### Comp: four explicit text mini-programs — **proven**

Each 100-byte record contains four 12-byte programs at `+0x24`, `+0x30`,
`+0x3C`, and `+0x48`. For a program beginning at `B`:

| Offset | Field |
| ---: | --- |
| `B+0x00` | u16 live-data source; `0xFFFF` disables the dynamic fragment, not fixed text |
| `B+0x02` | u16 fixed prefix dictionary index A, or `0xFFFF` |
| `B+0x04` | u16 fixed suffix dictionary index B, or `0xFFFF` |
| `B+0x06` | u16 dynamic dictionary base; `0xFFFF` disables dynamic output in **both** modes |
| `B+0x08` | u8 dynamic mode: 0 dictionary lookup, nonzero numeric formatting |
| `B+0x09` | u8 numeric format selector |
| `B+0x0A` | u16 ignored padding |

Each part joins **fixed-A, dynamic, fixed-B** (prefix, value, suffix). The update
loads both fixed strings before checking the source and base sentinels at
`0x2C10795E..976`; those gate dynamic output, even numeric mode. The final
`snprintf` argument setup at `0x2C107CAE..CB6` passes buffer slots **0, 2, 1**.
Reading storage order as display order previously put the degree sign before its
temperature and dropped date separators carried by fixed-only parts. The same
order is used by the permutation path at `0x2C1077C4..7D0`.
Record `+0x54..+0x57` is not read,
`+0x58` is stored AARRGGBB text colour (RGB consumed, alpha ignored), `+0x5C` is rotation in tenths of a degree,
`+0x5E` selects `font_N.bin`, `+0x60` is signed letter spacing, and `+0x62`
is a dictionary index containing a digit permutation such as `"1234"` or
`"3214"`. `+0x5F/+0x61` have no effect. A nonzero rotation uses an RGB888
canvas; zero uses an ordinary label. Comp is therefore authorable, but every
dictionary index and source must be designed with the locale files rather than
copied independently.

Numeric selectors are exact: 0/1 use `%d`, 2–6 use `%02d` through `%06d`, and
any value at least 7 falls back to `%d`. Pair has the analogous wider table
through selector 8 (`%08d`) and falls back to `%d` at 9 or above. Pair source
116 is exceptional: it picks its own font instead of using its stored
numbered-font byte and can create a hard-coded clickable 256×201 overlay whose
destination is not customisable by the record.

## 8 · Rasters

A 12-byte header, raw row-major pixels, and a 4-byte trailer. There is no
compression or filtering; format `0x0088` is palette-indexed.

| Offset | Size | Field |
| ---: | ---: | --- |
| `0x00` | 2 | width |
| `0x02` | 2 | height |
| `0x04` | 1 | format code the watch reads |
| `0x05` | 1 | high byte of the producer's `u16`; not read |
| `0x06` | 2 | reserved, zero in all 171 records |
| `0x08` | 4 | data size == `w × h × bpp + 4` |
| `0x0C` | var | pixels |
| after | 4 | trailer; excluded from the pixels and never drawn |

Formats: `0x0082` = RGB565, 2 bytes/px, little-endian half-word with R in bits
15–11, G in 10–5, B in 4–0. `0x0080` = the same half-word plus one alpha byte,
3 bytes/px. `0x0088` = a 1,024-byte, 256-entry BGRA palette followed by one
8-bit palette index per pixel. These map to GUI image-format enums 4, 5 and 10
respectively; they are the only codes in the 7,306-image style/AOD corpus.

**All 171 records in both files have a trailer of exactly four bytes** after
their format-specific pixel plane, with no exceptions.

The watch reads the whole declared payload but hands the drawing code
`data_size - 4`, which is what establishes that the trailer is not pixels. It
does not check the pixel-size equation itself; the writer and the analyzer do.

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

### The canonical checksum obligation, in order

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
| Append a duplicate widget | as above, plus a unique global index and any deliberate alignment-target reference | high — never device-tested |
| Remove a non-final widget | as above, plus renumbering every later `+0x0E` and rewriting explicit Static/Hand `+0x1E` and Pair/Comp `+0x22` alignment targets that name a renumbered record | high — never device-tested |
| Add or remove a directory entry | the directory grows or shrinks by 74 bytes, so *every* payload offset changes including the first | high — untested |

### Tier 3 — not plausible from this file

| Attempt | Why it cannot work |
| --- | --- |
| Add an arbitrary typeface | No font program exists in the container. A face may select only the firmware's own families and the sizes in §5. |
| Add a new sensor or metric | Sequence IDs are firmware-defined. A value the watch does not publish renders as nothing; the file cannot introduce a data source. |
| Change panel geometry | 256 × 402 is baked into every background raster, the Hand pivot arithmetic, the container name, `setting.bin`, and the filename the plugin matches on. |
| Global search-and-replace on an integer | Type-words are not a uniform pointer array. The same 32-bit value can be an image offset, an ARGB colour, an angle, a frame count, a glyph index, or a mode. Only per-type, per-offset edits are sound. |
| Recover source artwork | RGB565 quantisation is irreversible (§8). |
| Reduce file size meaningfully | No compression stage, and no unreferenced raster in either file (§6). |

### The three traps

1. **The header CRC covers the directory.** Fixing an entry CRC without
   recomputing the header CRC leaves a noncanonical file that the repository's
   validator rejects, even though the watch ignores both on install.
2. **Image references are section-relative, not absolute.** Moving a whole style
   needs no widget edits at all; changing anything *inside* a style's image
   section before the last raster needs all of them.
3. **Four fields hold another widget's global index.** Static and Hand at
   `+0x1E`, Pair and Comp at `+0x22`, live unless the code beside them is
   `0xFFFF` — which no catalogue record sets. Renumbering records without
   renumbering these leaves a widget measured from whichever record inherited the
   number, and nothing about the resulting file looks wrong.

## 11 · Fields this analysis established

The container, directory, style, widget and image layers are described in full
above. The rows below are the individual fields whose reading this work established
or pinned to a specific verification count, gathered in one place because these are
the findings the implementation actually relies on.

| Field | What it holds, and what pins it |
| --- | --- |
| Hand `+0x20` | `(pivot_y<<16)\|pivot_x`, verified 14/14 against the panel centre `(128, 201)`; first type-word is a 360° sweep constant |
| Sprite `+0x20` | Frame count, verified 24/24; equals the length of the image-offset array in every record |
| Pair `+0x20`/`+0x22` | Alignment code and the global index it is measured from; 1 = left inset and 3 = right inset with negative `x`, verified 9/9. `0xFFFF` disables it, and no catalogue record does |
| Static/Hand `+0x1C`/`+0x1E` | The same pair at different offsets — **these are not a width and a height** |
| Pair `+0x2C` | Locale-dictionary base index; `0xFFFF` selects numeric formatting instead. Confirmed against rendered labels |
| Badge geometry | Start/end points plus thickness, confirmed numerically on the one available record, with the thickness word identified |
| Comp | Four 12-byte format programs, followed by colour, rotation, font, spacing and dictionary-order fields |
| Glyph descriptors | A **byte** count, not a character count |
| Glyph group 9 | A field-order permutation (`"1234"`, Italian `"3214"`), not display text |
| `setting.bin` `0x38`/`0x78` | Two 64-byte slots, each a lead byte plus a NUL-terminated name, byte-identical |
| Style `+0x10` | Numbered binding count shifted left 8; exact across all 509 corpus styles/AOD files |
| Font binding `+0x00..+0x47` | Per-watch-language family selector table; byte `+0x01` overrides current-language lookup when nonzero |
| Sequence IDs 41, 48 | Position + label group give 41 = bpm and 48 = kcal in `00106` |
| `style3` alpha cascade | The RGB565→RGB565+A background adds exactly 102,912 bytes and forces all three Hand offsets |

**One case these two files cannot show.** Every one of the 109 widget records here is
4-byte aligned with zero leftover tail, so nothing in this derivation exercises a
record carrying a two-byte tail after its complete 32-bit words. The parser keeps a
tail-preserving path regardless: absence across two containers is not evidence that
no face has one, and a writer that dropped such a tail would corrupt it silently.

Per-face totals, for reference: `00046` has 17 entries, 4 styles, 28 widget records
and 24 rasters; `00106` has 19 entries, 4 styles, 81 widget records and 147 rasters.

## 12 · Fields this later analysis resolved, and what remains

The later pass closes the old structural unknowns: style `+0x10`, the image
trailer, setting `+0x30/+0x34/+0x35`, Comp's words, vector/image Arc, LineBar, and all
17 dispatch slots now have explicit readings. A separate corpus audit assigns
every stored byte in all 99 vendor containers exactly one of format-control,
rendered-semantic, raster/string payload, or normal-path-no-effect, with zero
holes and zero overlaps. Its persistent vendor-only scope also checks the two
locale-rich reference variants used for this derivation; it deliberately does
not include widget-import experiments or the clean-room candidate as evidence.

No-effect bytes are still preserved or emitted canonically. They include the
outer CRCs (which the watch does not check on install), the style marker and
conventional image offset, the common reserved widget words, duplicate
colour/thickness fields, the image trailer, the font role string, and the unused
setting markers and name slots. “Never read” describes the firmware this was
established against; it does not promise every firmware build ignores them.

The byte-exact corrections from the final falsification pass are part of those
schemas: Static registers only the low `u16` of common `+0x04`; Pair, Comp,
vector Arc, image Arc and LineBar load their width/height halfwords signed;
vector Arc tests only `u16 +0x3C` and leaves `+0x3E/+0x3F` unread; LineBar
leaves `+0x28` unread and both loads and sentinel-tests its image pointer at
`+0x2C`; Badge `+0x31` has a diagnostic log effect but no face effect; and
`setting.bin +0x30` is formatted as a signed decimal `i32` with `%d`.

The preview picker is also stricter and stranger than a simple fixed-record
reader. It seeks a header at `style_index * 0x18570`, then independently seeks
the payload at `12 + style_index * (loaded_data_size + 12)`. Canonical records
use data size 99,684, so the two formulas agree; mutating that size can make the
header and payload calculations disagree.

What remains is evidence unavailable in this package set rather than an
unparsed byte:

| Gap | Exact boundary |
| --- | --- |
| Other firmware builds | Everything here is established against one firmware build; a different one could read a field this document calls never-read |
| Types 4 and 9 producer defaults | Their layouts are known, but no catalogue record reveals canonical padding, default bytes or authoring-tool labels |
| Source 70 name | Numeric current/goal/update behaviour is known; Samsung's user-facing label is absent |
| Compiled font selectors 4, 5, 7–12 | Exact pointer/size/fallback branches are known; friendly visual family names are absent |
| Device acceptance | A structurally exact package still needs an SM-R390 install; the phone emulator cannot stand in for the watch |
| Image-count policy | Hardware rejects a changed image-record count after syntactically valid parsing; the later policy check is not part of this file grammar |

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
containers, 4,034 widget records, 7,716 rasters, every CRC matched and every file
rebuilt byte-identically. The zero-residual class census in §1 is recomputed on
every run and the exit status reflects it, so a regression in the parse cannot
pass silently.

## 14 · Related work

**[Ahmadjerj/galaxy-fit3-parser](https://github.com/Ahmadjerj/galaxy-fit3-parser)**
— an independent, read-only Python parser for this same OPPO container: it walks
the `.bin`, extracts the RGB565 and RGB565+A rasters, and reconstructs whole
rendered previews, including the multi-style and AOD variants, the font bindings
and the locale-aware glyph groups. It covers the commonly emitted Static, Hand,
Sprite, Pair, Badge, Comp, Arc and LineBar records and, being a reader, never
writes a container back.

It is worth knowing about for two reasons. It independently reaches `Arc` and
`LineBar`, which are absent from the two files used for the initial derivation.
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
