package com.letta.mobile.ui.components

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import com.letta.mobile.ui.haptics.HapticEffects


@Composable
fun ConfirmDialog(
    show: Boolean,
    title: String,
    message: String,
    confirmText: String,
    dismissText: String? = null,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    destructive: Boolean = false,
) {
    if (!show) return
    val haptic = LocalHapticFeedback.current
    val view = LocalView.current

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = {
            TextButton(onClick = {
                HapticEffects.confirm(haptic, view)
                onConfirm()
            }) {
                Text(
                    text = confirmText,
                    color = if (destructive) MaterialTheme.colorScheme.error else Color.Unspecified,
                )
            }
        },
        dismissButton = if (!dismissText.isNullOrBlank()) {
            { TextButton(onClick = onDismiss) { Text(dismissText) } }
        } else {
            null
        },
    )
}


@Composable
fun ConfirmDialog(
    show: Boolean,
    title: String,
    confirmText: String,
    dismissText: String? = null,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    destructive: Boolean = false,
    content: @Composable () -> Unit,
) {
    if (!show) return
    val haptic = LocalHapticFeedback.current
    val view = LocalView.current

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = content,
        confirmButton = {
            TextButton(onClick = {
                HapticEffects.confirm(haptic, view)
                onConfirm()
            }) {
                Text(
                    text = confirmText,
                    color = if (destructive) MaterialTheme.colorScheme.error else Color.Unspecified,
                )
            }
        },
        dismissButton = if (!dismissText.isNullOrBlank()) {
            { TextButton(onClick = onDismiss) { Text(dismissText) } }
        } else {
            null
        },
    )
}
