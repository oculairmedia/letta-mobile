# Shared-module layering ratchet

**Status:** Reference snapshot; implementation currency has not been revalidated. Consult the current module map and source before applying.

Bead: `letta-mobile-by4vm.27`. Gate: `SharedLayeringTest` in `android-compose/architecture-tests`,
run by `./gradlew :architecture-tests:architectureTest` inside the required CI `test` job.

## What is checked

| Baseline file (`android-compose/architecture-tests/`) | Rule | Count at introduction |
|---|---|---|
| `package-cycle-baseline.txt` | No two packages import each other. Scans `sharedLogic` `commonMain` + `jvmAndAndroid` and `sharedUI` `commonMain`, one import graph per module. | 36 (26 sharedLogic, 10 sharedUI) |
| `sharedlogic-ui-package-baseline.txt` | No `com.letta.mobile.ui.*` package in any `sharedLogic` production source set. UI belongs in `sharedUI`. | 7 |

An edge `A -> B` exists when a file in package `A` imports something from package `B` (nested
class, member and wildcard imports resolve to the longest declared package). Only pairs that
import each other count. Longer cycles are not reported yet.

Both baselines only shrink. The test fails when:

- a pair or package appears that is not in the baseline (`NEW`), or
- a baseline line no longer matches anything (`STALE`), or
- the baseline is not sorted or has duplicates.

`KotlinSourcePolicy.productionScope` also now covers every non-test source set, not just the
ones named `*Main`: sharedLogic `jvmAndAndroid`, every Android/JVM `main`, the app's
`debug`/`release`/`root`/`play`/`sideload` sets and desktop `phonePlayground`. None of them
broke a rule when they were added, so that check has no baseline.

## Shrinking a baseline

1. Pick a line, for example
   `sharedLogic com.letta.mobile.data.controller <-> com.letta.mobile.data.transport.appserver`.
2. Find the imports in both directions:
   `grep -rln '^import com.letta.mobile.data.transport.appserver\.' android-compose/sharedLogic/src/{commonMain,jvmAndAndroid}/kotlin/com/letta/mobile/data/controller/`
   and the same the other way round.
3. Break one direction. Usual fixes: move the shared type to the lower package (often a
   `.api` or `model` package), add an interface on the lower side that the upper side
   implements, or move the file to the package that uses it. For a `ui.*` package in
   `sharedLogic`, move it to `sharedUI`. If it holds state or logic, move that part to a
   `data.*` / `presentation.*` package instead.
4. Delete the line from the baseline in the same change. The test fails with `STALE` until
   you do, so a fix cannot leave the slot open for a new cycle.
5. Run `./gradlew :architecture-tests:architectureTest`.

Never add a line. If a change creates a new pair, fix the import. The baseline lists debt
that already existed. It is not a waiver list.
