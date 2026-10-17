package com.letta.mobile.ui.chat.subagents

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import com.letta.mobile.data.subagents.SubagentView
import com.letta.mobile.sharedui.resources.Res
import com.letta.mobile.sharedui.resources.subagent_view_read_only
import com.letta.mobile.sharedui.resources.subagent_view_unavailable
import com.letta.mobile.ui.chat.session.ChatSurfaceModeReducer
import com.letta.mobile.ui.chat.session.ChatSurfacePresentation
import com.letta.mobile.ui.chat.session.ReadOnlySubagentSessionPort
import com.letta.mobile.ui.chat.session.SubagentViewSession
import com.letta.mobile.ui.chat.session.SubagentViewSessions
import com.letta.mobile.ui.chat.surface.ChatSurface
import com.letta.mobile.ui.theme.LettaDimens
import org.jetbrains.compose.resources.stringResource

/**
 * letta-mobile-zxk2n.8: a subagent's conversation in the dual view - the same shared [ChatSurface]
 * every chat page draws, as its full-screen page, over a [ReadOnlySubagentSessionPort]: the composer
 * takes no input and says to reply in the parent. The child's session lives as long as its view is
 * shown and stops when the reader moves to another view or closes it.
 */
@Composable
fun SubagentConversationPane(
    view: SubagentView,
    sessions: SubagentViewSessions,
    options: SubagentDualViewOptions,
    modifier: Modifier = Modifier,
) {
    key(view.key) {
        val session = rememberSubagentViewSession(view, sessions)
        if (session == null) {
            UnavailablePane(modifier)
        } else {
            ReadOnlyConversation(session, options, modifier)
        }
    }
}

/**
 * The view's session in a scope that ends with it (the caller keys this on the view). Opened once
 * per view: a host that rebuilds its [sessions] binding on recomposition never reopens the child.
 */
@Composable
private fun rememberSubagentViewSession(view: SubagentView, sessions: SubagentViewSessions): SubagentViewSession? {
    val scope = rememberCoroutineScope()
    val session = remember(scope) { sessions.open(view, scope) }
    DisposableEffect(session) { onDispose { session?.close?.invoke() } }
    return session
}

@Composable
private fun ReadOnlyConversation(session: SubagentViewSession, options: SubagentDualViewOptions, modifier: Modifier) {
    val notice = stringResource(Res.string.subagent_view_read_only)
    val scope = rememberCoroutineScope()
    val port = remember(session, notice, scope) { ReadOnlySubagentSessionPort(session.port, notice, scope) }
    var presentation by remember { mutableStateOf(ChatSurfacePresentation.initial(openOnCanvas = false, hasCanvas = false)) }
    ChatSurface(
        port = port,
        presentation = presentation,
        onIntent = { intent -> presentation = ChatSurfaceModeReducer.reduce(presentation, intent) },
        host = options.host,
        modifier = modifier,
        appearance = options.appearance,
    )
}

/** The host cannot reach the child's conversation right now (no connection to its backend). */
@Composable
private fun UnavailablePane(modifier: Modifier) {
    Box(modifier, contentAlignment = Alignment.Center) {
        Text(
            text = stringResource(Res.string.subagent_view_unavailable),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(LettaDimens.Space.lg).testTag(SubagentDualViewTags.UNAVAILABLE),
        )
    }
}
