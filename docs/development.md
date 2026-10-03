# Development

## Toolchain

Android Studio with its bundled JBR, Android SDK 36, and the checked-in Gradle
wrapper. Pass the JBR explicitly instead of relying on the system Java. Java 17
is the compilation target; the bundled runtime can be newer (the recorded local
verification used JBR 25.0.2). CI uses Temurin 17.

```bash
JBR='/Applications/Android Studio.app/Contents/jbr/Contents/Home'
```

That path is the macOS default; adjust it for your platform — on Linux an Android
Studio install typically puts the JBR at `/opt/android-studio/jbr`.

| Property | Value |
| --- | --- |
| Application ID | `dev.fitface.studio` |
| minSdk / target / compile | 28 / 36 / 36 |
| Java bytecode target | 17 |

`libs/` holds two accessory SDK JARs consumed only by `:core:delivery`. They are not
committed, and the first build fetches and hash-verifies them, so that build needs
network. [`libs/README.md`](../libs/README.md) covers the mirror, the hashes and how
to fetch them on their own.

## Build

```bash
./gradlew -Dorg.gradle.java.home="$JBR" :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

The release variant enables R8 and resource shrinking. Release signing
credentials are deliberately not stored in this repository.

## Test

```bash
./gradlew -Dorg.gradle.java.home="$JBR" \
  :core:model:testDebugUnitTest :core:format:testDebugUnitTest \
  :core:delivery:testDebugUnitTest :core:data:testDebugUnitTest \
  :core:ui:testDebugUnitTest \
  :feature:editor:testDebugUnitTest :feature:library:testDebugUnitTest \
  :app:lintDebug
```

Last recorded corpus-backed verification (2026-10-03, artwork rotation on `dev`):
**749 tests collected, 748 passed, 1 skipped,
0 failures, 0 lint errors, 23 lint warnings.**
Those warnings were dependency/SDK notices. These are recorded results, not an
assertion that a later checkout has already passed; run the command above.

The skipped test is `IdentityTransferProtocolTest`, which needs recorded protocol
fixtures. Tests that read the uncommitted corpus also skip when it is absent; a clean
clone therefore runs a smaller subset. Do not infer corpus coverage from success alone.

`:core:ui` is the only module whose tests measure composables.
`FitTopBarLayoutTest` is the one that measures a real layout, so it runs Robolectric in
`@GraphicsMode(NATIVE)`: the default stub font metrics collapse every string to a few
pixels, which makes a text-labelled button look tiny and a title column look enormous.
Even in native mode Robolectric's metrics are not the device's — it measures the
subtitle that clipped on a real phone as fitting — so it asserts layout geometry rather
than whether text was ellipsized. See the class comment.

`FitDetailsTest` checks that optional help opens by click, announces its expanded state,
keeps its body reachable and restores that state after recreation. It complements the
emulator checks of long explanations, large text and scrolling.

`SemanticColorContrastTest` and `SmallTextContrastTest` need no Android runtime at all:
`Color` is a value class and the WCAG formula is arithmetic, so the palette is pinned by
plain JVM tests that cannot flake.

With the full corpus present, `EveryFaceRendersTest` sweeps all 99 editable
catalogue faces — about three minutes — and checks that each one:

- parses, validates and round-trips byte-identically;
- resolves a 256 × 402 panel;
- reports exactly the widgets drawing a panel-sized raster as background layers;
- keeps every selectable widget overlapping the panel;
- survives a move of every selectable widget, both on its own style and across
  every variant that carries it;
- survives a remove/restore of every style's final widget, and an all-variant
  remove/restore/duplicate of each style's first canvas widget.

There is no `androidTest` source set; the Room migration test runs under
Robolectric.

## The test corpus

Most tests are self-contained. The rest read real watch-face containers and
recorded transfer payloads, which are downloaded packages this project has no
right to redistribute, so they are **never committed**. Those tests skip
themselves when the corpus is absent, guarded by `Assume`. Do not "fix" a skip by
hard-coding a path.

Two system properties are resolved in the **root** `build.gradle.kts`:

- `fit3.corpusRoot` — real APKs and BINs;
- `fit3.fixtureRoot` — recorded protocol payloads.

For each, a supplied `-P` property takes precedence over its corresponding
`FIT3_CORPUS_ROOT` / `FIT3_FIXTURE_ROOT` environment variable. The first existing
candidate is used: that configured path, then repository-local `corpus/`. Legacy
fallbacks are `../artifacts` for corpus and `..` for fixtures; neither is needed
for a clean checkout. A nonexistent explicit property does not retry the environment.

```bash
./gradlew -Dorg.gradle.java.home="$JBR" \
          -Pfit3.corpusRoot="$PWD/corpus" \
          -Pfit3.fixtureRoot="$PWD/corpus" \
          :core:format:testDebugUnitTest :core:delivery:testDebugUnitTest
