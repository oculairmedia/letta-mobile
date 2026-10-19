package com.letta.mobile.ui.chat.session

import com.letta.mobile.data.a2ui.A2uiAction
import com.letta.mobile.data.chat.send.QueuedSendId
import com.letta.mobile.data.model.UiMessage
import com.letta.mobile.data.repository.modelcontrol.ReasoningEffortChoice
import com.letta.mobile.data.transport.appserver.AppServerPermissionMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/** The page state [ChatExtensionActions] reads and the view state it keeps. */
class ChatExtensionActionState(
    /** Collapsed runs, expanded reasoning and the acknowledged page error. */
    val local: MutableStateFlow<TimelineLocalState>,
    /** The page's timeline now, which a fork or edit plans against. */
    val branchView: () -> ChatBranchView,
    /** The owner's error now, shown or not; dismissing the page's snackbar acknowledges it. */
    val error: () -> String?,
)

/**
 * The [ChatActions] a host's extension points ([TimelineChatSessionOptions])
 * and the shared view state answer, the same for every session port. A port implements only what
 * its conversation owner does (the draft, sending, stop, approvals, reload); anything with no
 * extension point bound is a no-op its capabilities hide.
 */
abstract class ChatExtensionActions(
    options: TimelineChatSessionOptions,
    private val state: ChatExtensionActionState,
) : ChatActions,
    ChatHostCommandActions by HostCommandExtensions(options),
    ChatBranchActions by BranchExtensions(options.branching, state.branchView),
    ChatSendQueueActions by SendQueueExtensions(options.sendQueue),
    ChatConversationSettingsActions by SettingsExtensions(options) {
    override fun rerun(message: UiMessage) = Unit

    override fun dismissA2uiSurface(surfaceId: A2uiSurfaceId) = Unit

    override fun markA2uiSnackbarShown(id: A2uiSnackbarId) = Unit

    override fun toggleRunCollapsed(runId: ChatRunId) = state.local.update { it.toggleRun(runId.value) }

    override fun toggleReasoningExpanded(messageId: ChatMessageId) = state.local.update { it.toggleReasoning(messageId.value) }

    override fun loadOlderMessages() = Unit

    override fun releaseOlderMessages() = Unit

    override fun expandTruncatedToolResult(messageId: ChatMessageId) = Unit

    /** The page showed the error: acknowledge it so it is not shown again; the glow keeps "failed". */
    override fun clearError() = state.local.update { it.acknowledge(state.error()) }

    override fun setFontScale(scale: Float) = Unit

    override fun updateSearchQuery(query: String) = Unit

    override fun clearSearch() = Unit

    override fun refreshGoalStatus() = Unit

    override fun continueGoal() = Unit
}

/** Composer commands, `/recompile` and A2UI actions, answered by the host's inputs. */
private class HostCommandExtensions(private val options: TimelineChatSessionOptions) : ChatHostCommandActions {
    override fun runComposerCommand(command: ChatComposerCommand) = hostCommand(command)?.run?.invoke() ?: Unit

    override fun uninstallComposerCommand(command: ChatComposerCommand) = hostCommand(command)?.uninstall?.invoke() ?: Unit

    private fun hostCommand(command: ChatComposerCommand) = options.composerHost?.value?.commands?.firstOrNull { it.label == command.id }

    override fun dismissRecompileStatus() = options.recompile?.dismiss() ?: Unit

    override fun submitA2uiAction(action: A2uiAction) = options.onA2uiAction?.invoke(action) ?: Unit
}

/** Fork, edit-and-resend and rerun, planned against the page's timeline now. */
private class BranchExtensions(
    private val branching: ChatBranchingSource?,
    private val branchView: () -> ChatBranchView,
) : ChatBranchActions {
    override fun forkFromMessage(message: UiMessage, name: String?) = branching?.forkFrom(message, branchView(), name) ?: Unit

    override fun editAndResend(message: UiMessage) = branching?.editAndResend(message, branchView()) ?: Unit

    override fun rerunInFork(message: UiMessage) = branching?.rerun(message, branchView()) ?: Unit
}

private class SendQueueExtensions(private val queue: ChatSendQueueSource?) : ChatSendQueueActions {
    override fun cancelQueuedSend(id: QueuedSendId) = queue?.cancel(id) ?: Unit

    override fun sendQueuedNow(id: QueuedSendId) = queue?.sendNow(id) ?: Unit

    override fun resumeSendQueue() = queue?.resume() ?: Unit
}

private class SettingsExtensions(private val options: TimelineChatSessionOptions) : ChatConversationSettingsActions {
    override fun selectModel(handle: ChatModelHandle, effort: ReasoningEffortChoice) =
        options.models?.selectModel(handle, effort) ?: Unit

    override fun changeWorkingDirectory(directory: ChatWorkingDirectory) =
        options.workingDirectory?.changeWorkingDirectory(directory) ?: Unit

    override fun setPermissionMode(mode: AppServerPermissionMode) = options.permissionModes?.setPermissionMode(mode) ?: Unit
}
