package com.letta.mobile.ui.chat.session

import com.letta.mobile.data.chat.send.QueuedSendId
import com.letta.mobile.data.model.ConversationId
import com.letta.mobile.data.model.MessageContentPart
import com.letta.mobile.data.model.UiMessage
import com.letta.mobile.data.repository.modelcontrol.ReasoningEffortChoice
import com.letta.mobile.data.subagents.SubagentView
import com.letta.mobile.data.timeline.TimelineSyncLoop
import com.letta.mobile.data.timeline.TimelineTransport
import com.letta.mobile.data.transport.appserver.AppServerPermissionMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * A subagent's conversation as the dual view shows it - read-only. The child
 * works for its parent, so a reply belongs in the parent: the composer takes no input and says so
 * ([notice]), nothing can be sent, re-run or forked from here, and the conversation's settings stay
 * as the parent's dispatch left them. Reading, scrolling, expanding and stopping the run still work.
 */
class ReadOnlySubagentSessionPort(
    private val delegate: ChatSessionPort,
    private val notice: String,
    scope: CoroutineScope,
) : ChatSessionPort by delegate {
    override val composer: StateFlow<ChatComposerUiState> = delegate.composer
        .map(::readOnly)
        .stateIn(scope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), readOnly(delegate.composer.value))

    override val capabilities: StateFlow<ChatSurfaceCapabilities> = delegate.capabilities
        .map(::readOnly)
        .stateIn(scope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), readOnly(delegate.capabilities.value))

    override val actions: ChatActions = ReadOnlyActions(delegate.actions)

    private fun readOnly(composer: ChatComposerUiState): ChatComposerUiState = ChatComposerUiState(
        acceptsInput = false,
        canSend = false,
        placeholder = notice,
        contextUsage = composer.contextUsage,
        backgroundProcesses = composer.backgroundProcesses,
    )

    private fun readOnly(capabilities: ChatSurfaceCapabilities): ChatSurfaceCapabilities = capabilities.copy(
        attachImages = false,
        rerun = false,
        modelSwitch = false,
        workingDirectory = false,
        fork = false,
        editAndResend = false,
        rerunInFork = false,
        nameForks = false,
    )

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}

/** The owner's actions with every write to the conversation dropped. */
private class ReadOnlyActions(delegate: ChatActions) : ChatActions by delegate {
    override fun updateComposerText(text: String) = Unit

    override fun send() = Unit

    override fun sendText(text: String) = Unit

    override fun attachImage(image: MessageContentPart.Image) = Unit

    override fun runComposerCommand(command: ChatComposerCommand) = Unit

    override fun rerun(message: UiMessage) = Unit

    override fun forkFromMessage(message: UiMessage, name: String?) = Unit

    override fun editAndResend(message: UiMessage) = Unit

    override fun rerunInFork(message: UiMessage) = Unit

    override fun sendQueuedNow(id: QueuedSendId) = Unit

    override fun selectModel(handle: ChatModelHandle, effort: ReasoningEffortChoice) = Unit

    override fun changeWorkingDirectory(directory: ChatWorkingDirectory) = Unit

    override fun setPermissionMode(mode: AppServerPermissionMode) = Unit
}

/** A subagent conversation's live session: what the pane draws, and how it stops. */
class SubagentViewSession(val port: ChatSessionPort, val close: () -> Unit)

/**
 * Opens a [SubagentView]'s session in a scope that ends with the pane; null when this host cannot
 * reach the child's conversation right now (no connection), and the pane says so.
 */
fun interface SubagentViewSessions {
    fun open(view: SubagentView, scope: CoroutineScope): SubagentViewSession?
}

/** Where a subagent's timeline is read: the transport, and the conversation id its loop runs under. */
class SubagentTimelineRoute(val transport: TimelineTransport, val loopConversationId: ConversationId)

/**
 * The shared binding: the child conversation's own [TimelineSyncLoop] over the host's [route],
 * presented by the commonMain [TimelineChatSessionPort] every client uses. A platform supplies only
 * the route (its transport for the child's conversation).
 */
class TimelineSubagentViewSessions(
    private val route: (SubagentView) -> SubagentTimelineRoute?,
) : SubagentViewSessions {
    override fun open(view: SubagentView, scope: CoroutineScope): SubagentViewSession? {
        val resolved = route(view) ?: return null
        val loop = TimelineSyncLoop(
            messageApi = resolved.transport,
            conversationId = resolved.loopConversationId.value,
            scope = scope,
            logTag = LOG_TAG,
            agentId = view.agentId.value,
        )
        val target = TimelineChatTarget(
            agentId = view.agentId.value,
            agentName = view.title ?: view.shortAgentId,
            conversationId = view.conversationId.value,
        )
        return SubagentViewSession(TimelineChatSessionPort(TimelineSyncLoopSession(loop), target, scope), loop::close)
    }

    private companion object {
        const val LOG_TAG = "SubagentView"
    }
}
