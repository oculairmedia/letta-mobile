## 2024-05-20 - Kotlin List Append Allocation
**Learning:** In Kotlin, using `+=` on mutable collections inside loops can cause implicit operator resolution overhead or unwanted copy semantics if not careful.
**Action:** Use `.add()` explicitly to ensure intended behavior and performance when updating mutable collections.
## 2024-05-20 - Compose Scroll Bounds Evaluation
**Learning:** Manually reading `scrollState.firstVisibleItemScrollOffset` or `scrollState.layoutInfo` inside `remember { derivedStateOf { ... } }` defeats the purpose of the derivation during scroll, because the layout info changes on every single frame, forcing continuous lambda re-evaluations and creating unnecessary allocations.
**Action:** Prefer using the natively provided `scrollState.canScrollBackward` and `scrollState.canScrollForward` which update optimally and prevent expensive re-evaluations during active scrolling.
## 2024-05-20 - Compose Safe Pagination Check
**Learning:** In Jetpack Compose, avoid reading `LazyListState.layoutInfo` (including `visibleItemsInfo` and `totalItemsCount`) directly inside `derivedStateOf`, as its internal pixel offsets change constantly and will trigger recompositions on every pixel scrolled. For pagination or bounds checking, use `snapshotFlow { listState.firstVisibleItemIndex }` in a `LaunchedEffect` to emit only on item index changes, then safely read `listState.layoutInfo` inside the asynchronous `collect` block to accurately calculate exact item bounds without causing pixel-scroll stutter.
**Action:** Use `snapshotFlow { listState.firstVisibleItemIndex }` and defer `layoutInfo` access to the collector block when checking complex list boundaries.
## 2026-08-05 - Remove redundant derivedStateOf wrappers
**Learning:** In Jetpack Compose, wrapping state-backed properties like `LazyListState.canScrollForward` or `canScrollBackward` inside a `derivedStateOf` block is redundant. These properties are already backed by Compose `State<Boolean>` and their outputs change at the exact same rate as their inputs. Wrapping them wastes memory and observation overhead without reducing recompositions.
**Action:** Always read natively state-backed boolean properties (like scroll direction states) directly instead of wrapping them in `derivedStateOf`.
## 2024-10-25 - Replace maxOfOrNull on visibleItemsInfo
**Learning:** In Jetpack Compose, `LazyListState.layoutInfo.visibleItemsInfo` is inherently sorted by index representing the currently visible items on the screen. Using `maxOfOrNull { it.index }` forces the creation of an iterator and evaluates a lambda for each element. This wastes memory per evaluation frame.
**Action:** When finding the maximum index of visible items, always use `lastOrNull()?.index` which relies on O(1) list access to get the last element (which will always have the maximum index) and avoids iterator allocations.
## 2024-10-25 - Avoid derivedStateOf for Sticky Header Top Bounds
**Learning:** In Jetpack Compose, reading `listState.layoutInfo.visibleItemsInfo` inside `derivedStateOf` to check if a sticky header is pinned forces the lambda to re-evaluate continuously on every scroll pixel, as `layoutInfo` allocates new instances per frame.
**Action:** Replace `derivedStateOf` with `snapshotFlow { listState.firstVisibleItemIndex }` in a `LaunchedEffect`, map the index to the stable underlying data array (e.g., `rows.getOrNull(index - 1)`), and update a `mutableStateOf`. This prevents severe GC pressure and scroll jank by triggering only when the visible item index actually changes, rather than on every pixel.
