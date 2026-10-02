package com.letta.mobile.ui.modelcontrol

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import com.letta.mobile.data.repository.modelcontrol.ConnectableProvider
import com.letta.mobile.data.repository.modelcontrol.ProviderManagementState
import com.letta.mobile.ui.icons.LettaIcons
import com.letta.mobile.ui.theme.LettaDimens

/**
 * How to connect a subscription account. The App Server accepts finished
 * OAuth tokens but runs no browser or device-code login of its own; letta's
 * terminal `/connect` does, and writes the same provider store the host reads.
 * "Check again" re-lists providers until the account shows up.
 */
@Composable
internal fun TerminalSignInDialog(
    provider: ConnectableProvider,
    state: ProviderManagementState,
    onCheck: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(LettaIcons.Terminal, contentDescription = null) },
        title = { Text(ModelControlStrings.connectTitle(provider.displayName)) },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(LettaDimens.Space.sm),
                modifier = Modifier.testTag(ProviderSettingsTags.SIGN_IN),
            ) {
                Text(ModelControlStrings.SIGN_IN_STEP_1, style = MaterialTheme.typography.bodyMedium)
                CommandLine(ModelControlStrings.SIGN_IN_COMMAND)
                Text(ModelControlStrings.SIGN_IN_STEP_2, style = MaterialTheme.typography.bodyMedium)
                CommandLine(provider.displayName)
                Text(ModelControlStrings.SIGN_IN_STEP_3, style = MaterialTheme.typography.bodyMedium)
                if (state.busy) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                val notice = state.error ?: state.message
                notice?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (state.error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = onCheck,
                enabled = !state.busy,
                modifier = Modifier.testTag(ProviderSettingsTags.CHECK_AGAIN),
            ) { Text(ModelControlStrings.CHECK_AGAIN) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(ModelControlStrings.CANCEL) } },
    )
}

@Composable
private fun CommandLine(text: String) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        shape = MaterialTheme.shapes.small,
        modifier = Modifier.fillMaxWidth(),
    ) {
        SelectionContainer {
            Text(
                text = text,
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.padding(horizontal = LettaDimens.Space.md, vertical = LettaDimens.Space.sm),
            )
        }
    }
}
