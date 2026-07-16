## 2024-05-20 - Kotlin List Append Allocation
**Learning:** In Kotlin, using `+=` on mutable collections inside loops can cause implicit operator resolution overhead or unwanted copy semantics if not careful.
**Action:** Use `.add()` explicitly to ensure intended behavior and performance when updating mutable collections.
## 2024-05-20 - Compose Scroll Bounds Evaluation
**Learning:** Manually reading `scrollState.firstVisibleItemScrollOffset` or `scrollState.layoutInfo` inside `remember { derivedStateOf { ... } }` defeats the purpose of the derivation during scroll, because the layout info changes on every single frame, forcing continuous lambda re-evaluations and creating unnecessary allocations.
**Action:** Prefer using the natively provided `scrollState.canScrollBackward` and `scrollState.canScrollForward` which update optimally and prevent expensive re-evaluations during active scrolling.
