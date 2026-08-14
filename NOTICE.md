# Notices

## Not affiliated with any device vendor

FitFace Studio is an independent, experimental, personal-use project. It is **not
affiliated with, authorised, endorsed, sponsored by, or connected to Samsung
Electronics, OPPO, or any other device or platform vendor**, and it is not a
product of any of them.

All product names, trademarks, model numbers and package identifiers that appear
in this repository — including `SM-R390`, the `com.samsung.*` package
identifiers, and the store hostnames used for downloads — appear solely because
they are the literal, unavoidable technical identifiers of the hardware, the
companion apps and the endpoints this software interoperates with. Their use is
nominative and implies no relationship of any kind.

This app will not be published to Google Play or any other store. It is built and
sideloaded by its author for use with hardware they own.

## Third-party components

### Accessory SDK JARs — `libs/`

`libs/accessory-v2.6.4.jar` and `libs/sdk-v1.0.0.jar` are **proprietary
third-party binaries** licensed by their vendor. They are not covered by this
project's MIT licence, and this project grants no rights in them.

**Neither JAR is distributed with this repository.** This project does not own
them and has no right to redistribute them, so they are not committed and
`.gitignore` excludes `libs/*.jar`. They exist only as local, untracked files on
the machine doing the build, so that `:core:delivery` can compile against the
accessory transport the paired watch actually speaks.

When a JAR is missing, `:core:delivery:fetchAccessorySdk` downloads it from an
unofficial third-party mirror and verifies it against a pinned SHA-256 before the
build may use it — see [`libs/README.md`](libs/README.md). That mirror is not a
vendor distribution channel, and fetching a file from it is not a licence to use
it: **satisfy yourself that you are permitted to use these JARs**, or remove them
and build without `:core:delivery`. Do not redistribute them, and do not commit
them back into this repository.

**A built APK contains code from these JARs.** `.github/workflows/release.yml`
fetches them the same way a local build does, and on a version tag publishes the
resulting debug APK as a GitHub Release. That build is offered so the author and
anyone working on this project can install it on hardware they own. It is not a
product, not a supported distribution, and not a grant of any rights in the
accessory SDK — publishing a build does not license what is inside it, and this
project has no standing to license the vendor's code to anyone. The same applies to
any APK you build yourself: passing it on passes on the vendor's code with it.

### Runtime content

**No watch-face content is distributed by this software or this repository.** No
watch-face package, container, raster, font or preview image is bundled in the app
or committed here, and `.gitignore` keeps it that way; the documentation
screenshots described below are captures of this app's own interface, not face
assets. The app obtains each package by requesting it, at runtime and on the
user's instruction, from the vendor's own store API over the hostnames named
above — the same endpoints the stock companion plugin uses. Everything it obtains
that way remains the property of its publisher; this project claims no rights in
any of it and grants none.

* Downloaded packages, extracted containers and everything derived from them are
  written **only** to this app's private storage, and nowhere else.
* Nothing is re-signed, re-published, uploaded, mirrored or shared by the app.
* Use this only with faces you are authorised to inspect and modify on hardware
  you own.

### Documentation images — `docs/screenshots/`

Most of [`docs/screenshots/`](docs/screenshots/) is captures of this app's own user
interface, taken from an emulator. One file, `on-watch.jpg`, is a photograph of the
author's own watch running a face this app sent to it, included because delivery to
hardware is the one step an emulator cannot show.

Some of what those images show — catalogue previews and rendered watch-face artwork,
whether on screen or on the watch — belongs to the publishers of those faces. It
appears here only to document what this software does, at a size and fidelity suited
to that; none of it is offered as watch-face assets, and nothing in these images can
be extracted and installed on a watch.

### Open-source dependencies

Build-time and runtime dependencies (Kotlin, AndroidX/Jetpack Compose, Material 3,
Hilt/Dagger, Room, DataStore, Navigation 3, OkHttp, Coil, kotlinx-coroutines and
kotlinx-serialization) are used under their own licences — Apache 2.0 unless
stated otherwise by the project in question. Run
`./gradlew :app:dependencies` for the resolved set.

## Safety and warranty

Writing a modified container to a watch is inherently risky. This software:

* validates every container it produces and refuses to send anything that fails;
* never re-signs or installs an Android package on the watch;
* still cannot guarantee any particular firmware will accept the result.

There is no warranty of any kind. See `LICENSE`. Use at your own risk, on your
own hardware.
