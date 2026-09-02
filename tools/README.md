# tools/

Standalone Python 3 scripts. No third-party dependencies — the PNG encoder,
pixel decoders and every chart are written against `zlib`, `struct` and
`zipfile` only.

| Script | Does |
| --- | --- |
| `fetch_corpus.py` | Populates the local test corpus by driving the debug build's download path |
| `analyze_container.py` | Decodes watch-face containers byte by byte and extracts every asset |
| `build_report.py` | Renders a self-contained HTML anatomy report from that output |
| `watchface_schema.py` | Shared, evidence-labelled record definitions used by both tools |
| `test_report_tools.py` | Synthetic coverage for all 17 widget types and high-risk field regressions |

## analyze_container.py

Re-derives the container structure from raw bytes. It shares no code with
`:core:format`, so agreement between the two is independent corroboration rather
than a tautology — which is the whole reason it exists.

Accepts `.bin` containers, the `.apk` packages they ship inside, or directories
of either:

```bash
python3 tools/analyze_container.py corpus/packages --out out
python3 tools/analyze_container.py face.bin --out out
```

It writes, per face:

| Path | Contents |
| --- | --- |
| `<out>/index.json` | what was analysed, for `build_report.py` |
| `<out>/<face>/model.json` | complete structural model — every field, every record, coverage audit |
| `<out>/<face>/entries/*.bin` | every directory-entry payload, extracted verbatim |
| `<out>/<face>/images/*.png` | every embedded raster decoded to full resolution |
| `<out>/<face>/thumbs/*.png` | bounded thumbnails for the report |

The verdict is split rather than collapsed into one optimistic flag:

- byte integrity — CRCs, no holes or overlaps, byte-identical reconstruction;
- exact schema coverage — the common prefix plus the layout of whichever of the
  17 types it is, including byte-sized and signed fields rather than generic words;
- cross-resource validity — authoritative image pointers, style/preview counts,
  consecutive numbered fonts, dictionary bounds and canonical image trailers.

The exit status is non-zero if any container fails one, so it works as a
corpus-wide regression check. The model also records the schema version it was
produced with, so a stale run cannot be mistaken for a current one.

It fails loudly rather than guessing — an unknown image format, an implausible
record size, or a stream that does not end exactly on its declared boundary is
reported, not skipped. A package with no container inside (some catalogue
entries are customisation apps the watch renders itself) is skipped with a note.

`--skip-images` writes the model without decoding rasters, which is the
difference between about twenty seconds and several minutes over a full
catalogue. `--thumb-cap` sets the longest thumbnail edge.

## build_report.py

Renders one HTML page with no external requests: rasters inlined as data URIs,
charts as hand-built SVG, light and dark themes.

```bash
python3 tools/build_report.py out --output out/anatomy.html
python3 tools/build_report.py out --faces SM-R390_00046_256x402 --detail 1
```

Corpus counts and validation results are computed from the models actually
loaded. Field definitions come from the same versioned schema used by the
analyzer. The page includes all 17 types, a byte map for each, exact Value and
Composite formatting, source ids, the 72-slot font selector table, fixed-stride
previews, what-the-watch-accepts against what-a-writer-should-emit, the 4 MiB
boundary and the open evidence gaps.

The builder refuses stale models, because rendering an older generic-word model
would silently reintroduce disproven claims. Re-run the analyzer after a schema
change.

The file-layout section includes an end-to-end packing and reference graph. It
lists every directory record and payload in one real container, then expands one
style entry to distinguish absolute file offsets, image-section-relative raster
pointers, logical font/glyph indices and the data-source ids the watch owns.

Per-face detail — full widget dumps, asset galleries, variant diffs — is
expensive in page weight, so it is rendered for the first `--detail` faces
(default 2) and the page says which. Every other section is a census over
everything loaded.

## Regression checks

```bash
python3 -m unittest tools/test_report_tools.py
python3 tools/analyze_container.py corpus/SM_R390 --out out --skip-images --quiet
python3 tools/build_report.py out --output out/anatomy.html
```

That is exactly the 99 catalogue containers, and it is the regression check to
run after changing either script.

The reference ledger the schema quotes its totals from is a different, larger
set: those 99 plus the two locale-rich variants under
`corpus/SM-R390_00046/assets/` and `corpus/SM-R390_00106/assets/`, which carry
the same face IDs and so have to be analysed separately rather than in one run.
Keep the two apart — a 99-container pass is not the 101-container ledger — and
never let a generated or experimental container into either count.

## Reproducing the format documentation

[`docs/bin-format.md`](../docs/bin-format.md) was written from these scripts'
output, using the two commands shown above. `out/` is not committed; see
[`docs/development.md`](../docs/development.md) for how to obtain a corpus in the
first place.
