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
import com.letta.mobile.ui.shell.shortcuts.shortcutPage
import com.letta.mobile.ui.shell.shortcuts.stepList

/**
 * The entries the Android shell performs (letta-mobile-k5nbq.4). It has no command palette, rename
 * dialog or collapsible sidebar, so search, find, quick open, rename and the sidebar chords stay
 * with the focused field.
 */
internal val AndroidShortcuts: Set<ShellShortcutId> = setOf(
    ShellShortcutId.Help,
    ShellShortcutId.NewConversation,
    ShellShortcutId.Memory,
    ShellShortcutId.FullscreenMemory,
    ShellShortcutId.Schedules,
    ShellShortcutId.Settings,
    ShellShortcutId.PreviousConversation,
    ShellShortcutId.NextConversation,
    ShellShortcutId.PreviousAgent,
    ShellShortcutId.NextAgent,
)

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
    when (id) {
        ShellShortcutId.Help -> onHelp()
        ShellShortcutId.NewConversation -> openNewConversation(nav)
        else -> {
            if (stepList(id, lists)) return
            val page = shortcutPage(id, currentAgentId(lists)) ?: return
            nav.navigateTo(page)
        }
    }
}

/**
 * A fresh chat with the agent of the chat on screen; anywhere else the conversation list, where
 * the person picks the agent.
 */
internal fun openNewConversation(nav: NavController) {
    val agentId = currentChat(nav)?.agentId
    if (agentId == null) {
        nav.navigateTo(ShellDestination.Chat())
        return
    }
    nav.navigate(AgentChatRoute(agentId = agentId, freshRouteKey = System.currentTimeMillis()))
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
