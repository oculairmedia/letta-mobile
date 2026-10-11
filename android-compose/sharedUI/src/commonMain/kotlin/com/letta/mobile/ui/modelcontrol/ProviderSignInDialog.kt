package com.letta.mobile.ui.modelcontrol

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import com.letta.mobile.data.repository.modelcontrol.ProviderSignInController
import com.letta.mobile.data.repository.modelcontrol.ProviderSignInState
import com.letta.mobile.ui.icons.LettaIcons
import com.letta.mobile.ui.theme.LettaDimens

/** Callbacks of [ProviderSignInDialog]; [bind] wires them to the shared sign-in controller. */
data class ProviderSignInActions(
    val onCodeChange: (String) -> Unit,
    val onSubmitCode: () -> Unit,
    val onRetry: () -> Unit,
    /** Cancel while running; close once connected or failed. */
    val onClose: () -> Unit,
) {
    companion object {
        val NONE = ProviderSignInActions(onCodeChange = {}, onSubmitCode = {}, onRetry = {}, onClose = {})

        fun bind(controller: ProviderSignInController?): ProviderSignInActions = controller?.let {
            ProviderSignInActions(
                onCodeChange = it::updateCode,
                onSubmitCode = it::submitCode,
                onRetry = it::retry,
                onClose = it::cancel,
            )
        } ?: NONE
    }
}

object ProviderSignInTags {
    const val SHEET = "provider_oauth_sheet"
    const val USER_CODE = "provider_oauth_user_code"
    const val COPY_CODE = "provider_oauth_copy_code"
    const val OPEN_PAGE = "provider_oauth_open_page"
    const val COPY_LINK = "provider_oauth_copy_link"
    const val CODE_FIELD = "provider_oauth_code_field"
    const val SUBMIT = "provider_oauth_submit"
    const val RETRY = "provider_oauth_retry"
    const val CLOSE = "provider_oauth_close"
    const val STATUS = "provider_oauth_status"
}

/**
 * The in-app subscription sign-in (letta-mobile-zxk2n.3), shared by phone and
 * desktop. Device-code providers show the code to enter and wait for the
 * approval; the others open the sign-in page and take back the code. The
 * browser and the clipboard come from Compose's platform locals, so neither
 * host binds anything.
 */
@Composable
fun ProviderSignInDialog(state: ProviderSignInState, actions: ProviderSignInActions) {
    val provider = state.provider ?: return
    val finished = state is ProviderSignInState.Connected || state is ProviderSignInState.Failed
    AlertDialog(
        onDismissRequest = actions.onClose,
        icon = { Icon(LettaIcons.AccountCircle, contentDescription = null) },
        title = { Text(ModelControlStrings.signInTitle(provider.displayName)) },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(LettaDimens.Space.sm),
                modifier = Modifier.fillMaxWidth().testTag(ProviderSignInTags.SHEET),
            ) { SignInBody(state, actions) }
        },
        confirmButton = { ConfirmButton(state, actions) },
        dismissButton = {
            TextButton(onClick = actions.onClose, modifier = Modifier.testTag(ProviderSignInTags.CLOSE)) {
                Text(if (finished) ModelControlStrings.CLOSE else ModelControlStrings.CANCEL)
            }
        },
    )
}

@Composable
private fun SignInBody(state: ProviderSignInState, actions: ProviderSignInActions) {
    when (state) {
        ProviderSignInState.Idle -> Unit
        is ProviderSignInState.Starting -> Progress(ModelControlStrings.SIGN_IN_STARTING)
        is ProviderSignInState.AwaitingApproval -> DeviceCodeBody(state)
        is ProviderSignInState.AwaitingCode -> PastedCodeBody(state, actions)
        is ProviderSignInState.Connecting -> Progress(ModelControlStrings.SIGN_IN_CONNECTING)
        is ProviderSignInState.Connected -> Outcome(LettaIcons.CheckCircle, ModelControlStrings.signInConnected(state.provider.displayName), error = false)
        is ProviderSignInState.Failed -> Outcome(LettaIcons.Error, state.message, error = true)
    }
}

@Composable
private fun ConfirmButton(state: ProviderSignInState, actions: ProviderSignInActions) {
    when (state) {
        is ProviderSignInState.AwaitingCode -> Button(
            onClick = actions.onSubmitCode,
            enabled = state.canSubmit,
            modifier = Modifier.testTag(ProviderSignInTags.SUBMIT),
        ) { Text(ModelControlStrings.SIGN_IN_CONNECT) }
        is ProviderSignInState.Failed -> Button(
            onClick = actions.onRetry,
            modifier = Modifier.testTag(ProviderSignInTags.RETRY),
        ) { Text(ModelControlStrings.SIGN_IN_TRY_AGAIN) }
        is ProviderSignInState.Connected -> Button(onClick = actions.onClose) { Text(ModelControlStrings.SIGN_IN_DONE) }
        else -> Unit
    }
}

