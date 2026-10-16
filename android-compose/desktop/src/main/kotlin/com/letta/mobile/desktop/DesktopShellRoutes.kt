package com.letta.mobile.desktop

import androidx.compose.runtime.State
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.CanvasId
import com.letta.mobile.data.model.ConversationId
import com.letta.mobile.data.shell.ShellChatPort
import com.letta.mobile.data.shell.ShellConversationRef
import com.letta.mobile.data.shell.ShellDestination
import com.letta.mobile.data.shell.ShellPageKind
import com.letta.mobile.data.shell.ShellWorkspaceNavigator
import com.letta.mobile.data.shell.ShellWorkspaceState
import com.letta.mobile.desktop.chat.DesktopChatController
import com.letta.mobile.desktop.chat.DesktopChatSurfaceState
import com.letta.mobile.ui.shell.header.UnifiedSearchHit
import com.letta.mobile.ui.shell.header.UnifiedSearchKind

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

/**
 * Opens a unified-search pick, from the header search, the command palette or the quick-query bar
 * alike: a message opens its conversation; a canvas goes to [openCanvas] (only the header's search
 * lists canvases); a destination hit names a [DesktopDestination].
 */
internal fun ShellWorkspaceNavigator.openSearchHit(hit: UnifiedSearchHit, openCanvas: (CanvasId) -> Unit = {}) {
    when (hit.kind) {
        UnifiedSearchKind.Conversations, UnifiedSearchKind.Messages -> openConversation(ConversationId(hit.id))
        UnifiedSearchKind.Agents -> openAgent(AgentId(hit.id))
        UnifiedSearchKind.Canvases -> openCanvas(CanvasId(hit.id))
        UnifiedSearchKind.Destinations ->
            DesktopDestination.entries.firstOrNull { it.name == hit.id }?.let(state::navigate)
    }
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
