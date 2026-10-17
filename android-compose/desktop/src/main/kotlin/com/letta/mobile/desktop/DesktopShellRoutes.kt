package com.letta.mobile.desktop

import androidx.compose.runtime.State
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.ConversationId
import com.letta.mobile.data.shell.ShellChatPort
import com.letta.mobile.data.shell.ShellConversationRef
import com.letta.mobile.data.shell.ShellDestination
import com.letta.mobile.data.shell.ShellOverlays
import com.letta.mobile.data.shell.ShellPageKind
import com.letta.mobile.data.shell.ShellWorkspaceState
import com.letta.mobile.desktop.chat.DesktopChatConnectionState
import com.letta.mobile.desktop.chat.DesktopChatController
import com.letta.mobile.desktop.chat.DesktopChatSurfaceState
import com.letta.mobile.ui.components.tabs.LettaTabStripActions
import com.letta.mobile.ui.shell.ShellChatSelection

/**
 * letta-mobile-ckmub.17.3: the desktop's window adapters over the shared [ShellWorkspaceState] - its
 * rail/palette/saved-state vocabulary ([DesktopDestination]) mapped to and from the shared page, and
 * the chat controller as the navigator's [ShellChatPort]. The state and the verbs are the shared ones.
 */
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

/** The main pane's page for this frame's focused agent and selected conversation. */
internal fun ShellWorkspaceState.pageFor(frame: DesktopShellFrame): ShellDestination =
    pageFor(frame.focus.selectedAgentId?.let(::AgentId), frame.chatState.selectedConversationId?.let(::ConversationId))

/** The focused agent: the selected conversation's, else the workspace's while on the rail, else the rail's head. */
internal fun ShellWorkspaceState.focusFor(chatState: DesktopChatSurfaceState, railAgents: List<Pair<String, String>>): String? =
    focusFor(chatState.selectedConversation?.agentId?.let(::AgentId), railAgents.map { AgentId(it.first) })?.value

/**
 * What the chat shows, for the shared workspace's sync: the gateway's conversation list counts once
 * it is Live or has reported none.
 */
internal fun DesktopChatSurfaceState.shellChatSelection(): ShellChatSelection {
    val authoritative = connectionState == DesktopChatConnectionState.Live ||
        connectionState == DesktopChatConnectionState.NoConversations
    return ShellChatSelection(
        session = sessionGraphId.toString(),
        conversation = selectedConversationId?.let(::ConversationId),
        agent = selectedConversation?.agentId?.let(::AgentId),
        reported = if (authoritative) conversations.mapTo(mutableSetOf()) { ConversationId(it.id) } else null,
    )
}

/** The desktop chat controller as the shell navigator's chat. [chatState] is read when a verb runs. */
internal class DesktopShellChatPort(
    private val controller: DesktopChatController,
    private val chatState: State<DesktopChatSurfaceState>,
) : ShellChatPort {
    override fun conversations(): List<ShellConversationRef> = chatState.value.conversations.map {
        ShellConversationRef(ConversationId(it.id), it.agentId?.let(::AgentId), it.updatedAtLabel)
    }

    override val selectedConversation: ConversationId?
        get() = chatState.value.selectedConversationId?.let(::ConversationId)

    override fun selectConversation(conversation: ConversationId) {
        controller.selectConversation(conversation.value)
    }

    override fun createConversationForAgent(agent: AgentId, onCreated: (ConversationId) -> Unit) =
        controller.createConversationForAgent(agent.value) { onCreated(ConversationId(it)) }

    /**
     * Targets the agent explicitly - for a roster-only agent, createConversation()'s
     * conversation-derived agent would miss it. letta-mobile-zxk2n.5: a chat started here is pinned
     * when "Pin new chats" is on.
     */
    override fun startNewChat(agent: AgentId?) {
        val marks = controller.conversationManagement.marks
        agent?.let { controller.createConversationForAgent(it.value, onCreated = marks::onConversationCreated) }
            ?: controller.createConversation()
    }

    override fun send(conversation: ConversationId, text: String) = controller.replyFromNotification(conversation.value, text)

    override fun stageComposerText(text: String) = controller.updateComposerText(text)
}

/** The strip's verbs; its browser-style "+" is a new chat with the focused agent. */
internal fun desktopTabActions(context: DesktopShellContext, frame: DesktopShellFrame): LettaTabStripActions {
    val router = context.router
    return LettaTabStripActions(
        onSelect = { router.openConversation(ConversationId(it)) },
        onClose = { router.closeConversationTab(ConversationId(it)) },
        onReorder = { id, index -> router.reorderConversationTab(ConversationId(id), index) },
        onNew = { context.openNewChatForFocusedAgent(frame.focus) },
    )
}
