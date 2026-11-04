package com.letta.mobile.ui.chat.subagents

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.letta.mobile.data.model.SubagentEntry
import com.letta.mobile.ui.chat.AgentActivity
import com.letta.mobile.ui.chat.AgentActivityOrb
import com.letta.mobile.ui.host.InspectorPane
import com.letta.mobile.ui.host.LocalLettaHost
import com.letta.mobile.ui.theme.LettaDimens
import kotlin.time.Clock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

object BackgroundTasksTags {
    const val PANEL = "background_tasks_panel"
    const val TOGGLE = "background_tasks_toggle"
}

/**
 * The "Background tasks" panel (shared): the shared
 * [SubagentRosterList] over the shared [projectSubagentRoster], so every client lists the same
 * subagents in the same states. No header of its own: the hosting pane draws the title and close.
 */
@Composable
fun BackgroundTasksPanel(
    subagents: List<SubagentEntry>,
    modifier: Modifier = Modifier,
    onOpenConversation: OpenSubagentConversation? = null,
    now: () -> Long = { Clock.System.now().toEpochMilliseconds() },
    onDismiss: ((SubagentEntry) -> Unit)? = null,
    dismissTask: (suspend (SubagentEntry) -> Result<Unit>)? = null,
) {
    val coroutineScope = rememberCoroutineScope()
    var error by remember { mutableStateOf(false) }
    var pending by remember { mutableStateOf(false) }
    val dismiss: ((SubagentEntry) -> Unit)? = if (dismissTask != null && !pending) {
        { entry ->
            coroutineScope.launch {
                pending = true
                error = false
                try {
                    dismissTask(entry).getOrThrow()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    error = true
                } finally {
                    pending = false
                }
            }
        }
    } else {
        onDismiss
    }
    Box(
        modifier = modifier
            .fillMaxWidth()
            .fillMaxHeight()
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .testTag(BackgroundTasksTags.PANEL),
    ) {
        Column {
            if (error) {
                Text(
                    text = "Could not dismiss task. Please try again.",
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(LettaDimens.Space.lg),
                )
            }
            SubagentRoster(
                entries = subagents,
                onOpenConversation = onOpenConversation,
                onDismiss = dismiss,
                modifier = Modifier.fillMaxHeight().padding(horizontal = LettaDimens.Space.lg),
                now = now,
            )
        }
    }
}

/**
 * The panel in the window's inspector (S13): docked on the right of a
 * wide window's [com.letta.mobile.ui.host.InspectorFrame], a sheet when compact. The inspector
 * draws the title and close. [onOpenConversation] opens a row's conversation (the chat's subagent
 * dual view); null hides "View conversation".
 */
@Composable
fun BackgroundTasksSidePane(
    subagents: List<SubagentEntry>,
    onClose: () -> Unit,
    onOpenConversation: OpenSubagentConversation? = null,
    onDismiss: ((SubagentEntry) -> Unit)? = null,
    dismissTask: (suspend (SubagentEntry) -> Result<Unit>)? = null,
) {
    InspectorPane(title = BACKGROUND_TASKS_TITLE, onClose = onClose, width = SIDE_PANE_WIDTH) {
        BackgroundTasksPanel(
            subagents = subagents,
            onOpenConversation = onOpenConversation,
            onDismiss = onDismiss,
            dismissTask = dismissTask,
        )
    }
}

/** What the toggle's tooltip (and its screen-reader label) says. */
fun backgroundTasksLabel(runningCount: Int): String =
    if (runningCount > 0) "$BACKGROUND_TASKS_TITLE · $runningCount running" else BACKGROUND_TASKS_TITLE

/**
 * Opens the Background tasks panel: an activity orb that animates while work is in flight, so
 * background activity is visible with the panel closed. [inBar] draws just the orb button for a
 * host bar; otherwise it floats on its own surface. Icon-only: the host's tooltip carries the words.
 */
@Composable
fun BackgroundTasksToggle(
    runningCount: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    inBar: Boolean = false,
) {
    val label = backgroundTasksLabel(runningCount)
    val activity = if (runningCount > 0) AgentActivity.Working else AgentActivity.Idle
    // The caller's modifier (its pane-level align) sits on this outermost Box: inside the tooltip
    // wrapper it would bind to the wrapper's layout and silently no-op.
    Box(modifier = modifier) {
        LocalLettaHost.current.tooltip(label) {
            if (inBar) {
                Box(
                    modifier = Modifier
                        .size(LettaDimens.Control.iconButtonLg)
                        .clip(CircleShape)
                        .clickable(role = Role.Button, onClick = onClick)
                        .semantics { contentDescription = label }
                        .testTag(BackgroundTasksTags.TOGGLE),
                    contentAlignment = Alignment.Center,
                ) {
                    AgentActivityOrb(size = LettaDimens.Control.icon, activity = activity)
                }
            } else {
                Surface(
                    onClick = onClick,
                    shape = MaterialTheme.shapes.large,
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    border = BorderStroke(LettaDimens.Stroke.hairline, MaterialTheme.colorScheme.outlineVariant),
                    shadowElevation = LettaDimens.Space.hair,
                    modifier = Modifier.semantics { contentDescription = label }.testTag(BackgroundTasksTags.TOGGLE),
                ) {
                    Box(modifier = Modifier.padding(LettaDimens.Space.sm)) {
                        AgentActivityOrb(size = LettaDimens.Space.xl, activity = activity)
                    }
                }
            }
        }
    }
}

private const val BACKGROUND_TASKS_TITLE = "Background tasks"
private val SIDE_PANE_WIDTH = 360.dp
