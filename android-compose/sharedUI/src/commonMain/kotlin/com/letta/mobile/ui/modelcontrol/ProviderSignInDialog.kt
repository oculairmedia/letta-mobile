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
import com.letta.mobile.data.repository.modelcontrol.ConnectableProvider
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
 * host binds anything; they are read here, in the host's composition, since
 * a dialog window may provide its own.
 */
@Composable
fun ProviderSignInDialog(state: ProviderSignInState, actions: ProviderSignInActions) {
    val provider = state.provider ?: return
    val uriHandler = LocalUriHandler.current
    val clipboard = LocalClipboardManager.current
    val platform = SignInPlatform(
        // A host without a browser throws; the link stays visible to copy instead.
        openUrl = { url -> runCatching { uriHandler.openUri(url) } },
        copy = { text -> clipboard.setText(AnnotatedString(text)) },
    )
    val finished = state is ProviderSignInState.Connected || state is ProviderSignInState.Failed
    AlertDialog(
        // A stray tap outside or Back must not drop a running sign-in; Cancel does that explicitly.
        onDismissRequest = { if (finished) actions.onClose() },
        icon = { Icon(provider.logoOrNull() ?: LettaIcons.AccountCircle, contentDescription = null) },
        title = { Text(provider.signInTitle) },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(LettaDimens.Space.sm),
                modifier = Modifier.fillMaxWidth().testTag(ProviderSignInTags.SHEET),
            ) { SignInBody(state, actions, platform) }
        },
        confirmButton = { ConfirmButton(state, actions) },
        dismissButton = {
            TextButton(
                onClick = actions.onClose,
                enabled = state !is ProviderSignInState.Connecting,
                modifier = Modifier.testTag(ProviderSignInTags.CLOSE),
            ) {
                Text(if (finished) ModelControlStrings.CLOSE else ModelControlStrings.CANCEL)
            }
        },
    )
}

/** The browser and clipboard of the host the sheet is shown on. */
private class SignInPlatform(val openUrl: (String) -> Unit, val copy: (String) -> Unit)

@Composable
private fun SignInBody(state: ProviderSignInState, actions: ProviderSignInActions, platform: SignInPlatform) {
    when (state) {
        ProviderSignInState.Idle -> Unit
        is ProviderSignInState.Starting -> Progress(ModelControlStrings.SIGN_IN_STARTING)
        is ProviderSignInState.AwaitingApproval -> DeviceCodeBody(state, platform)
        is ProviderSignInState.AwaitingCode -> PastedCodeBody(state, actions, platform)
        is ProviderSignInState.Connecting -> Progress(ModelControlStrings.SIGN_IN_CONNECTING)
        is ProviderSignInState.Connected -> Outcome(LettaIcons.CheckCircle, state.provider.signInConnected, error = false)
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
private fun DeviceCodeBody(state: ProviderSignInState.AwaitingApproval, platform: SignInPlatform) {
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
            onClick = { platform.copy(state.userCode) },
            modifier = Modifier.testTag(ProviderSignInTags.COPY_CODE),
        ) { Text(ModelControlStrings.SIGN_IN_COPY_CODE) }
        OpenPageButton(state.verificationUrl, platform)
    }
    LinkText(state.verificationUrl)
    Progress(ModelControlStrings.SIGN_IN_WAITING)
    if (state.slowedDown) Hint(ModelControlStrings.SIGN_IN_SLOWED)
}

@Composable
private fun PastedCodeBody(state: ProviderSignInState.AwaitingCode, actions: ProviderSignInActions, platform: SignInPlatform) {
    val error = state.error
    Text(ModelControlStrings.SIGN_IN_CODE_STEP_1, style = MaterialTheme.typography.bodyMedium)
    Row(horizontalArrangement = Arrangement.spacedBy(LettaDimens.Space.sm), modifier = Modifier.fillMaxWidth()) {
        OpenPageButton(state.authorizeUrl, platform)
        OutlinedButton(
            onClick = { platform.copy(state.authorizeUrl) },
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
private fun OpenPageButton(url: String, platform: SignInPlatform) {
    Button(
        onClick = { platform.openUrl(url) },
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

/** "Sign in to xAI (Grok/X subscription)" */
internal val ConnectableProvider.signInTitle: String get() = ModelControlStrings.SIGN_IN_TITLE_PREFIX_TEXT + displayName

/** What the sheet says once the host accepted the sign-in. */
internal val ConnectableProvider.signInConnected: String get() = displayName + ModelControlStrings.SIGN_IN_CONNECTED_SUFFIX

@Composable
private fun Hint(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}
