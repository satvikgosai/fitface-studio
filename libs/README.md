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

```
https://raw.githubusercontent.com/MiJey/TizenConsumerSAAgentV2/master/app/libs
```

That mirror is an unrelated third-party repository, not a vendor distribution
channel. It is used only because both files are there unmodified — the copies it
serves are byte-for-byte identical to the hashes above. Pinning those hashes is
what makes an untrusted mirror safe to build from; it is not an endorsement of it,
and it says nothing about your right to use the files it serves.

The mirror plus these hashes is the backup. The JARs can always be re-obtained and
proven identical, which is why losing a local copy is not a problem worth solving
by committing them.

To fetch them without building anything else:

```bash
./gradlew :core:delivery:fetchAccessorySdk
```

## Licensing

These files remain subject to their vendor's SDK licence. This project's MIT
licence does not cover them and grants no rights in them. Before publishing,
redistributing or extracting this project, **satisfy yourself that you are
permitted to use them** — or drop `:core:delivery` and build without the watch
transport. See [`NOTICE.md`](../NOTICE.md).
