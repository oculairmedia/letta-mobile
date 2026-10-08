package com.letta.mobile.ui.shell.sidebar

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import com.letta.mobile.ui.theme.LettaDimens

/** Test tags of the conversation row's rename and pin. */
object ShellConversationTags {
    const val RENAME_FIELD = "shell_conversation_rename"
    const val PINNED = "shell_conversation_pinned"
}

/**
 * letta-mobile-bzvro.17: a conversation's title, editable in place. Enter (or the keyboard's
 * Done) saves a changed, non-blank title; Escape, or saving the same title, leaves it as it was.
 */
@Composable
fun ShellConversationRenameField(
    title: String,
    onRename: (String) -> Unit,
    onDone: () -> Unit,
) {
    var value by remember { mutableStateOf(TextFieldValue(title, selection = TextRange(0, title.length))) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    val commit = {
        val next = value.text.trim()
        if (next.isNotEmpty() && next != title) onRename(next)
        onDone()
    }
    OutlinedTextField(
        value = value,
        onValueChange = { value = it },
        singleLine = true,
        textStyle = MaterialTheme.typography.bodyMedium,
        placeholder = { Text("Chat name") },
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { commit() }),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = LettaDimens.Space.xs)
            .focusRequester(focus)
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (event.key) {
                    Key.Enter, Key.NumPadEnter -> true.also { commit() }
                    Key.Escape -> true.also { onDone() }
                    else -> false
                }
            }
            .testTag(ShellConversationTags.RENAME_FIELD),
    )
}
