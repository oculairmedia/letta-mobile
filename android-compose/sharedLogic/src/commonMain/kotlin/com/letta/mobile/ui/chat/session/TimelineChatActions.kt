package com.letta.mobile.ui.chat.session

import com.letta.mobile.data.model.MessageContentPart

/*
 * letta-mobile-o4ygk.4.5: the page's intents, forwarded to a TimelineChatSessionPort.
 */

/** [ChatActions] over a [TimelineChatSessionPort]; the extension points answer the rest. */
internal class TimelineChatActions(
    private val port: TimelineChatSessionPort,
    options: TimelineChatSessionOptions,
    state: ChatExtensionActionState,
) : ChatExtensionActions(options, state) {
    override fun updateComposerText(text: String) = port.updateDraft { it.withText(text) }

    override fun send() = port.sendDraft()

    override fun sendText(text: String) = port.sendDirect(TimelineSend(text.trim(), emptyList()))

    override fun attachImage(image: MessageContentPart.Image) = port.updateDraft { it.withImage(image, port.attachmentLimits) }

    override fun removeAttachment(index: Int) = port.updateDraft { it.withoutImage(index) }

    override fun reportComposerError(message: String) = port.updateDraft { it.copy(hostError = message) }

    override fun clearComposerError() = port.updateDraft { it.cleared() }

    override fun stopRun() = port.stopRun()

    override fun submitApproval(answer: ChatApprovalAnswer) = port.answerApproval(answer)

    override fun retryLoad() = port.retryLoad()
}
