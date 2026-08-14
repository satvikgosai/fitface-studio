# tools/

Standalone Python 3 scripts. No third-party dependencies — the PNG encoder,
pixel decoders and every chart are written against `zlib`, `struct` and
`zipfile` only.

| Script | Does |
| --- | --- |
| `fetch_corpus.py` | Populates the local test corpus by driving the debug build's download path |
| `analyze_container.py` | Decodes watch-face containers byte by byte and extracts every asset |
| `build_report.py` | Renders a self-contained HTML anatomy report from that output |

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

Three checks have to pass before a model is trusted, and all three are recorded
in it: every CRC-16 recomputed and matched, every byte assigned to exactly one
semantic class with zero residual, and the file rebuilt from the parsed pieces
and compared to the original. The exit status is non-zero if any container fails
one, so it works as a corpus-wide regression check.

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

Nothing in the page is asserted. Field readings, invariant hit-rates and the
"what remains unknown" table are computed from the models actually loaded, so a
reading that does not hold everywhere says so, and a run over one container
makes weaker claims than a run over a hundred.

Per-face detail — full widget dumps, asset galleries, variant diffs — is
expensive in page weight, so it is rendered for the first `--detail` faces
(default 2) and the page says which. Every other section is a census over
everything loaded.

## Reproducing the format documentation

[`docs/bin-format.md`](../docs/bin-format.md) was written from these scripts'
output, using the two commands shown above. `out/` is not committed; see
[`docs/development.md`](../docs/development.md) for how to obtain a corpus in the
first place.