```

The expected layout is the one the store ships:

```text
<corpusRoot>/SM_R390/SM-R390_<id>_256x402/SM-R390_<id>_256x402.bin
<corpusRoot>/SM-R390_<id>/assets/SM-R390_<id>_256x402.bin
<corpusRoot>/SM-R390_<id>.apk
```

To populate the corpus, follow [Tools: fetch the corpus](../tools/README.md#fetch-the-corpus).
The [tools README](../tools/README.md) also owns analyzer/report commands, output
layout and evidence-set boundaries.

## Manual verification

```bash
apkanalyzer manifest application-id app/build/outputs/apk/debug/app-debug.apk
apkanalyzer manifest min-sdk      app/build/outputs/apk/debug/app-debug.apk
apkanalyzer manifest target-sdk   app/build/outputs/apk/debug/app-debug.apk
adb logcat | rg 'Fit3|Accessory|OtaTransfer|SASocket|FOTA'
adb shell dumpsys package dev.fitface.studio
```

For a connection-only smoke test, stop after both peers are found. Do not
automate the final install confirmation during routine tests.

The catalogue smoke test should load the live catalogue, open a multi-style face,
download it, and reach the editor with the expected face and sampler IDs. An
Android 16 ARM64 emulator has verified 100 faces, 411 compatible styles, and the
download/edit path for face `00112`.

## Test constraints and troubleshooting

- `Unsupported class file major version` indicates a Java/test-tool compatibility
  mismatch. Use the documented JBR and check the actual runtime; do not assume its
  version from the bytecode target. A stale daemon causing `Failed to exec spawn
  helper` is fixed by `./gradlew --stop`.
- Editor ViewModel tests use a test `Dispatchers.Main` and
  `unitTests.isReturnDefaultValues` for Android logging. They deliberately avoid
  Robolectric: the accessory SDK's pre-stackmap receiver bytecode fails JVM verification.
- Native Robolectric tests pin geometry and `FitDetails` semantics, not device font
  truncation. Dialog composition can fail to idle; verify scrolling on the emulator.
- Real gestures are not driven by tests. Pure hit testing, clamping and drag-axis
  helpers are covered; stale gesture inputs and accumulation still need UI checks.
- The updater dialogs/state-to-UI mapping in `:app` have no automated UI coverage.
  Decision tests cover versions, feed, allowlists and signing verdicts. Recorded
  emulator checks covered offer/download, permission handoff, installer confirmation
  and cancellation, network failure, and a 0.1.0 → 0.1.1 self-install preserving
  projects. A real-device signing-key mismatch remains untested.
- An emulator cannot verify watch rendering, channel handover or timeout recovery.
  See [delivery gaps](direct-install.md#unverified) and
  [format gaps](bin-format.md#hardware-coverage-and-open-cases).

## Local working files

`analysis/` is ignored. Use `prompts/` for task briefs, `notes/` for unresolved work,
`research/` for firmware/format evidence, `experiments/` for test artifacts,
`reviews/` for audits and verification, `captures/` for screenshots and reports,
`assets/` for local design exports, and `archive/` for completed plans. Durable facts
belong in the relevant tracked reference; do not add a tracked document per task.
Keep corpus and SDK files in their documented locations. Machine settings, build
caches, signing material and proprietary packages remain uncommitted.

## Signing the debug build — maintainer only

> This section describes repository secrets and the release workflow. It applies to
> whoever owns the repository; a contributor cannot act on any of it and does not
> need to read it. Nothing here affects a local build.

An update must use the installed app's signing certificate. Uninstalling to change
keys deletes saved projects. Local AGP builds reuse the machine's debug keystore;
CI needs a stable key because its runners are ephemeral.

`app/build.gradle.kts` lets the debug keystore be supplied explicitly. Each
input reads `-P<property>` first, then the environment variable, and is ignored when
blank:

| Property | Environment | Default |
| --- | --- | --- |
| `fit3.debugKeystore` | `FIT3_DEBUG_KEYSTORE` | none — AGP's generated keystore |
| `fit3.debugKeystorePassword` | `FIT3_DEBUG_KEYSTORE_PASSWORD` | `android` |
| `fit3.debugKeyAlias` | `FIT3_DEBUG_KEY_ALIAS` | `androiddebugkey` |
| `fit3.debugKeyPassword` | `FIT3_DEBUG_KEY_PASSWORD` | `android` |

With none of them set — every local build — nothing changes.

`.github/workflows/release.yml` restores a keystore from the
`DEBUG_KEYSTORE_BASE64` repository secret and points `FIT3_DEBUG_KEYSTORE` at it. To
populate that secret from the keystore already on a development machine, so that CI
builds and local builds share one identity:

```bash
# macOS; on Linux use `base64 -w0 ~/.android/debug.keystore`
gh secret set DEBUG_KEYSTORE_BASE64 --body "$(base64 -i ~/.android/debug.keystore)"
```

Set `DEBUG_KEYSTORE_PASSWORD`, `DEBUG_KEY_ALIAS` and `DEBUG_KEY_PASSWORD` as well only
if the keystore does not use the Android debug defaults above. The workflow prints the
restored key's SHA-256 fingerprint and fails if the secret is truncated or mis-encoded,
so a signing problem surfaces before the build rather than as an opaque Gradle error.

Without the secret the workflow still builds and publishes, with a warning; the APK is
then signed with a throwaway key and cannot be installed over any other build. Note
that this is a **debug** signing key and confers nothing: it is not a release key, and
anyone holding it can produce an APK a device will accept as an update.

The release workflow builds the debug APK; it does not run the test suite. A `v*`
tag publishes a GitHub prerelease; a manual run uploads a build artifact retained
for 90 days. Preserve the key across releases. See [CONTRIBUTING](../CONTRIBUTING.md)
for review conventions; do not store secret values in repository or local notes.
