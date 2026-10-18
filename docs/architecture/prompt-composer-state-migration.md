# PromptComposerState prototype

**Status:** Historical headless prototype; not the production composer state owner (source audit 2026-10-18).

The prototype explored composer actions and send transitions without a Compose dependency.
Its [implementation](../../android-compose/sharedLogic/src/commonMain/kotlin/com/letta/mobile/data/chat/runtime/PromptComposerState.kt)
and tests remain, but there is no production caller. The generated
[PromptComposerState API](https://oculairmedia.github.io/letta-mobile/api/sharedLogic/com.letta.mobile.data.chat.runtime/-prompt-composer-state/index.html)
and [outgoing payload API](https://oculairmedia.github.io/letta-mobile/api/sharedLogic/com.letta.mobile.data.chat.runtime/-prompt-composer-outgoing-payload/index.html)
document that experiment.

Production [ChatComposerPanel](../../android-compose/sharedUI/src/commonMain/kotlin/com/letta/mobile/ui/chat/surface/composer/ChatComposerPanel.kt)
reads `ChatComposerUiState` and `ChatActions` through the shared chat session. Do not add the
prototype as a second state machine in the live send path; changes belong with that session's
composer ownership and the shared UI policy.
