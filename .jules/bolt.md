## 2024-05-20 - Kotlin List Append Allocation
**Learning:** In Kotlin, using `+=` on mutable collections inside loops can cause implicit operator resolution overhead or unwanted copy semantics if not careful.
**Action:** Use `.add()` explicitly to ensure intended behavior and performance when updating mutable collections.
## 2024-05-20 - Compose Scroll Bounds Evaluation
**Learning:** Manually reading `scrollState.firstVisibleItemScrollOffset` or `scrollState.layoutInfo` inside `remember { derivedStateOf { ... } }` defeats the purpose of the derivation during scroll, because the layout info changes on every single frame, forcing continuous lambda re-evaluations and creating unnecessary allocations.
**Action:** Prefer using the natively provided `scrollState.canScrollBackward` and `scrollState.canScrollForward` which update optimally and prevent expensive re-evaluations during active scrolling.
## 2024-05-20 - Compose Safe Pagination Check
**Learning:** In Jetpack Compose, avoid reading `LazyListState.layoutInfo` (including `visibleItemsInfo` and `totalItemsCount`) directly inside `derivedStateOf`, as its internal pixel offsets change constantly and will trigger recompositions on every pixel scrolled. For pagination or bounds checking, use `snapshotFlow { listState.firstVisibleItemIndex }` in a `LaunchedEffect` to emit only on item index changes, then safely read `listState.layoutInfo` inside the asynchronous `collect` block to accurately calculate exact item bounds without causing pixel-scroll stutter.
**Action:** Use `snapshotFlow { listState.firstVisibleItemIndex }` and defer `layoutInfo` access to the collector block when checking complex list boundaries.
## 2026-08-05 - Zero-allocation DerivedStateOf
**Learning:** In Jetpack Compose, performing sequence operations (like `asSequence().mapNotNull().map().firstOrNull()`) inside `derivedStateOf` blocks that read rapidly changing values like scroll layout info causes severe GC pressure and frame drops due to repeated iterator and lambda allocations on every scroll frame. Replacing these with simple `for` loops operating directly on indices avoids this allocation entirely.
**Action:** Always favor zero-allocation `for` loops over sequence/collection operators inside frequently re-evaluated Compose state blocks (like scroll position readers) to maintain smooth UI performance.
