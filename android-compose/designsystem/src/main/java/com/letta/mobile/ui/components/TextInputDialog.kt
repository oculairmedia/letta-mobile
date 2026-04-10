package com.letta.mobile.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun TextInputDialog(
    show: Boolean,
    title: String,
    label: String,
    confirmText: String,
    dismissText: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
    initialValue: String = "",
    placeholder: String = "",
    singleLine: Boolean = true,
    minLines: Int = 1,
    validate: (String) -> Boolean = { it.isNotBlank() },
) {
    if (!show) return

    var value by remember(show) { mutableStateOf(initialValue) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { value = it },
                label = { Text(label) },
                placeholder = if (placeholder.isNotEmpty()) {
                    { Text(placeholder) }
                } else {
                    null
                },
                singleLine = singleLine,
                minLines = minLines,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(value) },
                enabled = validate(value),
            ) {
                Text(confirmText)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(dismissText)
            }
        },
    )
}

@Composable
fun MultiFieldInputDialog(
    show: Boolean,
    title: String,
    confirmText: String,
    dismissText: String,
    onDismiss: () -> Unit,
    confirmEnabled: Boolean = true,
    onConfirm: () -> Unit,
    content: @Composable () -> Unit,
) {
    if (!show) return

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = content,
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                enabled = confirmEnabled,
            ) {
                Text(confirmText)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(dismissText)
            }
        },
    )
}
