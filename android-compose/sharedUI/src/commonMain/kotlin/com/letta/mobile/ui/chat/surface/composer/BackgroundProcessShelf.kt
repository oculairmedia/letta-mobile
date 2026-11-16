package com.letta.mobile.ui.chat.surface.composer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.letta.mobile.data.transport.appserver.AppServerBackgroundProcess
import com.letta.mobile.ui.components.foundation.LettaChip
import com.letta.mobile.ui.components.foundation.LettaChipSlots
import com.letta.mobile.ui.chat.ChatColumnMaxWidth
import com.letta.mobile.ui.host.LettaConfirmPane
import com.letta.mobile.ui.host.LettaConfirmRequest
import com.letta.mobile.ui.theme.LettaDimens
import com.letta.mobile.ui.icons.LettaIcons

/**
 * Shelf above the composer listing active background processes
 * reported by the App Server's `device_status.background_processes`. A chip's close button stops
 * its process (after a confirm); tapping an agent task's chip opens that subagent through [onOpen]
 * (the page's subagent view) where the host has one, and otherwise asks to stop it too.
 */
@Composable
fun BackgroundProcessShelf(
    processes: List<AppServerBackgroundProcess>,
    onStop: (String) -> Unit,
    modifier: Modifier = Modifier,
    onOpen: ((AppServerBackgroundProcess) -> Unit)? = null,
) {
    if (processes.isEmpty()) return

    var confirmingProcessId by remember { mutableStateOf<String?>(null) }

    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(LettaDimens.Space.sm),
        // The row keeps the column's width and start edge, but [modifier] measures the chips' own
        // extent: the lane lets its scroll-to-latest button sit beside a short row rather than above it.
        modifier = Modifier
            .widthIn(max = ChatColumnMaxWidth)
            .fillMaxWidth()
            .wrapContentWidth(Alignment.Start)
            .then(modifier)
            .testTag("background_process_shelf"),
    ) {
        items(processes, key = { it.id }) { process ->
            val open = onOpen?.takeIf { process.type == AGENT_TASK }
            BackgroundProcessItem(
                process = process,
                onRequestStop = { confirmingProcessId = process.id },
                onClick = open?.let { { it(process) } },
            )
        }
    }

    confirmingProcessId?.let { processId ->
        StopProcessConfirmDialog(
            processId = processId,
            onConfirm = {
                onStop(processId)
                confirmingProcessId = null
            },
            onDismiss = { confirmingProcessId = null },
        )
    }
}

@Composable
private fun BackgroundProcessItem(
    process: AppServerBackgroundProcess,
    onRequestStop: () -> Unit,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
) {
    val kindLabel = when (process.type) {
        "monitor" -> "Monitor"
        AGENT_TASK -> "Agent task"
        "bash" -> "Bash"
        "workflow" -> "Workflow"
        else -> "Process"
    }
    LettaChip(
        text = process.description ?: process.id,
        onClick = onClick ?: onRequestStop,
        modifier = modifier.testTag("process_item_${process.id}"),
        slots = LettaChipSlots(
            leading = {
                Text(
                    text = kindLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
            trailing = {
                process.ageSeconds?.let { age ->
                    Text(text = "${age}s", style = MaterialTheme.typography.labelSmall)
                }
                IconButton(
                    onClick = onRequestStop,
                    modifier = Modifier.size(LettaDimens.Control.iconButtonSm),
                ) {
                    Icon(
                        imageVector = LettaIcons.Close,
                        contentDescription = "Stop process ${process.id}",
                        modifier = Modifier.size(LettaDimens.Control.iconSm),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
        ),
    )
}

/** The process type of a dispatched subagent's run. */
private const val AGENT_TASK = "agent_task"

@Composable
private fun StopProcessConfirmDialog(
    processId: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val request = LettaConfirmRequest(
        title = "Stop background process?",
        message = "This will stop process $processId immediately.",
        confirmLabel = "Stop",
    )
    LettaConfirmPane(request, onConfirm = onConfirm, onDismiss = onDismiss)
}
