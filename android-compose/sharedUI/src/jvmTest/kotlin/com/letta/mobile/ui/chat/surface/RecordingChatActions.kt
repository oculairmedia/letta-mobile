package com.letta.mobile.ui.chat.surface

import com.letta.mobile.data.a2ui.A2uiAction
import com.letta.mobile.data.chat.send.QueuedSendId
import com.letta.mobile.data.model.MessageContentPart
import com.letta.mobile.data.model.UiMessage
import com.letta.mobile.data.repository.modelcontrol.ReasoningEffortChoice
import com.letta.mobile.ui.chat.session.A2uiSnackbarId
import com.letta.mobile.ui.chat.session.A2uiSurfaceId
import com.letta.mobile.ui.chat.session.ChatActions
import com.letta.mobile.ui.chat.session.ChatApprovalAnswer
import com.letta.mobile.ui.chat.session.ChatComposerCommand
import com.letta.mobile.ui.chat.session.ChatMessageId
import com.letta.mobile.ui.chat.session.ChatModelHandle
import com.letta.mobile.ui.chat.session.ChatRunId
import com.letta.mobile.ui.chat.session.ChatWorkingDirectory

/**
 * letta-mobile-bglj6.1: the one recording [ChatActions] for the shared page's tests (composer,
 * timeline and rows). Every call lands in [calls] by name (with its key where one identifies
 * it, e.g. `removeAttachment:0`); the arguments a test asserts on are kept in typed lists.
 *
 * @param onText lets a test feed the draft back, as an owner would.
 */
internal class RecordingChatActions(private val onText: (String) -> Unit = {}) : ChatActions {
    val calls = mutableListOf<String>()

    // Composer
    val texts = mutableListOf<String>()
    val sent = mutableListOf<String>()
    val commandsRun = mutableListOf<ChatComposerCommand>()
    val uninstalled = mutableListOf<ChatComposerCommand>()
    val models = mutableListOf<Pair<String, ReasoningEffortChoice>>()

    // Run
    val reruns = mutableListOf<UiMessage>()
    val approvals = mutableListOf<Approval>()
    val shownSnackbars = mutableListOf<Long>()
    val dismissedSurfaces = mutableListOf<String>()

    // Timeline
    val toggledRuns = mutableListOf<String>()
    val toggledReasoning = mutableListOf<String>()
    val expandedTruncations = mutableListOf<String>()
    val fontScales = mutableListOf<Float>()

    val retries: Int get() = count("retryLoad")
    val loadOlderCalls: Int get() = count("loadOlderMessages")
    val releaseOlderCalls: Int get() = count("releaseOlderMessages")
    val clearedErrors: Int get() = count("clearError")

    data class Approval(val requestId: String, val toolCallIds: List<String>, val approve: Boolean, val reason: String?)

    fun count(name: String): Int = calls.count { it == name }

    private fun record(name: String) {
        calls += name
    }

    override fun updateComposerText(text: String) {
        record("updateComposerText")
        texts += text
        onText(text)
    }

    override fun send() = record("send")

    override fun sendText(text: String) {
        record("sendText")
        sent += text
    }

    override fun attachImage(image: MessageContentPart.Image) = record("attachImage")

    override fun removeAttachment(index: Int) = record("removeAttachment:$index")

    override fun reportComposerError(message: String) = record("reportComposerError")

    override fun clearComposerError() = record("clearComposerError")

    override fun runComposerCommand(command: ChatComposerCommand) {
        record("runComposerCommand")
        commandsRun += command
    }

    override fun uninstallComposerCommand(command: ChatComposerCommand) {
        record("uninstallComposerCommand")
        uninstalled += command
    }

    override fun stopRun() = record("stopRun")

    override fun rerun(message: UiMessage) {
        record("rerun")
        reruns += message
    }

    val forks = mutableListOf<UiMessage>()
    val edits = mutableListOf<UiMessage>()

    override fun forkFromMessage(message: UiMessage) {
        record("forkFromMessage")
        forks += message
    }

    override fun editAndResend(message: UiMessage) {
        record("editAndResend")
        edits += message
    }

    override fun submitApproval(answer: ChatApprovalAnswer) {
        record("submitApproval")
        approvals += Approval(answer.requestId, answer.toolCallIds, answer.approve, answer.reason)
    }

    override fun submitA2uiAction(action: A2uiAction) = record("submitA2uiAction")

    override fun dismissA2uiSurface(surfaceId: A2uiSurfaceId) {
        record("dismissA2uiSurface")
        dismissedSurfaces += surfaceId.value
    }

    override fun markA2uiSnackbarShown(id: A2uiSnackbarId) {
        record("markA2uiSnackbarShown")
        shownSnackbars += id.value
    }

    override fun cancelQueuedSend(id: QueuedSendId) = record("cancelQueuedSend:${id.value}")

    override fun sendQueuedNow(id: QueuedSendId) = record("sendQueuedNow:${id.value}")

    override fun resumeSendQueue() = record("resumeSendQueue")

    override fun toggleRunCollapsed(runId: ChatRunId) {
        record("toggleRunCollapsed")
        toggledRuns += runId.value
    }

    override fun toggleReasoningExpanded(messageId: ChatMessageId) {
        record("toggleReasoningExpanded")
        toggledReasoning += messageId.value
    }

    override fun loadOlderMessages() = record("loadOlderMessages")

    override fun releaseOlderMessages() = record("releaseOlderMessages")

    override fun expandTruncatedToolResult(messageId: ChatMessageId) {
        record("expandTruncatedToolResult")
        expandedTruncations += messageId.value
    }

    override fun retryLoad() = record("retryLoad")

    override fun clearError() = record("clearError")

    override fun setFontScale(scale: Float) {
        record("setFontScale")
        fontScales += scale
    }

    override fun selectModel(handle: ChatModelHandle, effort: ReasoningEffortChoice) {
        record("selectModel")
        models += handle.value to effort
    }

    override fun changeWorkingDirectory(directory: ChatWorkingDirectory) = record("changeWorkingDirectory")

    override fun updateSearchQuery(query: String) = record("updateSearchQuery")

    override fun clearSearch() = record("clearSearch")

    override fun refreshGoalStatus() = record("refreshGoalStatus")

    override fun continueGoal() = record("continueGoal")
}
