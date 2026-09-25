package com.letta.mobile.ui.memory

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.font.FontFamily
import com.letta.mobile.data.memory.graph.MemoryNodeEditor
import com.letta.mobile.data.memory.graph.MemoryNodeSelection
import com.letta.mobile.data.memory.graph.MemoryPageActions
import com.letta.mobile.ui.theme.LettaDimens

/**
 * In-card block editor. Saving goes through the controller, which writes the
 * block by agent + label so the App Server commits it to the MemFS repo.
 */
@Composable
internal fun ColumnScope.MemoryNodeEditorPane(
    selection: MemoryNodeSelection,
    editor: MemoryNodeEditor,
    actions: MemoryPageActions,
) {
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(selection.detail.nodeId) { focusRequester.requestFocus() }
    OutlinedTextField(
        value = editor.draft,
        onValueChange = actions::updateDraft,
        enabled = !editor.isSaving,
        isError = editor.exceeds(selection.detail.limit),
        textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
        supportingText = { MemoryDraftCounter(editor, selection.detail.limit) },
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = LettaDimens.Pane.editorMinHeight)
            .weight(1f, fill = false)
            .focusRequester(focusRequester),
    )
    editor.error?.let {
        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
    }
    MemoryEditorActions(selection, editor, actions)
}

@Composable
private fun MemoryDraftCounter(editor: MemoryNodeEditor, limit: Int?) {
    val count = editor.draft.length
    Text(if (limit != null) "$count / $limit" else "$count chars")
}

@Composable
private fun MemoryEditorActions(selection: MemoryNodeSelection, editor: MemoryNodeEditor, actions: MemoryPageActions) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(LettaDimens.Space.sm, Alignment.End),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextButton(onClick = actions::cancelEdit, enabled = !editor.isSaving) { Text("Cancel") }
        Button(onClick = actions::saveEdit, enabled = selection.canSave) {
            Text(if (editor.isSaving) "Saving…" else "Save")
        }
    }
}
