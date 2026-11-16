package com.letta.mobile.ui.chat.surface.composer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
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
 * reported by the App Server's `device_status.background_processes`.
 */
@Composable
fun BackgroundProcessShelf(
    processes: List<AppServerBackgroundProcess>,
    onStop: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (processes.isEmpty()) return

    var confirmingProcessId by remember { mutableStateOf<String?>(null) }

    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(LettaDimens.Space.sm),
        modifier = modifier
            .widthIn(max = ChatColumnMaxWidth)
            .fillMaxWidth()
            .testTag("background_process_shelf"),
    ) {
        items(processes, key = { it.id }) { process ->
            BackgroundProcessItem(
                process = process,
                onRequestStop = { confirmingProcessId = process.id },
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
) {
    val kindLabel = when (process.type) {
        "monitor" -> "Monitor"
        "agent_task" -> "Agent task"
        "bash" -> "Bash"
        "workflow" -> "Workflow"
        else -> "Process"
    }
    LettaChip(
        text = process.description ?: process.id,
        onClick = onRequestStop,
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
