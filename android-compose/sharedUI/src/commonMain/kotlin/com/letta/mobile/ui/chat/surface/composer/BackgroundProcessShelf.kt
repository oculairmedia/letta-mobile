package com.letta.mobile.ui.chat.surface.composer

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import com.letta.mobile.data.transport.appserver.AppServerBackgroundProcess
import com.letta.mobile.ui.chat.ChatColumnMaxWidth
import com.letta.mobile.ui.theme.LettaDimens
import com.letta.mobile.ui.theme.customColors

/**
 * letta-mobile-bzvro.21: shelf above the composer listing active background processes
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

    Surface(
        modifier = modifier
            .widthIn(max = ChatColumnMaxWidth)
            .fillMaxWidth()
            .testTag("background_process_shelf"),
        shape = RoundedCornerShape(LettaDimens.Radius.md),
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(LettaDimens.Stroke.hairline, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(modifier = Modifier.padding(LettaDimens.Space.sm)) {
            Text(
                text = "Background processes (${processes.size})",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.customColors.runningColor,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(bottom = LettaDimens.Space.xs),
            )
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(LettaDimens.Space.sm),
                modifier = Modifier.fillMaxWidth(),
            ) {
                items(processes, key = { it.id }) { process ->
                    BackgroundProcessItem(
                        process = process,
                        onRequestStop = { confirmingProcessId = process.id },
                    )
                }
            }
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
    Surface(
        modifier = modifier.testTag("process_item_${process.id}"),
        shape = RoundedCornerShape(LettaDimens.Radius.sm),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        border = BorderStroke(LettaDimens.Stroke.hairline, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = LettaDimens.Space.sm, vertical = LettaDimens.Space.xs),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(LettaDimens.Space.xs),
        ) {
            val label = process.type?.uppercase() ?: "PROCESS"
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
            )
            val desc = process.description ?: process.id
            Text(
                text = desc,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurface,
            )
            process.ageSeconds?.let { age ->
                Text(
                    text = "${age}s",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(
                onClick = onRequestStop,
                modifier = Modifier.size(LettaDimens.Control.iconButtonSm),
            ) {
                Icon(
                    imageVector = Icons.Outlined.Close,
                    contentDescription = "Stop process ${process.id}",
                    modifier = Modifier.size(LettaDimens.Control.iconSm),
                    tint = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
private fun StopProcessConfirmDialog(
    processId: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Stop background process?") },
        text = { Text("This will stop process $processId immediately.") },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text("Stop", color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
    )
}
