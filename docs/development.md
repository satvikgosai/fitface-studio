# Development

## Toolchain

Android Studio with its bundled JBR, Android SDK 36, and the checked-in Gradle
wrapper. Newer system JDKs break Robolectric with `Unsupported class file major
version`, so always pass the JBR explicitly.

```bash
JBR='/Applications/Android Studio.app/Contents/jbr/Contents/Home'
```

That path is the macOS default; adjust it for your platform — on Linux an Android
Studio install typically puts the JBR at `/opt/android-studio/jbr`.

| Property | Value |
| --- | --- |
| Application ID | `dev.fitface.studio` |
| minSdk / target / compile | 28 / 36 / 36 |
| Java | 17 |

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

## Signing the debug build — maintainer only

> This section describes repository secrets and the release workflow. It applies to
> whoever owns the repository; a contributor cannot act on any of it and does not
> need to read it. Nothing here affects a local build.

Android identifies an installed app by application ID **and** signing certificate. An
APK signed by a different key cannot update one already installed: the package manager
refuses it, and the only way through is an uninstall — which deletes app-private
storage, and with it every saved project.

Locally this is invisible. AGP generates `~/.android/debug.keystore` the first time it
is needed and that file persists, so every debug build from one machine shares a key
and reinstalls cleanly. A CI runner starts with no such file and would generate a new
throwaway key on every run, so no two builds could update each other.

`app/build.gradle.kts` therefore lets the debug keystore be supplied explicitly. Each
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

## Test

```bash
./gradlew -Dorg.gradle.java.home="$JBR" \
  :core:model:testDebugUnitTest :core:format:testDebugUnitTest \
  :core:delivery:testDebugUnitTest :core:data:testDebugUnitTest \
  :feature:editor:testDebugUnitTest :feature:library:testDebugUnitTest \
  :app:lintDebug
```

Current baseline: **195 unit tests, 0 failures, 0 lint errors, 13 lint warnings.**
Every warning is a dependency- or SDK-version notice in a build file, none in this
code, so the count tracks whatever the ecosystem has published since.

81 of the 195 read the uncommitted corpus and skip without it, and
`IdentityTransferProtocolTest` skips without the recorded protocol fixtures. A clean
clone therefore runs 113 and still passes.

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

Each resolves `-P<name>=<path>`, then `FIT3_CORPUS_ROOT` / `FIT3_FIXTURE_ROOT`,
then `corpus/` in the repository root (gitignored), then a sibling `../artifacts`
and `..`.

```bash
./gradlew -Pfit3.corpusRoot=/path/to/containers \
          -Pfit3.fixtureRoot=/path/to/payloads \
          :core:format:testDebugUnitTest :core:delivery:testDebugUnitTest
```

The expected layout is the one the store ships:

```text
<corpusRoot>/SM_R390/SM-R390_<id>_256x402/SM-R390_<id>_256x402.bin
<corpusRoot>/SM-R390_<id>/assets/SM-R390_<id>_256x402.bin
<corpusRoot>/SM-R390_<id>.apk
```

To populate it from the live catalogue, with the debug build installed on a
connected device and the app opened once so it has synced:

```bash
python3 tools/fetch_corpus.py corpus
```

That downloads every catalogue package and extracts its container — 99 of the
100 faces, the hundredth being `00254`, which ships no container. It works by
driving a debug-only broadcast receiver that calls the app's own download path,
because the store's package endpoint requires the stock plugin's signed request
parameters. The receiver is compiled into the debug variant only.

## Decoding a container by hand

`tools/analyze_container.py` re-derives the container structure from raw bytes
without sharing any code with `:core:format`, so it is both the way new format
findings get established and an independent check on the parser the app uses.
It takes `.bin` containers, the `.apk` packages they ship inside, or directories
of either.

```bash
python3 tools/analyze_container.py corpus/packages --out out
python3 tools/build_report.py out --output out/anatomy.html
```

That writes a structural model, every directory entry, and every decoded raster
per face, then renders a self-contained HTML report over them. Over the full
catalogue the model-only pass (`--skip-images`) takes about twenty seconds and
exits non-zero if any container fails a CRC, coverage or reconstruction check —
which makes it a usable corpus-wide regression check on the format itself.
See [`tools/README.md`](../tools/README.md).

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

## Conventions

Naming, brand strings and where UI copy lives are in
[`CONTRIBUTING.md`](../CONTRIBUTING.md#conventions).
