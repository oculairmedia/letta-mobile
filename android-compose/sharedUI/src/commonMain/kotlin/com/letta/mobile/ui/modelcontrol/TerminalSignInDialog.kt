package com.letta.mobile.ui.modelcontrol

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import com.letta.mobile.data.repository.modelcontrol.ConnectableProvider
import com.letta.mobile.data.repository.modelcontrol.ProviderManagementState
import com.letta.mobile.ui.host.LettaAlertPane
import com.letta.mobile.ui.host.LettaAlertSpec
import com.letta.mobile.ui.host.LettaPaneButton
import com.letta.mobile.ui.host.LettaPaneButtons
import com.letta.mobile.ui.host.LettaPaneKind
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
    val buttons = LettaPaneButtons(
        confirm = LettaPaneButton(ModelControlStrings.CHECK_AGAIN, onCheck, enabled = !state.busy, testTag = ProviderSettingsTags.CHECK_AGAIN),
        dismiss = LettaPaneButton(ModelControlStrings.CANCEL, onDismiss),
    )
    val spec = LettaAlertSpec(
        title = ModelControlStrings.connectTitle(provider.displayName),
        buttons = buttons,
        kind = LettaPaneKind.Dialog,
        icon = provider.logoOrNull() ?: LettaIcons.Terminal,
        testTag = ProviderSettingsTags.SIGN_IN,
    )
    LettaAlertPane(spec, onDismiss) {
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
