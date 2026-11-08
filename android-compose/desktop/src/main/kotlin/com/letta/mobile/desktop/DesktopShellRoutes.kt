package com.letta.mobile.desktop

import androidx.compose.runtime.State
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.ConversationId
import com.letta.mobile.data.shell.ShellChatPort
import com.letta.mobile.data.shell.ShellConversationRef
import com.letta.mobile.data.shell.FocusAvailability
import com.letta.mobile.data.shell.ShellDestination
import com.letta.mobile.data.shell.ShellOverlays
import com.letta.mobile.data.shell.ShellPageKind
import com.letta.mobile.data.shell.ShellWorkspaceState
import com.letta.mobile.desktop.chat.DesktopChatController
import com.letta.mobile.desktop.chat.DesktopChatSurfaceState
import com.letta.mobile.ui.components.tabs.LettaTabStripActions
import com.letta.mobile.ui.shell.ShellChatSelection
/** Desktop destinations and the chat port over the shared workspace owner. */
private val DesktopDestinationByKind: Map<ShellPageKind, DesktopDestination> = mapOf(
    ShellPageKind.Home to DesktopDestination.Home,
    ShellPageKind.Chat to DesktopDestination.Conversations,
    ShellPageKind.Canvas to DesktopDestination.Conversations,
    ShellPageKind.AgentEditor to DesktopDestination.Conversations,
    ShellPageKind.Memory to DesktopDestination.Memory,
    ShellPageKind.Schedules to DesktopDestination.Schedules,
    ShellPageKind.Channels to DesktopDestination.Channels,
    ShellPageKind.Skills to DesktopDestination.Agents,
    ShellPageKind.Settings to DesktopDestination.Settings,
    ShellPageKind.Providers to DesktopDestination.Providers,
    ShellPageKind.Overview to DesktopDestination.Overview,
    ShellPageKind.Runs to DesktopDestination.Runs,
    ShellPageKind.Templates to DesktopDestination.Templates,
    ShellPageKind.Projects to DesktopDestination.Projects,
    ShellPageKind.Plugins to DesktopDestination.Plugins,
)

private val ShellPageOfDesktop: Map<DesktopDestination, ShellDestination> = mapOf(
    DesktopDestination.Home to ShellDestination.Home,
    DesktopDestination.Overview to ShellDestination.Overview,
    DesktopDestination.Agents to ShellDestination.Skills(),
    DesktopDestination.Memory to ShellDestination.Memory(),
    DesktopDestination.Schedules to ShellDestination.Schedules(),
    DesktopDestination.Channels to ShellDestination.Channels,
    DesktopDestination.Providers to ShellDestination.Providers,
    DesktopDestination.Conversations to ShellDestination.Chat(),
    DesktopDestination.Settings to ShellDestination.Settings(),
    DesktopDestination.Runs to ShellDestination.Runs,
    DesktopDestination.Templates to ShellDestination.Templates,
    DesktopDestination.Projects to ShellDestination.Projects,
    DesktopDestination.Plugins to ShellDestination.Plugins,
)
/** The desktop destination that shows [this]. */
internal val ShellDestination.desktopDestination: DesktopDestination
    get() = DesktopDestinationByKind.getValue(kind)
/** The shared page [this] desktop destination shows, before the focus refines it. */
internal val DesktopDestination.shellPage: ShellDestination get() = ShellPageOfDesktop.getValue(this)
/** The rail's, palette's and saved state's view of the page; setting it leaves the editor as it is. */
internal var ShellWorkspaceState.selectedDestination: DesktopDestination
    get() = page.desktopDestination
    set(value) = showPage(value.shellPage)
/** Leaves any agent editor, then shows [destination]. */
internal fun ShellWorkspaceState.navigate(destination: DesktopDestination) = navigate(destination.shellPage)


/** The overlay stack's flags: the shared workspace's. */
internal val DesktopShellContext.overlays: ShellOverlays get() = navigator.overlays

/** Desktop's visible catalog is partial and cannot establish global conversation absence. */
internal fun DesktopChatSurfaceState.shellChatSelection(): ShellChatSelection = ShellChatSelection(
    session = sessionGraphId.toString(),
    conversation = selectedConversationId?.let(::ConversationId),
    agent = selectedConversation?.agentId?.let(::AgentId),
    reported = null,
)

/** The desktop chat controller as the shell navigator's chat. [chatState] is read when a verb runs. */
internal class DesktopShellChatPort(
    private val controller: DesktopChatController,
    private val chatState: State<DesktopChatSurfaceState>,
    private val workspace: ShellWorkspaceState,
) : ShellChatPort {
    init {
        controller.workspaceOwner = workspace
        controller.ownerAllowsSending = {
            val focus = workspace.currentFocus
            val selected = controller.state.value.selectedConversation
            selected != null && focus.conversationId?.value == selected.id && focus.agentId?.value == selected.agentId &&
                workspace.agentAvailability != FocusAvailability.Unavailable &&
                workspace.conversationAvailability != FocusAvailability.Unavailable
        }
    }

    override fun conversations(): List<ShellConversationRef> = chatState.value.conversations.map {
        ShellConversationRef(ConversationId(it.id), it.agentId?.let(::AgentId), it.updatedAtLabel, tags = it.tags)
    }

    override val selectedConversation: ConversationId?
        get() = workspace.selectedConversationId

    override fun selectConversation(conversation: ConversationId) {
        if (workspace.selectedConversationId != conversation) return
        val token = workspace.requestToken()
        controller.selectConversation(conversation.value, allowUnlisted = true) { workspace.accepts(token) }
    }

    override fun createConversationForAgent(agent: AgentId, onCreated: (ConversationId) -> Unit) {
        val token = workspace.requestToken()
        controller.createConversationForAgent(agent.value, { workspace.accepts(token) }) { onCreated(ConversationId(it)) }
    }

    /** Complete a created chat only while the request still names the owner's current target. */
    override fun startNewChat(agent: AgentId?) {
        val target = agent ?: return
        val token = workspace.requestToken()
        controller.clearSelection { workspace.accepts(token) }
        controller.createConversationForAgent(target.value, { workspace.accepts(token) }) { id ->
            if (workspace.completeFocus(token, ConversationId(id), target)) {
                controller.conversationMarks.onConversationCreated(id)
                selectConversation(ConversationId(id))
            }
        }
    }

    override fun send(conversation: ConversationId, text: String) {
        if (workspace.selectedConversationId != conversation) return
        val token = workspace.requestToken()
        controller.replyFromNotification(conversation.value, text) { workspace.accepts(token) }
    }

    override fun stageComposerText(text: String) {
        val token = workspace.requestToken()
        if (token.mode != com.letta.mobile.data.shell.CurrentFocusChatMode.New) return
        controller.clearSelection { workspace.accepts(token) }
        if (workspace.accepts(token)) controller.updateComposerText(text)
    }
}

/** The strip's verbs; its browser-style "+" is a new chat with the focused agent. */
internal fun desktopTabActions(context: DesktopShellContext, frame: DesktopShellFrame): LettaTabStripActions {
    val router = context.router
    return LettaTabStripActions(
        onSelect = { router.openConversation(ConversationId(it)) },
        onClose = { router.closeConversationTab(ConversationId(it)) },
        onReorder = { id, index -> router.reorderConversationTab(ConversationId(id), index) },
        onPin = { router.pinConversationTab(ConversationId(it)) },
        onUnpin = { router.unpinConversationTab(ConversationId(it)) },
        onNew = { context.openNewChatForFocusedAgent(frame.focus) },
    )
}
