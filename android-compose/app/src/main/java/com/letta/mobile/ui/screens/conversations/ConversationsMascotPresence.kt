package com.letta.mobile.ui.screens.conversations

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import com.letta.mobile.data.presence.AgentActivityKind
import com.letta.mobile.data.presence.AgentPresence
import com.letta.mobile.ui.mascot.LocalMascotRegistry

/**
 * The list's presence source: every agent with a conversation whose run is in flight reads as
 * thinking on the shell's mascot registry, so its tile moves (the shared [com.letta.mobile.ui.mascot.mascotAtWork]
 * rule) while the run does. Scoped: it sets and clears only the agents it published, so the chat
 * screen's own presence for the open agent is never wiped by this list.
 */
@Composable
internal fun ConversationsMascotPresenceSync(conversations: List<ConversationDisplay>) {
    val registry = LocalMascotRegistry.current
    val working = conversations.filter { it.isWorking }.map { it.conversation.agentId.value }.toSet()
    val published = remember { mutableSetOf<String>() }
    SideEffect {
        (published - working).forEach { registry.clearPresence(it) }
        working.forEach { registry.setPresence(it, AgentPresence(activity = AgentActivityKind.THINKING)) }
        published.clear()
        published.addAll(working)
    }
    DisposableEffect(registry) {
        onDispose {
            published.forEach { registry.clearPresence(it) }
            published.clear()
        }
    }
}
