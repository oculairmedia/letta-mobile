package com.letta.mobile.desktop.chat

import com.letta.mobile.data.model.ConversationId
import com.letta.mobile.data.model.SubagentEntry
import com.letta.mobile.ui.chat.session.SubagentViewSessions
import com.letta.mobile.ui.chat.session.TimelineSubagentViewSessions
import com.letta.mobile.ui.chat.subagents.SubagentDualViewBinding
import com.letta.mobile.ui.chat.subagents.SubagentTodoLoader
import com.letta.mobile.ui.chat.subagents.conversationOpener

/**
 * letta-mobile-ckmub.3.19: what the shared subagent rings over the desktop chat page read: the
 * conversation's registry [entries] and where a ring's todo sheet loads its checklist.
 * letta-mobile-ulcg6.1: [views] is the page's subagent dual view, which a ring, the roster and the
 * timeline rows open a subagent's conversation in; null leaves "view conversation" out.
 */
internal data class DesktopChatSubagents(
    val entries: List<SubagentEntry>,
    val loader: SubagentTodoLoader,
    val views: SubagentDualViewBinding? = null,
) {
    /** A ring's and the todo sheet's "view conversation": the dual view, when the page has one. */
    val onViewConversation: ((String, String) -> Unit)?
        get() = views?.holder?.conversationOpener { entries }
}

/**
 * letta-mobile-ulcg6.1: where the dual view reads a subagent's conversation - over [controller]'s
 * active gateway, routed the way a selected conversation is.
 */
internal fun desktopSubagentViewSessions(controller: DesktopChatController): SubagentViewSessions =
    TimelineSubagentViewSessions { view -> controller.activeGateway?.let { desktopSubagentTimelineRoute(it, view) } }

/** The conversation whose subagents the dual view opens: the selected one. */
internal val DesktopChatSurfaceState.subagentParent: ConversationId?
    get() = selectedConversationId?.let(::ConversationId)
