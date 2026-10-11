package com.letta.mobile.ui.shell.sidebar

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue

/** Test tags for the rename dialog. */
object ShellRenameChatDialogTags {
    const val FIELD = "shell-rename-chat-field"
    const val SAVE = "shell-rename-chat-save"
}

/**
 * letta-mobile-zxk2n.5 (G92): `/rename` for the open chat, a dialog with the title selected. Save
 * (or Enter) keeps a changed, non-blank title; anything else leaves the chat as it was.
 */
@Composable
fun ShellRenameChatDialog(title: String, onRename: (String) -> Unit, onDismiss: () -> Unit) {
    var value by remember(title) { mutableStateOf(TextFieldValue(title, TextRange(0, title.length))) }
    val focus = remember { FocusRequester() }
    val trimmed = value.text.trim()
    val canSave = trimmed.isNotEmpty() && trimmed != title.trim()
    val save = {
        if (canSave) onRename(trimmed)
        onDismiss()
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rename chat") },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { value = it },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { save() }),
                modifier = Modifier.fillMaxWidth().focusRequester(focus).testTag(ShellRenameChatDialogTags.FIELD),
            )
        },
        confirmButton = {
            TextButton(onClick = save, enabled = canSave, modifier = Modifier.testTag(ShellRenameChatDialogTags.SAVE)) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
}
