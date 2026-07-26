package com.letta.mobile.ui.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp

/**
 * An adaptive Material dialog that presents immutable text with native selection handles.
 *
 * The full value is selected when the dialog opens so copying a complete payload is a
 * single action, while the read-only field still allows a smaller range to be selected.
 */
@Composable
fun SelectableTextDialog(
    title: String,
    text: String,
    closeText: String,
    onDismiss: () -> Unit,
) {
    val focusRequester = remember { FocusRequester() }
    var fieldValue by remember(text) {
        mutableStateOf(
            TextFieldValue(
                text = text,
                selection = TextRange(0, text.length),
            ),
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            BasicTextField(
                value = fieldValue,
                onValueChange = { fieldValue = it.copy(text = text) },
                readOnly = true,
                textStyle = MaterialTheme.typography.bodyMedium.copy(
                    color = MaterialTheme.colorScheme.onSurface,
                ),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 480.dp)
                    .focusRequester(focusRequester),
            )
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(closeText)
            }
        },
    )

    LaunchedEffect(text) {
        focusRequester.requestFocus()
    }
}
