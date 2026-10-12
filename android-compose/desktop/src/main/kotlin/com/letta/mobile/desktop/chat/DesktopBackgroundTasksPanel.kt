package com.letta.mobile.desktop.chat

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.letta.mobile.data.model.SubagentEntry
import com.letta.mobile.data.subagents.projectSubagentRoster
import com.letta.mobile.ui.chat.AgentActivity
import com.letta.mobile.ui.chat.AgentActivityOrb
import com.letta.mobile.ui.chat.subagents.SubagentRosterList
import com.letta.mobile.ui.components.LettaSidePane
import com.letta.mobile.ui.theme.LettaDimens

/**
 * Right-side "Background tasks" panel. letta-mobile-ulcg6 (PR4): it renders
 * the shared [SubagentRosterList] from the shared [projectSubagentRoster], the
 * same surface the Android subagent sheet shows, so both clients list the same
 * subagents in the same states. Fed by the shared SubagentRepository over the
 * Iroh channel transport (empty on HTTP backends).
 */
@Composable
internal fun DesktopBackgroundTasksPanel(
    subagents: List<SubagentEntry>,
    modifier: Modifier = Modifier,
    now: () -> Long = System::currentTimeMillis,
) {
    var clearedKeys by remember { mutableStateOf<Set<String>>(emptySet()) }
    // The panel stays open while subagents stream status updates, so keep the
    // projection keyed to its inputs instead of re-projecting per recomposition.
    val roster = remember(subagents, clearedKeys) { projectSubagentRoster(subagents, now(), clearedKeys) }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .fillMaxHeight()
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = LettaDimens.Space.lg),
    ) {
        // No header of its own: the hosting side pane draws the title and close.
        SubagentRosterList(
            roster = roster,
            // Desktop has no subagent-conversation route yet (dual view: letta-mobile-zxk2n.8).
            onOpenConversation = null,
            onClearFinished = { clearedKeys = clearedKeys + roster.finished.map { it.key } },
        )
    }
}

/**
 * Floating affordance (top-right of the chat view) that opens the Background
 * tasks panel. Shows an animated activity orb + the running-task count while
 * work is in flight, so background activity is visible without the panel open.
 */
@Composable
internal fun DesktopBackgroundTasksToggle(
    runningCount: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    /** Inside another bar (the canvas header): just the orb button, no surface of its own. */
    inBar: Boolean = false,
) {
    // Icon-only: the labelled pill was wide enough to collide with the pinned
    // prompt card; the tooltip carries the words (and the running count).
    // The caller's modifier (which includes the pane-level align) MUST sit on
    // this outermost Box: on the inner Surface it would bind to the tooltip
    // wrapper's internal layout and silently no-op — the chip then rendered
    // at the pane's top-LEFT instead of top-right.
    val tooltip = if (runningCount > 0) "Background tasks · $runningCount running" else "Background tasks"
    Box(modifier = modifier) {
        com.letta.mobile.desktop.DesktopTooltip(text = tooltip) {
            if (inBar) {
                Box(
                    modifier = Modifier
                        .size(LettaDimens.Control.iconButtonLg)
                        .clip(CircleShape)
                        // A button with the tooltip's words: the orb alone says nothing to a
                        // screen reader.
                        .clickable(role = Role.Button, onClick = onClick)
                        .semantics { contentDescription = tooltip },
                    contentAlignment = Alignment.Center,
                ) {
                    AgentActivityOrb(
                        size = LettaDimens.Control.icon,
                        activity = if (runningCount > 0) AgentActivity.Working else AgentActivity.Idle,
                    )
                }
                return@DesktopTooltip
            }
            Surface(
                onClick = onClick,
                shape = MaterialTheme.shapes.large,
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                shadowElevation = LettaDimens.Space.hair,
            ) {
                Box(modifier = Modifier.padding(LettaDimens.Space.sm)) {
                    AgentActivityOrb(
                        size = LettaDimens.Space.xl,
                        activity = if (runningCount > 0) AgentActivity.Working else AgentActivity.Idle,
                    )
                }
            }
        }
    }
}

/** The panel docked in a resizable side pane, which draws the title and close. */
@Composable
internal fun DesktopBackgroundTasksSidePane(
    subagents: List<SubagentEntry>,
    onClose: () -> Unit,
) {
    LettaSidePane(title = "Background tasks", onClose = onClose, initialWidth = 360.dp) {
        DesktopBackgroundTasksPanel(subagents = subagents)
    }
}
