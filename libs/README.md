# Accessory SDK dependencies

`:core:delivery` compiles against two accessory SDK JARs that belong in this
directory. **They are not committed, and must not be.** They are proprietary
third-party binaries: this project does not own them and has no right to
redistribute them. `.gitignore` excludes `libs/*.jar` so they cannot be added by
accident.

| File | Bytes | SHA-256 | Purpose |
| --- | --- | --- | --- |
| `accessory-v2.6.4.jar` | 183399 | `d8333b1d92866b09c712476f82aedfbcfd2f909cbf14cc1d4ebffd9f864dce14` | Accessory discovery and messaging API |
| `sdk-v1.0.0.jar` | 2008 | `a0950fde86125fd7487039e6c5d009e1f502155ce504c29ac04c9d2737b78a5b` | Base `Ssdk*` types required by Accessory SDK 2.6.4 |

Keep the pair together. Removing the base SDK JAR causes reflected accessory
agent construction to fail with a nested `NoClassDefFoundError`;
`AccessorySdkDependencyTest` guards the ABI that Accessory 2.6.4 needs from it.

## How the build gets them

`:core:delivery:fetchAccessorySdk` runs before anything compiles against them:

* **A JAR is already in `libs/`** — it is left exactly as it is. Nothing is
  downloaded, nothing is overwritten. If its SHA-256 does not match the table
  above the build warns that it is not the build this module was written against
  and carries on with your copy; delete it to fetch the pinned one instead.
* **A JAR is absent** — it is downloaded from the mirror below, hashed, and only
  then moved into place. A download that does not match its pinned SHA-256 is
  deleted and fails the build.

The [mirror](https://raw.githubusercontent.com/MiJey/TizenConsumerSAAgentV2/master/app/libs)
is unrelated to the vendor. Hash pinning verifies the expected bytes, not the
right to use them. The mirror and hashes permit recovery without committing JARs.

To fetch separately, after setting `JBR` as in [Development](../docs/development.md#toolchain):

```bash
./gradlew -Dorg.gradle.java.home="$JBR" :core:delivery:fetchAccessorySdk
```

## Licensing

These files remain subject to their vendor's SDK licence. This project's MIT
licence does not cover them and grants no rights in them. Before publishing,
redistributing or extracting this project, **satisfy yourself that you are
permitted to use them** — or drop `:core:delivery` and build without the watch
transport. See [`NOTICE.md`](../NOTICE.md).
