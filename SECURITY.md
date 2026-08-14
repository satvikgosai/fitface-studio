# Security policy

FitFace Studio is an independent, experimental, personal-use project maintained in
spare time. There is no security team and no response-time commitment. Reports are
read and taken seriously, but treat everything here as best effort.

## Reporting a vulnerability

**Use GitHub's private vulnerability reporting** — the *Report a vulnerability*
button under this repository's **Security** tab. It opens a channel visible only to
the maintainer.

Please do not open a public issue for anything exploitable. For ordinary bugs with
no security dimension, a normal issue is exactly right.

A useful report says what an attacker controls, what they get, and how you know —
ideally with a container, a face ID, or a packet capture that reproduces it. If a
report involves a watch-face container, describe it rather than attaching a package;
see the redistribution note in [`NOTICE.md`](NOTICE.md).

## Supported versions

Only the latest release, and in practice only `main`. There are no maintenance
branches and no backports.

## Scope

The things most worth reporting, because they are where this app can actually hurt
someone:

- **Anything that reaches the watch without passing `Session.validatedBytes()`.**
  That function is fail-closed and is meant to be the only path to hardware. A
  bypass is the highest-severity class of bug in this project.
- **Container parsing and editing** (`:core:format`) — memory-unsafe reads,
  unbounded allocation, or a crafted `.bin`/`.apk` that makes the app write
  something it should have refused.
- **Download handling** (`:core:data`) — the 32 MiB bound, the declared-size match,
  the HTTPS-and-trusted-host rule, or XML entity handling.
- **The delivery path** (`:core:delivery`) — anything that lets another app on the
  phone drive a transfer, or that writes outside app-private storage.

### Known and accepted

`CorpusFetchReceiver` is exported without a permission so `adb shell am broadcast`
can reach it. It is compiled into the **debug variant only**, takes nothing from the
caller but a catalogue face ID, and does exactly what tapping Download does — fetches
a public package into this app's private cache. Note that the APKs published under
Releases are debug builds, so they do contain it. This is a documented trade-off, not
an oversight; a report that it exists is not a finding, but a report that it can be
made to do something else is.

## Out of scope

- **Watch-side firmware behaviour.** A watch rejecting, mis-rendering or misbehaving
  on a structurally valid container is a firmware property this project cannot
  change and does not control.
- **Vendor store APIs and hostnames.** Issues in the endpoints this app requests
  packages from belong to their operators, not here.
- **The third-party JAR mirror.** `:core:delivery:fetchAccessorySdk` pins a SHA-256
  precisely because the mirror is untrusted. If a fetched JAR ever fails that check
  the build is *working*. A compromised mirror serving a matching hash is in scope; a
  mirror serving anything else is not.
- Vulnerabilities in the accessory SDK JARs themselves. This project has no rights
  in that code and cannot patch it — report those to the vendor.

## What this software already assumes

Writing a modified container to a watch is inherently risky, and no report is needed
to establish that. It is stated plainly in [`NOTICE.md`](NOTICE.md) and the README:
there is no warranty, a valid container can still be rejected by firmware, and the
app is used on hardware you own at your own risk.
