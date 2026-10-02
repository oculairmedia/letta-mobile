package com.letta.mobile.ui.chat.session

import com.letta.mobile.data.a2ui.A2uiAction
import com.letta.mobile.data.chat.send.QueuedSendId
import com.letta.mobile.data.model.MessageContentPart
import com.letta.mobile.data.model.UiMessage
import com.letta.mobile.data.repository.modelcontrol.ReasoningEffortChoice

/*
 * letta-mobile-o4ygk.4.5: the page's intents, forwarded to a TimelineChatSessionPort. The raw ids
 * and values in these signatures are ChatActions' own; typed ids on the interface are
 * letta-mobile-bglj6.1.10.1.
 */

/** [ChatActions] over a [TimelineChatSessionPort]; what it cannot do is a no-op its capabilities hide. */
internal class TimelineChatActions(private val port: TimelineChatSessionPort) : ChatActions {
    override fun updateComposerText(text: String) = port.updateDraft { it.withText(text) }

    override fun send() = port.sendDraft()

    override fun sendText(text: String) = port.sendDirect(TimelineSend(text.trim(), emptyList()))

    override fun attachImage(image: MessageContentPart.Image) = port.updateDraft { it.withImage(image, port.attachmentLimits) }

    override fun removeAttachment(index: Int) = port.updateDraft { it.withoutImage(index) }

    override fun reportComposerError(message: String) = port.updateDraft { it.copy(hostError = message) }

    override fun clearComposerError() = port.updateDraft { it.cleared() }

    override fun runComposerCommand(command: ChatComposerCommand) = Unit

    override fun uninstallComposerCommand(command: ChatComposerCommand) = Unit

    override fun stopRun() = port.stopRun()

    override fun rerun(message: UiMessage) = Unit

    override fun submitApproval(requestId: String, toolCallIds: List<String>, approve: Boolean, reason: String?) =
        port.answerApproval(ChatApprovalAnswer(requestId, toolCallIds, approve, reason))

    override fun submitA2uiAction(action: A2uiAction) = Unit

    override fun dismissA2uiSurface(surfaceId: String) = Unit

    override fun markA2uiSnackbarShown(id: Long) = Unit

    override fun cancelQueuedSend(id: QueuedSendId) = Unit

    override fun sendQueuedNow(id: QueuedSendId) = Unit

    override fun resumeSendQueue() = Unit

    override fun toggleRunCollapsed(runId: String) = port.updateLocal { it.toggleRun(runId) }

    override fun toggleReasoningExpanded(messageId: String) = port.updateLocal { it.toggleReasoning(messageId) }

    override fun loadOlderMessages() = Unit

    override fun releaseOlderMessages() = Unit

    override fun expandTruncatedToolResult(messageId: String) = Unit

    override fun retryLoad() = port.retryLoad()

    /** The page showed the error: acknowledge it so it is not shown again; the glow keeps "failed". */
    override fun clearError() {
        val shown = port.currentRun.error ?: return
        port.updateRun { it.copy(acknowledgedError = shown) }
    }

    override fun setFontScale(scale: Float) = Unit

    override fun selectModel(handle: String, effort: ReasoningEffortChoice) = Unit

    override fun changeWorkingDirectory(path: String) = Unit

    override fun updateSearchQuery(query: String) = Unit

    override fun clearSearch() = Unit

    override fun refreshGoalStatus() = Unit

    override fun continueGoal() = Unit
}
