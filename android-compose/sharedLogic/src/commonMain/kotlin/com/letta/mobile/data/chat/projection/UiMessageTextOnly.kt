package com.letta.mobile.data.chat.projection

import com.letta.mobile.data.model.UiMessage

/**
 * Whether the message carries its text and nothing else: no tool call, generated UI, approval,
 * attachment or canvas card (letta-mobile-bglj6.13). Only such a message can be an echo the chat
 * folds away; one carrying anything more is always shown.
 */
internal fun UiMessage.carriesOnlyText(): Boolean =
    toolCalls.isNullOrEmpty() &&
        generatedUi == null &&
        approvalRequest == null &&
        approvalResponse == null &&
        attachments.isEmpty() &&
        artifacts.isEmpty()
