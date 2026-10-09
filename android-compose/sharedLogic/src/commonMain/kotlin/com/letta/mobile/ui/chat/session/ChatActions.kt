package com.letta.mobile.ui.chat.session

import androidx.compose.runtime.Immutable
import com.letta.mobile.data.a2ui.A2uiAction
import com.letta.mobile.data.chat.projection.CanvasArtifactReceipt
import com.letta.mobile.data.chat.send.QueuedSendId
import com.letta.mobile.data.model.MessageContentPart
import com.letta.mobile.data.model.UiMessage
import com.letta.mobile.data.repository.modelcontrol.ReasoningEffortChoice
import com.letta.mobile.data.transport.appserver.AppServerPermissionMode

/**
 * letta-mobile-bglj6.1: every user intent the shared chat page can raise against the
 * conversation's owner.
 *
 * Android's `AdminChatViewModel` and desktop's `DesktopChatController` each implement
 * this behind [ChatSessionPort]. The page never calls a platform API to change chat
 * state; it calls one of these. Host navigation (open the canvas, open an agent,
 * pick a file) is not here; it is [ChatSurfaceHost]'s, because it changes what is on
 * screen rather than the conversation.
 *
 * An owner that cannot do something reports it through [ChatSurfaceCapabilities] so
 * the page hides the control, rather than offering a control that silently does nothing.
 */
interface ChatActions {
    // Composer
    fun updateComposerText(text: String)

    /** Sends (or queues, during a run) the current draft. */
    fun send()

    /** Sends [text] directly without touching the draft (starter prompts, "send again"). */
    fun sendText(text: String)

    fun attachImage(image: MessageContentPart.Image)

    fun removeAttachment(index: Int)

    fun reportComposerError(message: String)

    fun clearComposerError()

    fun runComposerCommand(command: ChatComposerCommand)

    fun uninstallComposerCommand(command: ChatComposerCommand)

    // Run
    /** Stops the active run. A second call while a stop is outstanding force-clears locally. */
    fun stopRun()

    /** Stops a background process or monitor on the server (letta-mobile-bzvro.21). */
    fun stopBackgroundProcess(processId: String) = Unit

    fun rerun(message: UiMessage)

    /**
     * letta-mobile-bzvro.15 (F15): branch the conversation from [message] into a new one and open
     * it ([ChatSurfaceCapabilities.fork]). The original conversation is not changed.
     */
    fun forkFromMessage(message: UiMessage) = Unit

    /**
     * letta-mobile-bzvro.16 (F16): fork just before the user's [message], open the fork, and put
     * the prompt's text in its composer to edit and send ([ChatSurfaceCapabilities.editAndResend]).
     */
    fun editAndResend(message: UiMessage) = Unit

    fun submitApproval(answer: ChatApprovalAnswer)

    fun submitA2uiAction(action: A2uiAction)

    fun dismissA2uiSurface(surfaceId: A2uiSurfaceId)

    fun markA2uiSnackbarShown(id: A2uiSnackbarId)

    // Send queue
    fun cancelQueuedSend(id: QueuedSendId)

    fun sendQueuedNow(id: QueuedSendId)

    fun resumeSendQueue()

    // Timeline
    fun toggleRunCollapsed(runId: ChatRunId)

    fun toggleReasoningExpanded(messageId: ChatMessageId)

    fun loadOlderMessages()

    fun releaseOlderMessages()

    fun expandTruncatedToolResult(messageId: ChatMessageId)

    /** Retries the conversation or connection load after a failure. */
    fun retryLoad()

    fun clearError()

    fun setFontScale(scale: Float)

    // Conversation settings
    fun selectModel(handle: ChatModelHandle, effort: ReasoningEffortChoice)

    fun changeWorkingDirectory(directory: ChatWorkingDirectory)

    /** letta-mobile-bzvro.13: asks for another permission mode on this conversation (owners with a mode chip). */
    fun setPermissionMode(mode: AppServerPermissionMode) = Unit

    // Search
    fun updateSearchQuery(query: String)

    fun clearSearch()

    // Goal (only when ChatSurfaceCapabilities.goals)
    /** Re-reads the conversation's goal status. */
    fun refreshGoalStatus()

    /** Asks the agent to keep working on the active goal. */
    fun continueGoal()
}

/**
 * What this owner supports. The page hides what is false; it never shows a control that
 * would do nothing.
 */
@Immutable
data class ChatSurfaceCapabilities(
    val attachImages: Boolean = true,
    val rerun: Boolean = true,
    val approvals: Boolean = true,
    val modelSwitch: Boolean = true,
    val workingDirectory: Boolean = false,
    val search: Boolean = false,
    val pagedHistory: Boolean = true,
    val fontScale: Boolean = true,
    /** Goal refresh/continue ([ChatActions.refreshGoalStatus], [ChatActions.continueGoal]). */
    val goals: Boolean = false,
    /** "Fork from here" on a message ([ChatActions.forkFromMessage]). */
    val fork: Boolean = false,
    /** "Edit and resend" on the user's prompt ([ChatActions.editAndResend]). */
    val editAndResend: Boolean = false,
) {
    companion object {
        val Default = ChatSurfaceCapabilities()
    }
}

/**
 * Navigation the page asks its host for. These change what is on screen, not the
 * conversation, so they stay with the host's navigation owner.
 *
 * Null members mean the host has nowhere to go and the page hides the affordance
 * (for example a host without a canvas passes `openCanvas = null`).
 */
@Immutable
data class ChatSurfaceHost(
    val openCanvas: (() -> Unit)? = null,
    /**
     * letta-mobile-bglj6.13: "Show on canvas" on a canvas_compose card: open the conversation's
     * board and frame [CanvasArtifactReceipt.bounds]. A page that draws the canvas itself binds
     * this to its own board (ChatSurface does, through ChatCanvasActions); a host that only has a
     * canvas route passes null and the card falls back to [openCanvas], without framing. An
     * explicit action only: composing never switches to the canvas on its own.
     */
    val showOnCanvas: ((CanvasArtifactReceipt) -> Unit)? = null,
    val openAgent: ((agentId: String) -> Unit)? = null,
    /**
     * Display name for an agent id (inter-agent provenance labels). Null, or a null result,
     * falls back to the short id label.
     */
    val resolveAgentName: ((agentId: String) -> String?)? = null,
    /**
     * Opens a dispatched subagent's activity (Android: its todo sheet, which offers the
     * subagent's conversation once the host resolves it). The page knows only the dispatch, so
     * it hands over the tool call id, the correlated subagent agent id when known, and the
     * dispatch's description for the sheet's title. Null hides the affordance.
     */
    val openSubagent: ((toolCallId: String, subagentAgentId: String?, description: String) -> Unit)? = null,
    val openModelPicker: (() -> Unit)? = null,
    val pickWorkingDirectory: (() -> Unit)? = null,
    /**
     * Shows the conversation's agent pane (desktop: the sidebar). The agent's mascot beside the
     * composer is the way in; null leaves the mascot unclickable.
     */
    val openAgentPane: (() -> Unit)? = null,
    /** Opens the conversation's agent in its editor (the mascot's pencil badge); null hides it. */
    val editAgent: (() -> Unit)? = null,
    /**
     * Opens the host's agent switcher. The phone's canvas mode offers it in the board's menu, in
     * place of the host header's agent pill, which that mode does not show; null leaves it out.
     */
    val openAgentSwitcher: (() -> Unit)? = null,
)
