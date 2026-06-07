package com.letta.mobile.data.chat.runtime

import com.letta.mobile.data.attachment.AttachmentLimits
import com.letta.mobile.data.model.MessageContentPart

object ChatComposerPolicy {
    fun updateText(
        state: ChatComposerState,
        text: String,
    ): ChatComposerState =
        state.copy(text = text)

    fun attachImage(
        state: ChatComposerState,
        image: MessageContentPart.Image,
        limits: AttachmentLimits = AttachmentLimits.Default,
    ): ChatComposerState {
        if (state.pendingImageAttachments.size >= limits.maxAttachmentCount) {
            return state.copy(error = ChatComposerError.MaxAttachmentCountExceeded)
        }
        if (state.pendingImageAttachments.sumOf { it.base64.length } + image.base64.length >
            limits.maxTotalBase64Bytes
        ) {
            return state.copy(error = ChatComposerError.MaxTotalBase64BytesExceeded)
        }
        return state.copy(
            pendingImageAttachments = state.pendingImageAttachments + image,
            error = null,
        )
    }

    fun removeImageAttachment(
        state: ChatComposerState,
        index: Int,
    ): ChatComposerState =
        state.copy(
            pendingImageAttachments = state.pendingImageAttachments.filterIndexed { i, _ -> i != index },
            error = null,
        )

    fun showAttachmentLoadFailed(state: ChatComposerState): ChatComposerState =
        state.copy(error = ChatComposerError.AttachmentLoadFailed)

    fun beginSend(state: ChatComposerState): ChatComposerSendDraft? {
        val text = state.text.trim()
        val attachments = state.pendingImageAttachments
        if (text.isBlank() && attachments.isEmpty()) return null
        return ChatComposerSendDraft(
            text = text,
            attachments = attachments,
            nextState = ChatComposerState(),
        )
    }

    fun restoreAfterSendFailure(
        text: String,
        attachments: List<MessageContentPart.Image>,
    ): ChatComposerState =
        ChatComposerState(
            text = text,
            pendingImageAttachments = attachments,
        )
}

data class ChatComposerSendDraft(
    val text: String,
    val attachments: List<MessageContentPart.Image>,
    val nextState: ChatComposerState,
)
