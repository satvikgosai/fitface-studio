# Contributing

This is a personal, experimental project for one watch family. It is maintained in
spare time, and a change that edits watch-face binaries and sends them to real
hardware gets read carefully before it lands. That is the only reason review may be
slow — not a judgement on the change.

## Before you start

Read [`AGENTS.md`](AGENTS.md). It is the danger map: roughly forty specific mistakes
that have already been made in this codebase, written as imperatives so they are not
made again. Most of them produce a container the watch accepts and then quietly
refuses to render, which no test you would think to write will catch.

If you are touching `:core:format` or the editing rules, read
[`docs/editing.md`](docs/editing.md) as well. Every rule in it is there because
breaking it made real faces unopenable, uneditable, or silently wrong on hardware.

## Setup

Android Studio with its bundled JBR, Android SDK 36, and the checked-in Gradle
wrapper. Newer system JDKs break Robolectric with `Unsupported class file major
version`, so always pass the JBR explicitly.

```bash
JBR='/Applications/Android Studio.app/Contents/jbr/Contents/Home'
```

That is the macOS default; on Linux an Android Studio install typically puts it at
`/opt/android-studio/jbr`.

The first build needs network. `:core:delivery` compiles against two accessory SDK
JARs that are **not** in this repository, and the build fetches them into `libs/` and
verifies each against a pinned SHA-256. **Never commit them back** — `libs/*.jar` is
gitignored for a reason. See [`libs/README.md`](libs/README.md) and
[`NOTICE.md`](NOTICE.md).

## Build

```bash
./gradlew -Dorg.gradle.java.home="$JBR" :app:assembleDebug
```

The APK lands in `app/build/outputs/apk/debug/`.

## Running the tests

```bash
./gradlew -Dorg.gradle.java.home="$JBR" \
  :core:model:testDebugUnitTest :core:format:testDebugUnitTest \
  :core:delivery:testDebugUnitTest :core:data:testDebugUnitTest \
  :feature:editor:testDebugUnitTest :feature:library:testDebugUnitTest \
  :app:lintDebug
```

The current baseline and what a clean clone should see are in
[`docs/development.md`](docs/development.md#test) — quoted once, there, so the
numbers cannot drift between files.

If every test task fails with `Failed to exec spawn helper` before a single test
runs, that is a stale Gradle daemon from an earlier Android Studio version.
`./gradlew --stop` fixes it; nothing in the repository is wrong.

## The corpus, and why tests skip

Many tests read real watch-face containers and recorded transfer payloads. Those are
downloaded packages this project has no right to redistribute, so they are **never
committed**. Those tests guard themselves with `Assume` and skip when the corpus is
absent, which is why a clean clone runs a subset and still passes.

**Do not "fix" a skip by hard-coding a path.** The corpus is located through
`fit3.corpusRoot` / `fit3.fixtureRoot`; the layout and how to populate it are in
[`docs/development.md`](docs/development.md#the-test-corpus).

## Conventions

No document or build file may depend on a path outside this repository.

Brand strings appear only where they are literal technical identifiers — package
IDs, model numbers, hostnames — and **never in UI copy**. The one exception is
[`NOTICE.md`](NOTICE.md), where the non-affiliation statement has to name the
vendors it disclaims to mean anything.

UI copy lives in per-module `res/values/strings.xml`, with `editor_`, `library_` and
`ui_` prefixes — library resources merge into one table, where a bare name collision
is silently resolved rather than reported. Two things stay in Kotlin on purpose:
decorative glyphs, and any string produced below the UI layer. `:core:format`
diagnostics, validation errors, `WidgetGuide.supportMessage` and `:core:delivery`
transfer messages are framework-free by design and reach the screen as data.

## Writing comments and docs

Name what you refer to. No "both bugs fixed", no "the fix", no pointing at a
revision a reader cannot see. A comment that needs the pull request open beside it
to make sense is worse than no comment.

Docs record their evidence. The **proven** / **supported** / **unknown** vocabulary
defined in [`docs/README.md`](docs/README.md#honesty-about-evidence) is load-bearing,
not decoration — anything the code depends on is in the first category or is
fail-closed. If you promote a claim from one level to another, say what moved it.

## Invariants that must survive any change

The six are listed once, in
[`docs/architecture.md`](docs/architecture.md#invariants). Do not restate them in
your PR; do check them.

The one to internalise: **`Session.validatedBytes()` is fail-closed and is the only
path to the watch.** Anything that routes around it — a shortcut for testing, a
"temporary" direct send — is not acceptable, because the failure mode is a bricked
face on someone's wrist and the code cannot tell you it happened.

## Changing the format or the editor

- **Never add or remove an image record.** The watch ignores a container whose
  image-record count changed. This is firmware policy, proven on hardware, and the
  bytes look perfectly sound when it happens.
- Every new rule records the corpus or hardware evidence behind it. "It seemed to
  work" is not evidence; say how many faces, and which.
- Extend `CanvasIntegrityTest` when a bug is visual rather than structural. It
  replays every structural edit across all corpus faces and asserts the canvas still
  agrees with itself. That class of bug produces a valid container the watch would
  accept, so nothing else catches it.

## Hardware verification

Say which faces and which edits you tried, or say you did not test on hardware.
**Both are fine.** Most changes cannot be device-tested by most people — the project
is honest about which claims are device-proven and which are not, and a PR that
guesses is worse than one that says "not verified".

## Opening a pull request

Fill in the template. It only asks for things specific to your change; everything
standing is in this file.
