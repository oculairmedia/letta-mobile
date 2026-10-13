package com.letta.mobile.ui.memory

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import com.letta.mobile.data.memory.graph.MemoryBlockDeletion
import com.letta.mobile.data.memory.graph.MemoryBlockDraft
import com.letta.mobile.data.memory.graph.MemoryBlockLifecycleActions
import com.letta.mobile.ui.host.LettaAlertPane
import com.letta.mobile.ui.host.LettaAlertSpec
import com.letta.mobile.ui.host.LettaPaneButton
import com.letta.mobile.ui.host.LettaPaneButtons
import com.letta.mobile.ui.host.LettaPaneKind
import com.letta.mobile.ui.theme.LettaDimens

/** The page's modal layer: the "New block" pane and the delete confirmation. */
@Composable
internal fun MemoryBlockDialogs(
    creation: MemoryBlockDraft?,
    deletion: MemoryBlockDeletion?,
    actions: MemoryBlockLifecycleActions,
) {
    creation?.let { MemoryBlockCreateDialog(it, actions) }
    deletion?.let { MemoryBlockDeleteDialog(it, actions) }
}

/**
 * New block: a label (validated as one MemFS file name) and an initial value.
 * The node refuses a label that already exists, and that error lands here.
 */
@Composable
private fun MemoryBlockCreateDialog(draft: MemoryBlockDraft, actions: MemoryBlockLifecycleActions) {
    val buttons = LettaPaneButtons(
        confirm = LettaPaneButton(if (draft.isSaving) "Creating…" else "Create", actions::submitCreate, enabled = draft.canSubmit),
        dismiss = LettaPaneButton("Cancel", actions::cancelCreate, enabled = !draft.isSaving),
    )
    LettaAlertPane(LettaAlertSpec("New memory block", buttons, kind = LettaPaneKind.Dialog), actions::cancelCreate) {
        // Only flag the label once the user has typed something.
        val labelError = draft.labelError.takeIf { draft.label.isNotEmpty() }
        OutlinedTextField(
            value = draft.label,
            onValueChange = actions::updateCreateLabel,
            label = { Text("Label") },
            placeholder = { Text("project_notes") },
            singleLine = true,
            isError = labelError != null,
            supportingText = labelError?.let { { Text(it) } },
            enabled = !draft.isSaving,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = draft.value,
            onValueChange = actions::updateCreateValue,
            label = { Text("Contents") },
            textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
            enabled = !draft.isSaving,
            modifier = Modifier.fillMaxWidth().heightIn(min = LettaDimens.Pane.sidePanelWidth / CONTENT_HEIGHT_DIVISOR),
        )
        draft.error?.let { MemoryDialogError(it) }
    }
}

/** Delete confirmation: deleting commits a MemFS removal, so it is never one tap. */
@Composable
private fun MemoryBlockDeleteDialog(deletion: MemoryBlockDeletion, actions: MemoryBlockLifecycleActions) {
    val buttons = LettaPaneButtons(
        confirm = LettaPaneButton(
            if (deletion.isDeleting) "Deleting…" else "Delete",
            actions::confirmDelete,
            enabled = !deletion.isDeleting,
            destructive = true,
        ),
        dismiss = LettaPaneButton("Cancel", actions::cancelDelete, enabled = !deletion.isDeleting),
    )
    LettaAlertPane(LettaAlertSpec("Delete “${deletion.ref.label}”?", buttons), actions::cancelDelete) {
        Text(
            "The block is removed from this agent's memory and the change is committed. " +
                "The agent stops seeing it on its next turn.",
            style = MaterialTheme.typography.bodyMedium,
        )
        deletion.error?.let { MemoryDialogError(it) }
    }
}

@Composable
private fun MemoryDialogError(message: String) {
    Text(message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
}

private const val CONTENT_HEIGHT_DIVISOR = 3
