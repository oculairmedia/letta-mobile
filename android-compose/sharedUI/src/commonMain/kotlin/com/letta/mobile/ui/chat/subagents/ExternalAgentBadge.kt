package com.letta.mobile.ui.chat.subagents

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.letta.mobile.data.subagents.SubagentIdentity
import com.letta.mobile.ui.theme.LettaDimens

/**
 *  (G40): names an external coding agent (Claude Code, Codex) wherever its work
 * shows - a roster row, a timeline dispatch or its report - so it is never mistaken for a Letta
 * subagent. Draws nothing for a Letta subagent.
 */
@Composable
fun ExternalAgentBadge(identity: SubagentIdentity, modifier: Modifier = Modifier, testTag: String = EXTERNAL_AGENT_BADGE_TAG) {
    val label = identity.label ?: return
    Surface(
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.secondaryContainer,
        modifier = modifier,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            maxLines = 1,
            modifier = Modifier.padding(horizontal = LettaDimens.Space.xs).testTag(testTag),
        )
    }
}

/** Default test tag of the badge's label; a list gives each row's badge its own. */
const val EXTERNAL_AGENT_BADGE_TAG = "external-agent-badge"
