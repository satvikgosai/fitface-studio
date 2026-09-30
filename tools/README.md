# Tools

All scripts in `tools/` use Python 3's standard library only. The decoder shares
no implementation with `:core:format`, providing an independent byte-level check.

| Script | Purpose |
| --- | --- |
| [fetch_corpus.py](fetch_corpus.py) | Drive the debug app's download path to obtain the local corpus |
| [analyze_container.py](analyze_container.py) | Decode containers/APKs/directories and extract assets |
| [build_report.py](build_report.py) | Build a self-contained HTML anatomy report from models |
| [watchface_schema.py](watchface_schema.py) | Evidence-labelled, versioned definitions for both tools |
| [test_report_tools.py](test_report_tools.py) | Synthetic coverage of all 17 types and field regressions |

## Fetch the corpus

Corpus configuration and expected layout are in
[Development](../docs/development.md#the-test-corpus). Run from the repository root.

To populate it from the live catalogue, with the debug build installed on a
connected device and the app opened once so it has synced:

```bash
python3 tools/fetch_corpus.py corpus
```

That downloads the catalogue packages and extracts their containers. The recorded
100-face catalogue contained 99 editable containers; `00254` had none. Counts can
change when the store changes. It works by
driving a debug-only broadcast receiver that calls the app's own download path,
because the store's package endpoint requires the stock plugin's signed request
parameters. The receiver is compiled into the debug variant only.

## Analyze and report

Run from the repository root; generated output belongs in ignored `analysis/`:

```bash
python3 -m unittest tools/test_report_tools.py
python3 tools/analyze_container.py corpus/SM_R390 \
  --out analysis/research/format-report --skip-images --quiet
python3 tools/build_report.py analysis/research/format-report \
  --output analysis/research/format-report/anatomy.html
```

Drop `--skip-images` to decode raster PNGs; `--thumb-cap` controls thumbnail size.
A single `.bin`, `.apk`, or `corpus/packages` also works as analyzer input.

| Output | Contents |
| --- | --- |
| `index.json` | Analyzed inputs, consumed by the report builder |
| `<face>/model.json` | Versioned structural model, every field and coverage audit |
| `<face>/entries/*.bin` | Verbatim entry payloads |
| `<face>/images/*.png` | Full-resolution decoded rasters |
| `<face>/thumbs/*.png` | Bounded report thumbnails |

Verdicts distinguish byte integrity (CRCs, coverage, exact rebuild), exact type
schema coverage, and cross-resource validity (pointers, counts, fonts, dictionaries,
trailers). Any failed container check exits nonzero. Unknown formats, invalid record
sizes and boundary mismatches are errors; a containerless package is noted and skipped.
The model-only catalogue pass has taken about twenty seconds; image output takes longer.

The report has no external requests: inlined rasters, SVG diagrams and both themes.
Counts come from the models actually loaded. `--faces SM-R390_00046_256x402` filters;
`--detail 1` limits expensive per-face dumps/galleries (default 2). Other sections
remain a census of loaded models. Stale schema versions are refused: rerun the
analyzer after changing the schema. The packing graph distinguishes absolute file
offsets, section-relative raster pointers, logical resource indices and data sources.

Keep evidence sets separate: `corpus/SM_R390` is the 99 vendor-container census.
The schema's 101-container ledger also includes the locale-rich variants in
`corpus/SM-R390_00046/assets/` and `corpus/SM-R390_00106/assets/`. Analyze those
separately because their face IDs collide with the vendor set. Never mix generated
or experimental containers into either census. The [format reference](../docs/bin-format.md)
records the two reference hashes and their original measurements.
