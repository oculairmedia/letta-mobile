package com.letta.mobile.desktop.chat

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.letta.mobile.data.model.SubagentEntry
import com.letta.mobile.data.model.SubagentStatus
import com.letta.mobile.data.model.SubagentTodo
import java.time.Duration
import java.time.Instant

/**
 * Right-side "Background tasks" panel (Penpot "App Mockups v2" desktop board):
 * the active-subagent registry split into Running (animated activity orb +
 * expandable todo log) and Finished (status icon + Clear). Fed by the shared
 * SubagentRepository over the desktop WS side-channel.
 */
@Composable
internal fun DesktopBackgroundTasksPanel(
    subagents: List<SubagentEntry>,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    onFetchTodos: (suspend (String) -> List<SubagentTodo>)? = null,
) {
    var clearedKeys by remember { mutableStateOf<Set<String>>(emptySet()) }
    val running = subagents.filter { it.status == SubagentStatus.RUNNING }
    val finished = subagents.filter { it.status != SubagentStatus.RUNNING && it.entryKey() !in clearedKeys }

    Column(
        modifier = modifier
            .width(360.dp)
            .fillMaxHeight()
            .background(MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Background tasks",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            Icon(
                imageVector = Icons.Outlined.Close,
                contentDescription = "Close",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp).clickable(onClick = onClose),
            )
        }

        if (running.isEmpty() && finished.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxWidth().weight(1f),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "No background tasks",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            return@Column
        }

        LazyColumn(
            modifier = Modifier.fillMaxWidth().weight(1f).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (running.isNotEmpty()) {
                item { SectionHeader("Running") }
                items(running.size) { index -> RunningTaskCard(running[index], onFetchTodos) }
            }
            if (finished.isNotEmpty()) {
                item {
                    SectionHeader(
                        "Finished",
                        action = "Clear",
                        onAction = { clearedKeys = clearedKeys + finished.map { it.entryKey() } },
                    )
                }
                items(finished.size) { index -> FinishedTaskRow(finished[index]) }
            }
        }
    }
}

@Composable
private fun SectionHeader(
    text: String,
    action: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        if (action != null && onAction != null) {
            Text(
                text = action,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.clickable(onClick = onAction),
            )
        }
    }
}

@Composable
private fun RunningTaskCard(
    entry: SubagentEntry,
    onFetchTodos: (suspend (String) -> List<SubagentTodo>)?,
) {
    var expanded by remember(entry.entryKey()) { mutableStateOf(false) }
    var todos by remember(entry.entryKey()) { mutableStateOf<List<SubagentTodo>?>(null) }
    val canExpand = onFetchTodos != null && entry.toolCallId.isNotBlank()

    LaunchedEffect(expanded) {
        if (expanded && todos == null && onFetchTodos != null) {
            todos = runCatching { onFetchTodos(entry.toolCallId) }.getOrDefault(emptyList())
        }
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .let { if (canExpand) it.clickable { expanded = !expanded } else it }
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AgentActivityOrb(size = 30.dp, activity = AgentActivity.Working)
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(
                        text = entry.taskTitle(),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = entry.subtitle(),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (canExpand) {
                    Icon(
                        imageVector = if (expanded) Icons.Outlined.KeyboardArrowUp else Icons.Outlined.KeyboardArrowDown,
                        contentDescription = if (expanded) "Collapse" else "Expand",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
            if (expanded) {
                TodoLog(todos, entry)
            }
        }
    }
}

@Composable
private fun TodoLog(todos: List<SubagentTodo>?, entry: SubagentEntry) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp)
            .padding(bottom = 12.dp),
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.small,
            color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.6f),
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                when {
                    todos == null -> LogLine("…", "Loading activity", MaterialTheme.colorScheme.onSurfaceVariant)
                    todos.isEmpty() -> LogLine(
                        "›",
                        entry.todoProgress?.let { "${it.completed}/${it.total} steps" } ?: "No activity yet",
                        MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    else -> todos.take(20).forEach { todo -> TodoLine(todo) }
                }
            }
        }
    }
}

@Composable
private fun TodoLine(todo: SubagentTodo) {
    val (glyph, color) = when (todo.status) {
        "completed" -> "✓" to Color(0xFF34C759)
        "in_progress" -> "›" to Color(0xFFE0A33E)
        else -> "›" to MaterialTheme.colorScheme.onSurfaceVariant
    }
    val text = todo.activeForm.takeIf { it.isNotBlank() && todo.status == "in_progress" } ?: todo.content
    LogLine(glyph, text, color)
}

@Composable
private fun LogLine(glyph: String, text: String, color: Color) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = glyph,
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            color = color,
        )
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            color = color,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun FinishedTaskRow(entry: SubagentEntry) {
    val isFailure = entry.status == SubagentStatus.FAILED || entry.status == SubagentStatus.CANCELLED
    val accent = if (isFailure) MaterialTheme.colorScheme.error else Color(0xFF34C759)
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = if (isFailure) Icons.Outlined.ErrorOutline else Icons.Outlined.CheckCircle,
            contentDescription = null,
            tint = accent,
            modifier = Modifier.size(18.dp),
        )
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = entry.taskTitle(),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = entry.subtitle(),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
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
) {
    Surface(
        modifier = modifier.clickable(onClick = onClick),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        shadowElevation = 2.dp,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AgentActivityOrb(
                size = 22.dp,
                activity = if (runningCount > 0) AgentActivity.Working else AgentActivity.Idle,
            )
            Text(
                text = if (runningCount > 0) "Background tasks · $runningCount" else "Background tasks",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Medium,
            )
        }
    }
}

private fun SubagentEntry.entryKey(): String =
    toolCallId.takeIf { it.isNotBlank() } ?: taskId?.takeIf { it.isNotBlank() } ?: description

private fun SubagentEntry.taskTitle(): String =
    description.takeIf { it.isNotBlank() } ?: subagentType.takeIf { it.isNotBlank() } ?: "Subagent"

/** "Subagent · {type} · {elapsed}" — mirrors the board's "Subagent · Researcher · 1m 28s". */
private fun SubagentEntry.subtitle(): String {
    val parts = buildList {
        add("Subagent")
        subagentType.takeIf { it.isNotBlank() }?.let { add(it) }
        if (status != SubagentStatus.RUNNING) add(status.replaceFirstChar { it.uppercase() })
        elapsedLabel()?.let { add(it) }
        todoProgress?.takeIf { it.total > 0 }?.let { add("${it.completed}/${it.total}") }
    }
    return parts.joinToString(" · ")
}

/** Elapsed wall-clock since [SubagentEntry.startedAt], formatted "1m 28s" / "12s". */
private fun SubagentEntry.elapsedLabel(): String? {
    val start = startedAt?.let { runCatching { Instant.parse(it) }.getOrNull() } ?: return null
    val seconds = Duration.between(start, Instant.now()).seconds
    if (seconds < 0) return null
    return if (seconds >= 60) "${seconds / 60}m ${seconds % 60}s" else "${seconds}s"
}
