# PromptComposerState migration note

**Status:** Implemented headless prototype; not the production composer state owner.

## Code audit — 2026-10-18

[PromptComposerState](../../android-compose/sharedLogic/src/commonMain/kotlin/com/letta/mobile/data/chat/runtime/PromptComposerState.kt) and `PromptComposerStateTest` remain, but a repository-wide symbol search finds no production caller outside its declaration. `beginSend` trims the payload and resets to a Sending state; `beginStreaming` only transitions from Sending and `stop` returns to Ready. There is no `markReady()` method, despite the old wiring suggestion below. Production [ChatComposerPanel](../../android-compose/sharedUI/src/commonMain/kotlin/com/letta/mobile/ui/chat/surface/composer/ChatComposerPanel.kt) reads `ChatComposerUiState`/`ChatActions` from the shared session. Do not introduce this second state machine into the live send path merely to complete the prototype's suggested migration.

## Original record

`PromptComposerState` lives in `sharedLogic/src/commonMain/kotlin/com/letta/mobile/data/chat/runtime` as a small, headless state container for composer actions. It intentionally does not depend on Compose UI or prompt-bar.

## First-pass scope

- Tracks draft text and ready/sending/streaming lifecycle state.
- Derives `canSend`, `canStop`, `isSending`, `isStreaming`, and `ready` from state.
- Produces a `PromptComposerOutgoingPayload` during `beginSend()` and returns a reset sending state for UI/viewmodel handoff.
- Includes cheap extension slots for attachment, template, and model metadata without implementing prompt-bar feature surfaces.

## Suggested wiring path

1. Keep existing composer UI behavior unchanged until the send path can be adapted safely.
2. In the chat viewmodel, map existing text and image draft fields into `PromptComposerState`.
3. Call `beginSend()` from the existing send action, pass `PromptComposerOutgoingPayload` into the current message send gateway, and render `nextState` immediately so the visible draft resets.
4. Move stream callbacks to `beginStreaming()`, `markReady()`, and `stop()` once the active stream cancellation path is consolidated.

The current PR stops at the shared headless contract and tests because replacing the live composer wiring would touch UI, image attachment policy, and runtime send cancellation in one change.
