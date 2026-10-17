package com.letta.mobile.ui.chat.subagents

import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.ConversationId
import com.letta.mobile.data.model.SubagentEntry
import com.letta.mobile.data.subagents.SubagentDispatchTarget
import com.letta.mobile.data.subagents.SubagentViewLabel
import com.letta.mobile.data.subagents.subagentViewForDispatch
import com.letta.mobile.data.subagents.subagentViewOf
import com.letta.mobile.data.subagents.toSubagentView
import com.letta.mobile.runtime.ToolCallId
import com.letta.mobile.ui.chat.session.ChatSurfaceHost

/*
 * letta-mobile-zxk2n.8: every way into a subagent's conversation, bound to one dual view, so the
 * roster, the rings and the timeline rows open the same view the same way on every client.
 */

/** The roster's "View conversation": opens the row's conversation beside the parent, after [beforeOpen] (show the chat). */
fun SubagentDualViewHolder.rosterOpener(beforeOpen: () -> Unit = {}): OpenSubagentConversation = { item ->
    item.toSubagentView()?.let { view ->
        beforeOpen()
        open(view)
    }
}

/**
 * A ring's or the todo sheet's "view conversation", handed the child's agent and conversation: it
 * opens beside the parent, labelled from the registry [entries] when they list the child.
 */
fun SubagentDualViewHolder.conversationOpener(entries: () -> List<SubagentEntry>): (String, String) -> Unit = { agent, conversation ->
    val entry = entries().firstOrNull { it.subagentAgentId == agent }
    val label = SubagentViewLabel.of(entry?.description, entry?.subagentType, listOf(agent))
    subagentViewOf(AgentId(agent), ConversationId(conversation), label)?.let(::open)
}

/**
 * The page's [ChatSurfaceHost.openSubagent] with a dual view: a tapped dispatch (or its report)
 * opens its subagent beside the parent once [entries] name the child's conversation
 * ([subagentViewForDispatch]). A subagent whose conversation is not known yet opens nothing.
 */
fun ChatSurfaceHost.withSubagentDualView(holder: SubagentDualViewHolder, entries: () -> List<SubagentEntry>): ChatSurfaceHost =
    copy(
        openSubagent = { call, agent, description ->
            if (call.isNotBlank()) {
                val target = SubagentDispatchTarget(ToolCallId(call), agent?.takeIf { it.isNotBlank() }?.let(::AgentId), description)
                entries().subagentViewForDispatch(target)?.let(holder::open)
            }
        },
    )
