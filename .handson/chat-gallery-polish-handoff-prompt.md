# Handoff Prompt — Chat UX Polish vs Google Gallery

You are working in `/opt/stacks/letta-mobile` on the Letta Mobile Android Compose chat UI. The user asked to ignore Google Gallery parity items **1, 6, 8, and 9** from the review, and to ignore **camera and audio** support for now. Do **not** spend time on Run Again discoverability, composer camera/audio, input-history send-vs-insert behavior, or benchmark actions unless explicitly re-scoped.

Use `bd` for task tracking. Start with:

```bash
bd prime
bd show letta-mobile-ykt1 letta-mobile-zcgc letta-mobile-wbof letta-mobile-iio2 letta-mobile-nvot
```

## Issues created

1. `letta-mobile-ykt1` — Make chat auto-scroll follow streaming content only near bottom
2. `letta-mobile-zcgc` — Persist and manage chat input history
3. `letta-mobile-wbof` — Dismiss chat composer focus when message list scrolls
4. `letta-mobile-iio2` — Add full-screen pager image viewer for chat attachments
5. `letta-mobile-nvot` — Replace chat copy Toast with snackbar or in-app banner

## Source references

Google Gallery checkout used for comparison:

- `/tmp/google-gallery/Android/src/app/src/main/java/com/google/ai/edge/gallery/ui/common/chat/ChatPanel.kt`
- `/tmp/google-gallery/Android/src/app/src/main/java/com/google/ai/edge/gallery/ui/common/chat/MessageInputText.kt`
- `/tmp/google-gallery/Android/src/app/src/main/java/com/google/ai/edge/gallery/ui/common/chat/TextInputHistorySheet.kt`
- `/tmp/google-gallery/Android/src/app/src/main/java/com/google/ai/edge/gallery/ui/common/chat/ChatView.kt`
- `/tmp/google-gallery/Android/src/app/src/main/java/com/google/ai/edge/gallery/ui/common/FloatingBanner.kt`

Letta Mobile files likely involved:

- `android-compose/app/src/main/java/com/letta/mobile/ui/screens/chat/ChatMessageList.kt`
- `android-compose/app/src/main/java/com/letta/mobile/ui/screens/chat/ChatScreen.kt`
- `android-compose/app/src/main/java/com/letta/mobile/ui/screens/chat/ChatComposer.kt`
- `android-compose/app/src/main/java/com/letta/mobile/ui/screens/chat/ChatComposerController.kt`
- `android-compose/app/src/main/java/com/letta/mobile/ui/screens/chat/AdminChatViewModel.kt`
- `android-compose/app/src/main/java/com/letta/mobile/ui/screens/chat/ChatMessageComponents.kt`
- `android-compose/app/src/main/java/com/letta/mobile/ui/screens/chat/MessageAttachmentsGrid.kt`
- `android-compose/core/src/main/java/com/letta/mobile/data/model/UiMessage.kt`
- `android-compose/designsystem/src/main/java/com/letta/mobile/ui/components/FloatingBanner.kt`
- `android-compose/app/src/main/java/com/letta/mobile/ui/common/SnackbarDispatcher.kt`

## Implementation guidance

### `letta-mobile-ykt1` — streaming-aware auto-scroll

Current Letta behavior in `ChatMessageList.kt` observes `messageCount` and scrolls to item `0` in reverseLayout only when `isNearBottom`. This misses cases where the newest assistant message grows during streaming without changing message count.

Implement an additional streaming-aware trigger. Options:

- observe last message id + content length + latency/tool status where relevant, or
- derive a compact `streamingScrollKey` from `state.messages.lastOrNull()` and collect it with `snapshotFlow`.

Keep these constraints:

- `reverseLayout = true`, newest item is index `0`.
- Only scroll when `isNearBottom` is true.
- Do not fight `scrollToMessageId` search-result jumps.
- Do not trigger older-message pagination incorrectly.

