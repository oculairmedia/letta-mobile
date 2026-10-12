package com.letta.mobile.ui.chat.branch

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.letta.mobile.sharedui.resources.Res
import com.letta.mobile.sharedui.resources.fork_dialog_body
import com.letta.mobile.sharedui.resources.fork_dialog_cancel
import com.letta.mobile.sharedui.resources.fork_dialog_confirm
import com.letta.mobile.sharedui.resources.fork_dialog_name
import com.letta.mobile.sharedui.resources.fork_dialog_title
import com.letta.mobile.ui.theme.LettaDimens
import org.jetbrains.compose.resources.stringResource

/** Test tags for [ForkNameDialog]. */
object ForkNameDialogTags {
    const val DIALOG = "fork_name_dialog"
    const val NAME = "fork_name_field"
    const val CONFIRM = "fork_name_confirm"
}

/**
 * letta-mobile-7mys7 (G37): names a fork as it is made. The name is optional: left blank, the fork
 * keeps the title the backend gives it. Shared by the chat rows ("Fork from here") and the
 * conversation list's "Fork…".
 */
@Composable
fun ForkNameDialog(
    onFork: (name: String?) -> Unit,
    onDismiss: () -> Unit,
    initialName: String = "",
) {
    var name by remember { mutableStateOf(initialName) }
    val confirm = { onFork(name.trim().takeIf { it.isNotEmpty() }) }
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag(ForkNameDialogTags.DIALOG),
        title = { Text(stringResource(Res.string.fork_dialog_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(LettaDimens.Space.sm)) {
                Text(stringResource(Res.string.fork_dialog_body), style = MaterialTheme.typography.bodyMedium)
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(Res.string.fork_dialog_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag(ForkNameDialogTags.NAME),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = confirm, modifier = Modifier.testTag(ForkNameDialogTags.CONFIRM)) {
                Text(stringResource(Res.string.fork_dialog_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(Res.string.fork_dialog_cancel)) }
        },
    )
}
