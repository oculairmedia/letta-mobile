package com.letta.mobile.ui.chat.subagents

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.letta.mobile.data.model.SubagentEntry
import com.letta.mobile.data.subagents.projectSubagentRoster
import com.letta.mobile.ui.chat.AgentActivity
import com.letta.mobile.ui.chat.AgentActivityOrb
import com.letta.mobile.ui.host.InspectorPane
import com.letta.mobile.ui.host.LocalLettaHost
import com.letta.mobile.ui.theme.LettaDimens
import kotlin.time.Clock

/**
 * letta-mobile-ckmub.3.15 (hard cut S13): the "Background tasks" roster in the window's inspector
 * — docked on the right when the window is wide, a sheet when compact. The shared
 * [SubagentRosterList] over the shared [projectSubagentRoster], so every client lists the same
 * subagents in the same states. Replaces desktop's DesktopBackgroundTasksPanel and its side pane.
 */
@Composable
fun SubagentRosterPane(
    subagents: List<SubagentEntry>,
    onClose: () -> Unit,
    onOpenConversation: OpenSubagentConversation? = null,
    now: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) {
    var clearedKeys by remember { mutableStateOf<Set<String>>(emptySet()) }
    // The pane stays open while subagents stream status updates: re-project only when they change.
    val roster = remember(subagents, clearedKeys) { projectSubagentRoster(subagents, now(), clearedKeys) }
    InspectorPane(title = BACKGROUND_TASKS, onClose = onClose, testTag = SubagentRosterPaneTags.PANE) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = LettaDimens.Space.lg),
        ) {
            SubagentRosterList(
                roster = roster,
                onOpenConversation = onOpenConversation,
                onClearFinished = { clearedKeys = clearedKeys + roster.finished.map { it.key } },
            )
        }
    }
}

/**
 * The button that opens [SubagentRosterPane] from a bar (the chat page's canvas header): an
 * activity orb that works while any subagent runs, its words (and the running count) in the host's
 * tooltip and for screen readers.
 */
@Composable
fun SubagentRosterToggle(runningCount: Int, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val label = if (runningCount > 0) "$BACKGROUND_TASKS · $runningCount running" else BACKGROUND_TASKS
    Box(modifier) {
        LocalLettaHost.current.tooltip(label) {
            Box(
                modifier = Modifier
                    .size(LettaDimens.Control.iconButtonLg)
                    .clip(CircleShape)
                    .clickable(role = Role.Button, onClick = onClick)
                    .semantics { contentDescription = label }
                    .testTag(SubagentRosterPaneTags.TOGGLE),
                contentAlignment = Alignment.Center,
            ) {
                AgentActivityOrb(
                    size = LettaDimens.Control.icon,
                    activity = if (runningCount > 0) AgentActivity.Working else AgentActivity.Idle,
                )
            }
        }
    }
}

private const val BACKGROUND_TASKS = "Background tasks"

/** Test tags of [SubagentRosterPane] and [SubagentRosterToggle]. */
object SubagentRosterPaneTags {
    const val PANE = "subagent-roster-pane"
    const val TOGGLE = "subagent-roster-toggle"
}
