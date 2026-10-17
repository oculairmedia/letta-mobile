package com.letta.mobile.ui.chat.subagents

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import com.letta.mobile.data.model.ConversationId
import com.letta.mobile.data.model.SubagentEntry
import com.letta.mobile.data.subagents.SubagentView
import com.letta.mobile.ui.chat.session.ChatSurfaceHost
import com.letta.mobile.ui.chat.session.SubagentViewSessions

/*
 * letta-mobile-zxk2n.8: how a chat page binds its subagent dual view, the same on every client, so a
 * platform only hands over where a child's conversation is read and how to open its full chat.
 */

/**
 * The dual view of the [parent] conversation (closed again for each one), reading children through
 * [sessions] and jumping to a child's full chat through [onOpenFullView] (null hides that). Null
 * without [sessions]: the page then has no dual view and "view conversation" keeps its old route.
 * The latest [sessions] and [onOpenFullView] are used, so a host may rebuild them freely.
 */
@Composable
fun rememberSubagentDualView(
    parent: ConversationId?,
    sessions: SubagentViewSessions?,
    onOpenFullView: ((SubagentView) -> Unit)? = null,
): SubagentDualViewBinding? {
    val holder = rememberSubagentDualViewHolder(parent)
    val latestSessions by rememberUpdatedState(sessions)
    val latestOpenFull by rememberUpdatedState(onOpenFullView)
    val hasSessions = sessions != null
    val canOpenFull = onOpenFullView != null
    return remember(holder, hasSessions, canOpenFull) {
        if (!hasSessions) return@remember null
        SubagentDualViewBinding(
            holder = holder,
            sessions = SubagentViewSessions { view, scope -> latestSessions?.open(view, scope) },
            onOpenFullView = if (canOpenFull) { view -> latestOpenFull?.invoke(view) } else null,
        )
    }
}

/**
 * The page's [host] with its timeline rows opening their subagent in [dualView] (labelled from the
 * registry [entries]); [host] itself without a dual view.
 */
@Composable
fun rememberSubagentDualViewHost(host: ChatSurfaceHost, dualView: SubagentDualViewBinding?, entries: List<SubagentEntry>): ChatSurfaceHost {
    val latestEntries by rememberUpdatedState(entries)
    val holder = dualView?.holder
    return remember(host, holder) { holder?.let { host.withSubagentDualView(it) { latestEntries } } ?: host }
}
