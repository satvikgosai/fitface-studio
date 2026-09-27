# Contributing

FitFace Studio is a personal, experimental project for one watch family. Read
[AGENTS.md](AGENTS.md) and the reference for the area you intend to change.

## Setup and checks

[Development](docs/development.md) owns toolchain setup, build/test commands, corpus
configuration and maintainer signing instructions. [Tools](tools/README.md) owns
script usage. Accessory SDK JARs are fetched on the first build; never commit them
or downloaded watch faces.

Run the checks relevant to your change and report what ran, what skipped, and what
was checked on an emulator or a physical watch. Corpus tests skip on a clean clone;
a successful task alone does not establish full corpus coverage.

## Conventions

Keep model and format implementations framework-free. Module ownership and the
validation/persistence boundaries are in [Architecture](docs/architecture.md).

Brand strings belong only in literal technical identifiers (package IDs, model
numbers, hostnames), never UI copy. [NOTICE.md](NOTICE.md) names vendors where the
non-affiliation and rights statements require it.

UI copy lives in module `res/values/strings.xml` with `editor_`, `library_` and
`ui_` prefixes. Decorative glyphs and framework-free diagnostics remain in Kotlin.
Follow the [design system](docs/design-system.html#implementation-rules) for visible
status, optional help, accessibility and layout.

Setup and documentation must be usable from a clean checkout. Do not require a
personal path or an undocumented sibling repository.

## Documentation

Update the existing canonical reference instead of adding a parallel guide, audit
or session report. Keep implementation facts and enduring constraints; put task
plans, screenshots and working notes in ignored `analysis/` subdirectories.

Name the behaviour and code being discussed. Avoid comments that require a PR or
conversation to understand. Preserve unique evidence when condensing text, and
use the [evidence vocabulary](docs/bin-format.md#evidence) consistently. Hardware
claims must name the faces and operations verified; “not verified” is acceptable.

## Review

- Preserve [architecture invariants](docs/architecture.md#invariants), especially
  `Session.validatedBytes()` as the only route to the watch.
- For format edits, check [editing contracts](docs/bin-format.md#editing-contracts)
  and extend `CanvasIntegrityTest` when structural validation cannot catch the visual bug.
- For UI changes, exercise the affected flow on an emulator, including errors,
  narrow/short layouts and accessible text where applicable.
- Fill in the pull-request template with relevant validation and remaining limits.
  Changelog conventions live in [AGENTS.md](AGENTS.md#documentation-and-local-work).
