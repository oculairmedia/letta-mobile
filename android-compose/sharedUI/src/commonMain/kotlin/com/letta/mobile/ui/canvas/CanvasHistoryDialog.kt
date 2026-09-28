package com.letta.mobile.ui.canvas

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.letta.mobile.data.canvas.CanvasCheckpoint
import com.letta.mobile.ui.theme.LettaDimens

internal data class CanvasHistoryDialogState(
    val show: Boolean,
    val checkpoints: List<CanvasCheckpoint>,
    val compact: Boolean = false,
)

@Composable
internal fun CanvasHistoryDialog(
    state: CanvasHistoryDialogState,
    onDismiss: () -> Unit,
    onRestore: (CanvasCheckpoint) -> Unit,
) {
    if (!state.show) return
    if (state.compact) {
        ModalBottomSheet(onDismissRequest = onDismiss) {
            Column(modifier = Modifier.fillMaxWidth().padding(LettaDimens.Space.md)) {
                Text("Revision History", style = MaterialTheme.typography.titleMedium)
                CanvasHistoryContent(state.checkpoints, onRestore)
            }
        }
        return
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Revision History", style = MaterialTheme.typography.titleMedium) },
        text = { CanvasHistoryContent(state.checkpoints, onRestore) },
        confirmButton = {
            Button(onClick = onDismiss) { Text("Close") }
        },
    )
}

@Composable
private fun CanvasHistoryContent(
    checkpoints: List<CanvasCheckpoint>,
    onRestore: (CanvasCheckpoint) -> Unit,
) {
    if (checkpoints.isEmpty()) {
        Text("No revision checkpoints recorded yet.", style = MaterialTheme.typography.bodyMedium)
    } else {
        LazyColumn(
            modifier = Modifier.fillMaxWidth().height(300.dp),
            verticalArrangement = Arrangement.spacedBy(LettaDimens.Space.sm),
        ) {
            items(checkpoints) { cp -> CanvasHistoryCard(cp = cp, onRestore = { onRestore(cp) }) }
        }
    }
}

@Composable
private fun CanvasHistoryCard(
    cp: CanvasCheckpoint,
    onRestore: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(LettaDimens.Radius.sm),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = LettaDimens.Alpha.hairline),
        ),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(LettaDimens.Space.sm),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Revision ${cp.revision}",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
                val desc = if (cp.description.isNotBlank()) cp.description else "Actor: ${cp.actorId}"
                Text(
                    text = desc,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Button(
                onClick = onRestore,
                contentPadding = PaddingValues(horizontal = LettaDimens.Space.md, vertical = LettaDimens.Space.xs),
            ) {
                Text("Restore", style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}
