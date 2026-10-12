package com.letta.mobile.ui.shell.pages.agents

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import com.letta.mobile.avatar.core.MascotIdentity
import com.letta.mobile.ui.shell.rail.ShellAgentLibrary
import com.letta.mobile.ui.shell.rail.ShellAgentRailActions
import com.letta.mobile.ui.shell.rail.ShellRailFocus
import com.letta.mobile.ui.shell.rail.ShellRailMapping
import com.letta.mobile.ui.theme.LettaDimens

/** Test tags of the agents page. */
object AgentsPageTags {
    const val PAGE = "agents-page"
    const val SHARE_PREVIEW = "agents-page-share-preview"
    const val ERROR = "agents-page-error"
    const val EMPTY = "agents-page-empty"
    const val LOADING = "agents-page-loading"
}

/** Everything the agents page draws. */
@Immutable
data class AgentsPageState(
    /** Every agent as (id, name), in roster order. */
    val agents: List<Pair<String, String>> = emptyList(),
    /** Mascot identities by agent id: those agents show their mascot instead of the gradient orb. */
    val identities: Map<String, MascotIdentity> = emptyMap(),
    val pinnedAgentIds: Set<String> = emptySet(),
    val loading: Boolean = false,
    /** A load failure with no agents cached. */
    val error: String? = null,
    /** Text shared into the app: the page is picking the agent it goes to, and says so. */
    val sharePreview: String? = null,
)

/**
 * letta-mobile-ckmub.2.4 (hard cut S16): every agent, the shell's agent library
 * ([ShellAgentLibrary], the desktop's expanded rail) as a page: search, pinned agents first, the rest
 * by space, each with its mascot or orb and the orb's menu (Open, Pin, Agent settings). [actions] is
 * the rail's own action set. [modifier] carries the host frame's insets.
 */
@Composable
fun AgentsPage(
    state: AgentsPageState,
    actions: ShellAgentRailActions,
    modifier: Modifier = Modifier,
    onRetry: () -> Unit = {},
) {
    Column(modifier.testTag(AgentsPageTags.PAGE).padding(horizontal = LettaDimens.Space.md)) {
        state.sharePreview?.let { SharePreview(it) }
        val error = state.error
        when {
            error != null && state.agents.isEmpty() -> AgentsPageError(error, onRetry)
            state.agents.isEmpty() && state.loading -> LinearProgressIndicator(Modifier.fillMaxWidth().testTag(AgentsPageTags.LOADING))
            state.agents.isEmpty() -> AgentsPageEmpty()
            else -> {
                val groups = remember(state.agents) { ShellRailMapping.groups(state.agents, selectedAgentId = null) }
                val focus = ShellRailFocus(identityByAgentId = state.identities, pinnedAgentIds = state.pinnedAgentIds)
                val entries = remember(groups, focus) { ShellRailMapping.entries(groups, focus) }
                ShellAgentLibrary(groups = groups, entries = entries, actions = actions)
            }
        }
    }
}

@Composable
private fun SharePreview(text: String) {
    Column(Modifier.fillMaxWidth().padding(vertical = LettaDimens.Space.sm).testTag(AgentsPageTags.SHARE_PREVIEW)) {
        Text("Choose an agent to share with", style = MaterialTheme.typography.titleSmall)
        Text(
            text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = SHARE_PREVIEW_LINES,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun AgentsPageError(message: String, onRetry: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(LettaDimens.Space.lg).testTag(AgentsPageTags.ERROR)) {
        Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
        TextButton(onClick = onRetry) { Text("Retry") }
    }
}

@Composable
private fun AgentsPageEmpty() {
    Text(
        "No agents yet",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(LettaDimens.Space.lg).testTag(AgentsPageTags.EMPTY),
    )
}

private const val SHARE_PREVIEW_LINES = 3
