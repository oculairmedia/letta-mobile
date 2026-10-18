# Chat pinch-to-zoom performance note

## Current policy

Chat pinch-to-zoom is intentionally **visual-realtime, layout-deferred**.

While the user's fingers are down, `TimelinePinchScale` updates a transient scale
and applies it to the message list through `Modifier.graphicsLayer`. That keeps
the gesture on the compositor path: no chat-wide text recomposition, markdown
reparse, LazyColumn remeasure, or tool-output relayout on every pointer frame.

When the user lifts, the gesture commits one snapped `fontScale` value through
`ChatActions.setFontScale`; the owning session/host persists that setting.
That is the only true typography/layout reflow in the current interaction.

<!-- live-path: android-compose/sharedUI/src/commonMain/kotlin/com/letta/mobile/ui/chat/surface/timeline/TimelinePinchZoom.kt -->
<!-- live-path: android-compose/sharedUI/src/jvmTest/kotlin/com/letta/mobile/ui/chat/surface/timeline/TimelinePinchPreviewUiTest.kt -->

Current shared implementation (repository-relative paths):

- `android-compose/sharedUI/src/commonMain/kotlin/com/letta/mobile/ui/chat/surface/timeline/TimelinePinchZoom.kt`
- `android-compose/sharedUI/src/jvmTest/kotlin/com/letta/mobile/ui/chat/surface/timeline/TimelinePinchPreviewUiTest.kt`

## Why not raw realtime text reflow

Raw realtime text reflow means pushing a new `fontScale` through the chat theme
on every pointer event or every tiny scale delta. That has already been tried in
this area and was replaced because it made realistic chats visibly unstable.

The expensive path is not just a single text size update. A committed
`fontScale` change can affect:

- visible message text and role labels;
- markdown rendering, including code blocks and tables;
- tool call cards and expanded tool outputs;
- generated UI cards;
- run blocks and reasoning sections;
- LazyColumn item measurement and scroll anchoring;
- any active `animateContentSize` or expand/collapse animation that was not
  suppressed for pinch.

That makes raw pointer-frame reflow scale with visible chat complexity:

`pointer frames * visible message/render surface complexity`

The current compositor path is closer to:

`pointer frames * one layer transform + one committed reflow on lift`

The trade-off is that text can look slightly raster-scaled during the gesture
and then snap once when the real layout commits. That trade-off is intentional.

## If we revisit fully realtime behavior

Do not replace the current path with pointer-frame text reflow directly. The
safer option is a hybrid checkpoint model:

1. Keep `transientPinchScale` driving `graphicsLayer` every pointer frame.
2. Commit real `activeFontScale` only at a bounded cadence, for example every
   `80-120 ms` or every `4-6%` scale delta.
3. Persist the setting only once, on gesture lift.
4. Keep `LocalChatIsPinching` active so expensive local animations remain
   suppressed during the gesture.
5. Profile before shipping.

The hybrid goal is to reduce the final release snap without paying full
recomposition and layout cost at raw pointer frequency.

## Acceptance gates for a realtime experiment

Use a release-like build on a physical Android device. Test all of these chats:

- plain text conversation near the bottom;
- long markdown response with code fences and tables;
- expanded tool outputs with highlighted shell/diff/json output;
- multi-step run blocks with consecutive tool calls;
- active streaming response while pinch starts;
- older-history loading boundary visible or near visible.

Success criteria:

- no visible strobe while pinching;
- no LazyColumn measurement crash;
- no repeated persistence writes during the gesture;
- scroll position remains understandable after lift;
- expanded tool outputs do not chug when multiple are open;
- JankStats or Perfetto evidence shows the hybrid path is at least as smooth as
  the current compositor path on realistic chat content.

If those gates are not met, keep the current visual-realtime, layout-deferred
implementation.
