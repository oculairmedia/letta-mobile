package com.letta.mobile.ui.navigation

import androidx.navigation.NavController
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.toRoute
import com.letta.mobile.data.model.Agent
import com.letta.mobile.data.model.Conversation
import com.letta.mobile.data.shell.ShellDestination
import com.letta.mobile.feature.chat.route.AgentChatRoute
import com.letta.mobile.ui.shell.shortcuts.ShellShortcutId
import com.letta.mobile.ui.shell.shortcuts.ShortcutLists
import com.letta.mobile.ui.shell.shortcuts.ShortcutStepping
import com.letta.mobile.ui.shell.shortcuts.stepList

/** Android destination and stepping for the shared keymap (letta-mobile-k5nbq.4). */
internal fun androidShortcutLists(
    conversations: List<Conversation>,
    agents: List<Agent>,
    nav: NavController,
): ShortcutLists = ShortcutLists(
    conversations = conversationStepping(conversations, nav),
    agents = agentStepping(agents, nav),
)

internal fun handleAndroidShortcut(
    id: ShellShortcutId,
    nav: NavController,
    lists: ShortcutLists,
    onHelp: () -> Unit,
) {
    if (id == ShellShortcutId.Help) {
        onHelp()
        return
    }
    if (openAndroidPage(id, nav, lists)) return
    stepList(id, lists)
}

internal fun openAndroidPage(id: ShellShortcutId, nav: NavController, lists: ShortcutLists): Boolean {
    val destination = androidDestination(id, lists) ?: return false
    nav.navigateTo(destination)
    return true
}

private fun androidDestination(id: ShellShortcutId, lists: ShortcutLists): ShellDestination? = when (id) {
    ShellShortcutId.Search, ShellShortcutId.Find, ShellShortcutId.QuickOpen -> ShellDestination.Home
    ShellShortcutId.NewConversation -> ShellDestination.Chat(lists.agents.ids().firstOrNull())
    ShellShortcutId.Memory, ShellShortcutId.FullscreenMemory -> ShellDestination.Memory(currentAgentId(lists))
    ShellShortcutId.Schedules -> ShellDestination.Schedules(currentAgentId(lists))
    ShellShortcutId.Settings -> ShellDestination.Settings()
    else -> null
}

private fun currentAgentId(lists: ShortcutLists): String? = lists.agents.current() ?: lists.agents.ids().firstOrNull()

private fun conversationStepping(conversations: List<Conversation>, nav: NavController): ShortcutStepping {
    val ids = conversations.map { it.id.value }
    return ShortcutStepping(
        ids = { ids },
        current = { currentChat(nav)?.conversationId },
        open = { id ->
            val conversation = conversations.firstOrNull { it.id.value == id } ?: return@ShortcutStepping
            nav.navigateTo(ShellDestination.Chat(conversation.agentId.value, conversation.id.value))
        },
    )
}

private fun agentStepping(agents: List<Agent>, nav: NavController): ShortcutStepping {
    val ids = agents.map { it.id.value }
    return ShortcutStepping(
        ids = { ids },
        current = { currentChat(nav)?.agentId ?: ids.firstOrNull() },
        open = { id -> nav.navigateTo(ShellDestination.Chat(id)) },
    )
}

private fun currentChat(nav: NavController): AgentChatRoute? {
    val entry = nav.currentBackStackEntry ?: return null
    if (!entry.destination.hasRoute<AgentChatRoute>()) return null
    return entry.toRoute()
}