@Composable
private fun DeviceCodeBody(state: ProviderSignInState.AwaitingApproval) {
    val clipboard = LocalClipboardManager.current
    Text(ModelControlStrings.SIGN_IN_ENTER_CODE, style = MaterialTheme.typography.bodyMedium)
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        shape = MaterialTheme.shapes.small,
        modifier = Modifier.fillMaxWidth(),
    ) {
        SelectionContainer {
            Text(
                text = state.userCode,
                style = MaterialTheme.typography.headlineSmall,
                fontFamily = FontFamily.Monospace,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(LettaDimens.Space.md).testTag(ProviderSignInTags.USER_CODE),
            )
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(LettaDimens.Space.sm), modifier = Modifier.fillMaxWidth()) {
        OutlinedButton(
            onClick = { clipboard.setText(AnnotatedString(state.userCode)) },
            modifier = Modifier.testTag(ProviderSignInTags.COPY_CODE),
        ) { Text(ModelControlStrings.SIGN_IN_COPY_CODE) }
        OpenPageButton(state.verificationUrl)
    }
    LinkText(state.verificationUrl)
    Progress(ModelControlStrings.SIGN_IN_WAITING)
    if (state.slowedDown) Hint(ModelControlStrings.SIGN_IN_SLOWED)
}

@Composable
private fun PastedCodeBody(state: ProviderSignInState.AwaitingCode, actions: ProviderSignInActions) {
    val clipboard = LocalClipboardManager.current
    val error = state.error
    Text(ModelControlStrings.SIGN_IN_CODE_STEP_1, style = MaterialTheme.typography.bodyMedium)
    Row(horizontalArrangement = Arrangement.spacedBy(LettaDimens.Space.sm), modifier = Modifier.fillMaxWidth()) {
        OpenPageButton(state.authorizeUrl)
        OutlinedButton(
            onClick = { clipboard.setText(AnnotatedString(state.authorizeUrl)) },
            modifier = Modifier.testTag(ProviderSignInTags.COPY_LINK),
        ) { Text(ModelControlStrings.SIGN_IN_COPY_LINK) }
    }
    Text(ModelControlStrings.SIGN_IN_CODE_STEP_2, style = MaterialTheme.typography.bodyMedium)
    OutlinedTextField(
        value = state.input,
        onValueChange = actions.onCodeChange,
        label = { Text(ModelControlStrings.SIGN_IN_CODE_LABEL) },
        singleLine = true,
        isError = error != null,
        supportingText = if (error != null) {
            { Text(error) }
        } else {
            null
        },
        modifier = Modifier.fillMaxWidth().testTag(ProviderSignInTags.CODE_FIELD),
    )
}

@Composable
private fun OpenPageButton(url: String) {
    val uriHandler = LocalUriHandler.current
    Button(
        // A host without a browser throws; the link stays visible to copy instead.
        onClick = { runCatching { uriHandler.openUri(url) } },
        modifier = Modifier.testTag(ProviderSignInTags.OPEN_PAGE),
    ) {
        Icon(LettaIcons.ExternalLink, contentDescription = null, modifier = Modifier.size(LettaDimens.Control.iconSm))
        Text(ModelControlStrings.SIGN_IN_OPEN_PAGE, modifier = Modifier.padding(start = LettaDimens.Space.xs))
    }
}

@Composable
private fun LinkText(url: String) {
    SelectionContainer {
        Text(url, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun Progress(label: String) {
    Column(
        verticalArrangement = Arrangement.spacedBy(LettaDimens.Space.xs),
        modifier = Modifier.fillMaxWidth().testTag(ProviderSignInTags.STATUS).semantics { liveRegion = LiveRegionMode.Polite },
    ) {
        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        Hint(label)
    }
}

@Composable
private fun Outcome(icon: ImageVector, text: String, error: Boolean) {
    val color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(LettaDimens.Space.sm),
        modifier = Modifier.fillMaxWidth().testTag(ProviderSignInTags.STATUS).semantics { liveRegion = LiveRegionMode.Polite },
    ) {
        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(LettaDimens.Control.icon))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = if (error) color else MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
private fun Hint(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}
