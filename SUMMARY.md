## Tap navigates with local conversation id — please test

**What I changed:** in `AgentScaffoldChromeScaffold` (the chat shell), the `onAgentClick` callback now passes `state.conversationId` (PM's local conversation id) as the second argument to `onSwitchConversation`, replacing the previous `null`.

**Honest caveat:** conversation ids are per-agent in this repo's model (each `Conversation` has its own `id` and `agent_id`). When you tap "Meridian" from PM's chat, I don't have a direct id for "Meridian's view of THIS conversation" — PM's local id might or might not match. The appserver might:
  a. accept it and open the right conversation (best case),
  b. open an empty / error state,
  c. redirect somewhere else.

This is a best-effort guess at what the appserver does. If the tap lands you on the right conversation, we're done. If not, the right fix requires either:
  - A new appserver query to find the recipient-side conversation containing a given message id, or
  - Surfacing `routingConversationId` from the `agent_message_send` tool result (currently only `msgId` + `to` are in the result JSON).

Tell me what the appserver actually did when you tap — that decides whether this iteration is sufficient or we need to escalate.

**Validation:** `:feature-chat:compileDebugKotlin` green. Dev APK rebuilt and reinstalled on the Pixel.