Gallery reference: `ChatPanel.kt` has a `LaunchedEffect(lastMessage.value, lastMessageContent.value, lastMessage.value?.latencyMs)` that scrolls only if the last visible item is close to viewport end.

### `letta-mobile-zcgc` — persistent input history

Current Letta history is in-memory in `ChatComposerState.inputHistory` and updated in `ChatComposerController.clearAfterSend()`.

Add persistent storage, probably in `SettingsRepository` or a small dedicated repository. Requirements:

- load existing history into the composer/viewmodel
- add/promote nonblank prompt on send
- dedupe duplicates
- cap history size, e.g. 30
- delete one prompt
- clear all prompts with confirmation

Update `ChatComposer.kt` `InputHistorySheet` to support delete/clear actions. Use repo design-system patterns (`ConfirmDialog` for clear-all confirmation) rather than raw `AlertDialog`.

Keep current behavior where selecting a prompt inserts it into the composer; do not change to immediate send unless re-scoped.

### `letta-mobile-wbof` — focus dismissal on scroll

Add chat-owned keyboard/focus dismissal when the user scrolls the message list.

Likely approach:

- in `ChatMessageList.kt`, get `LocalFocusManager.current`
- add a `NestedScrollConnection`
- clear focus on intentional user scroll
- attach via `Modifier.nestedScroll(...)` without breaking the existing `pointerInput` pinch-to-font-scale modifier

Be careful:

- Programmatic `animateScrollToItem(0)` should not clear focus unnecessarily.
- Search result jumps should not clear focus unnecessarily.
- Pinch font scaling must still work.

Gallery reference: `ChatPanel.kt` clears focus in `onPreScroll` when scrolling down.

### `letta-mobile-iio2` — full-screen image viewer

Current attachments are rendered statically in `MessageAttachmentsGrid.kt`.

Implement tap-to-view:

- make `MessageAttachmentsGrid` accept an optional callback like `onAttachmentClick(index, attachments)`
- thread callback through `ChatMessageComponents.kt`, `ChatMessageList.kt`, and `ChatScreen.kt`
- in `ChatScreen.kt`, keep selected image list/index state
- render a full-screen overlay with `AnimatedVisibility`, `HorizontalPager`, and an accessible close button
- support back/dismiss if practical

First pass can be fit-to-screen only. Zoom is optional unless scoped in the issue. Keep decoding remembered/cached and avoid raw hex colors.

Gallery reference: `ChatView.kt` image viewer overlay.

### `letta-mobile-nvot` — replace chat Toast

Current chat copy path in `ChatMessageComponents.kt` imports `android.widget.Toast` and calls `Toast.makeText(...)` after copying.

Replace that with an existing Compose-owned transient surface:

- preferred: `LocalSnackbarDispatcher` if plumbing is simple
- acceptable: chat-local `FloatingBanner` or another existing in-repo pattern

Do not invent a new transient component. Keep clipboard behavior unchanged.

## Suggested execution order

1. `letta-mobile-ykt1` — visible behavior bug/polish, important for streaming chat.
2. `letta-mobile-wbof` — small, localized, good follow-up while in `ChatMessageList.kt`.
3. `letta-mobile-nvot` — small cleanup.
4. `letta-mobile-zcgc` — larger state/persistence change.
5. `letta-mobile-iio2` — larger UI plumbing and overlay.

## Verification

Run relevant unit tests after changes. At minimum, for code changes in Android Compose:

```bash
cd android-compose
./gradlew testDebugUnitTest
```

If a full unit run is too slow, run targeted tests for the touched module/classes and document what was run.

Manual verification checklist:

- Streaming response follows only when already near bottom.
- User-scrolled-away transcript is not yanked to bottom.
- Composer focus clears on manual chat scroll.
- Input history persists after recreation/restart and supports delete/clear.
- Chat attachment tap opens full-screen pager and closes cleanly.
- Copy message feedback no longer uses Toast.

Remember project workflow: update/close beads, run quality gates, and push if a remote is configured. This repo currently reported no git remote in `bd prime`, but still verify `git status` before handoff.
