# Compose performance notes

**Status:** Corrected during the 2026-10-18 source/documentation audit. Earlier notes
incorrectly equated derived-state recalculation with consumer recomposition.

- Use `derivedStateOf` when a frequently changing input produces a less frequently
  changing result, such as crossing a scroll threshold. It can recalculate while
  still preventing consumer recomposition when the result is unchanged.
- Prefer `canScrollForward`/`canScrollBackward` for simple bounds; they already expose
  state. Do not add a redundant derived-state wrapper around the same Boolean.
- Use `snapshotFlow` for side effects. Observing only `firstVisibleItemIndex` is
  insufficient when the desired condition can change while that index stays fixed
  (for example a sticky-header pixel boundary or content/viewport resize).
- Do not assume a visible-items list order without checking reverse-layout and
  pinned-item behavior. `maxOfOrNull { it.index }` explicitly asks for the maximum;
  replacing it with `lastOrNull` needs an ordering invariant and evidence.
- `.add()` expresses mutation clearly; do not claim a measured `+=` allocation or
  operator-resolution cost without checking its receiver type and profiling.

Repository evidence: shared `TimelineScrollToLatestVisibility.kt`, `PagedTimelineList.kt`
and `TimelinePinchZoom.kt` under `android-compose/sharedUI/src/commonMain/kotlin/com/letta/mobile/ui/chat/surface/timeline/`.
Framework semantics: [Android Compose side effects and derived state](https://developer.android.com/develop/ui/compose/side-effects#derivedstateof).
