# Internal documentation

How FitFace Studio works underneath. The [top-level README](../README.md) covers
what the app does and how to use it; everything here is implementation detail.

| Document | Covers |
| --- | --- |
| [architecture.md](architecture.md) | Modules, data flow, state ownership, caches, and the six invariants |
| [bin-format.md](bin-format.md) | The container format byte by byte, derived from the corpus; §14 lists related public work |
| [editing.md](editing.md) | Which edits are safe, what the catalogue sweep proved, and why |
| [direct-install.md](direct-install.md) | Accessory discovery, the OTA/RFCOMM transfer, security posture |
| [development.md](development.md) | Toolchain, build, the uncommitted corpus, decoding by hand, manual verification |

Changing the code: [`CONTRIBUTING.md`](../CONTRIBUTING.md) is the procedure and
[`AGENTS.md`](../AGENTS.md) is the danger map — the traps that already bit this
codebase.

## Honesty about evidence

These documents distinguish three levels, and the distinction is load-bearing:

- **proven** — an arithmetic invariant tested against every matching record in the
  corpus, or a value confirmed against an embedded preview raster;
- **supported** — consistent across every available record, with too few distinct
  examples to exclude coincidence;
- **unknown** — preserved verbatim, with no reading offered.

Anything the code depends on is in the first category or is fail-closed.
