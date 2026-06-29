## 2024-05-20 - Kotlin List Append Allocation
**Learning:** In Kotlin, using `+=` on mutable collections inside loops can cause implicit operator resolution overhead or unwanted copy semantics if not careful.
**Action:** Use `.add()` explicitly to ensure intended behavior and performance when updating mutable collections.
## 2024-06-29 - Optimize Fading Edges Recomposition
**Learning:** In Jetpack Compose, manually reading `firstVisibleItemScrollOffset` or `layoutInfo` inside `derivedStateOf` to calculate scrolling bounds forces continuous lambda re-evaluations on every single scroll frame.
**Action:** Use the built-in `canScrollBackward` and `canScrollForward` properties on `LazyListState` or `ScrollableState`, which are highly optimized to only trigger recomposition when the boolean state actually changes.
