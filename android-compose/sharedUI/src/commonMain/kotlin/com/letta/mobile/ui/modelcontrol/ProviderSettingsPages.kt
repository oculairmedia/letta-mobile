package com.letta.mobile.ui.modelcontrol

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import com.letta.mobile.data.repository.modelcontrol.ConnectableProvider
import com.letta.mobile.data.repository.modelcontrol.ProviderConnectMethod
import com.letta.mobile.data.repository.modelcontrol.ProviderManagementState
import com.letta.mobile.data.repository.modelcontrol.ProviderSettingsLists
import com.letta.mobile.data.repository.modelcontrol.connectMethod
import com.letta.mobile.data.repository.modelcontrol.connectedBaseUrl
import com.letta.mobile.ui.icons.LettaIcons
import com.letta.mobile.ui.theme.LettaDimens

private val pagePadding = PaddingValues(horizontal = LettaDimens.Space.lg, vertical = LettaDimens.Space.md)

/** "Connect an account", "Connected" and "Other providers". */
@Composable
internal fun AccountsPage(
    lists: ProviderSettingsLists,
    state: ProviderManagementState,
    actions: ProviderSettingsActions,
    onPageChange: (ProviderSettingsPage) -> Unit,
    modifier: Modifier,
) {
    LazyColumn(modifier = modifier, contentPadding = pagePadding, verticalArrangement = Arrangement.spacedBy(LettaDimens.Space.sm)) {
        item("connect-title") {
            SectionTitle(ModelControlStrings.CONNECT_ACCOUNT, subtitle = ModelControlStrings.CONNECT_ACCOUNT_SUBTITLE)
        }
        if (lists.accounts.isEmpty()) {
            item("accounts-empty") { Hint(ModelControlStrings.NO_ACCOUNTS_LEFT) }
        }
        items(lists.accounts, key = { "account-${it.id}" }) { provider ->
            ProviderCard(
                provider = provider,
                description = ModelControlStrings.METHOD_TERMINAL,
                leading = LettaIcons.Terminal,
                busy = state.busy,
                onClick = { actions.onOpenSignIn(provider) },
            )
        }
        item("browser-note") { Hint(ModelControlStrings.METHOD_BROWSER_UNSUPPORTED) }
        item("have-key") {
            TextButton(
                onClick = { onPageChange(ProviderSettingsPage.API_KEYS) },
                modifier = Modifier.testTag(ProviderSettingsTags.HAVE_KEY),
            ) { Text(ModelControlStrings.HAVE_API_KEY) }
        }
        connectedSection(lists.connected, state, actions, onPageChange)
        otherSection(lists.other, state, actions)
    }
}

private fun LazyListScope.connectedSection(
    connected: List<ConnectableProvider>,
    state: ProviderManagementState,
    actions: ProviderSettingsActions,
    onPageChange: (ProviderSettingsPage) -> Unit,
) {
    item("connected-title") { SectionTitle(ModelControlStrings.CONNECTED) }
    if (connected.isEmpty()) item("connected-empty") { Hint(ModelControlStrings.NOTHING_CONNECTED) }
    items(connected, key = { "connected-${it.id}" }) { provider ->
        ProviderCard(
            provider = provider,
            description = connectedDescription(provider),
            leading = methodIcon(provider),
            busy = state.busy,
            connected = true,
            onRemove = { actions.management.onDisconnect(provider) },
            onClick = {
                when (provider.connectMethod) {
                    ProviderConnectMethod.TERMINAL_SIGN_IN -> actions.onOpenSignIn(provider)
                    ProviderConnectMethod.API_KEY -> onPageChange(ProviderSettingsPage.API_KEYS)
                    ProviderConnectMethod.ENDPOINT -> onPageChange(ProviderSettingsPage.ENDPOINTS)
                    ProviderConnectMethod.NONE -> Unit
                }
            },
        )
    }
}

private fun LazyListScope.otherSection(other: List<ConnectableProvider>, state: ProviderManagementState, actions: ProviderSettingsActions) {
    if (other.isEmpty()) return
    item("other-title") { SectionTitle(ModelControlStrings.OTHER_PROVIDERS) }
    items(other, key = { "other-${it.id}" }) { provider ->
        ProviderCard(
            provider = provider,
            description = provider.description.ifBlank { methodLabel(provider) },
            leading = methodIcon(provider),
            busy = state.busy,
            onClick = { actions.management.onConnect(provider) },
        )
    }
}

/** The API keys and Custom Endpoints pages share one layout. */
internal enum class CredentialsKind(val subtitle: String, val addLabel: String, val replaceLabel: String) {
    API_KEYS(ModelControlStrings.API_KEYS_SUBTITLE, ModelControlStrings.ADD_KEY, ModelControlStrings.REPLACE_KEY),
    ENDPOINTS(ModelControlStrings.ENDPOINTS_SUBTITLE, ModelControlStrings.ADD_ENDPOINT, ModelControlStrings.EDIT),
}

@Composable
internal fun CredentialsPage(
    kind: CredentialsKind,
    providers: List<ConnectableProvider>,
    state: ProviderManagementState,
    actions: ProviderSettingsActions,
    modifier: Modifier,
) {
    LazyColumn(modifier = modifier, contentPadding = pagePadding, verticalArrangement = Arrangement.spacedBy(LettaDimens.Space.sm)) {
        item("subtitle") { Hint(kind.subtitle) }
        items(providers, key = { "cred-${it.id}" }) { provider ->
            CredentialCard(kind, provider, state, actions)
        }
        if (kind == CredentialsKind.ENDPOINTS) item("named-note") { Hint(ModelControlStrings.NAMED_ENDPOINTS_UNSUPPORTED) }
    }
}

@Composable
private fun CredentialCard(kind: CredentialsKind, provider: ConnectableProvider, state: ProviderManagementState, actions: ProviderSettingsActions) {
    val form = state.form?.takeIf { it.provider.id == provider.id }
    CardSurface(provider) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(LettaDimens.Space.md)) {
            MethodIcon(methodIcon(provider))
            Column(modifier = Modifier.weight(1f)) {
                CardTitle(provider.displayName)
                val detail = when {
                    !provider.isConnected -> ModelControlStrings.NOT_CONNECTED
                    kind == CredentialsKind.ENDPOINTS -> provider.connectedBaseUrl ?: ModelControlStrings.CONNECTED
                    else -> ModelControlStrings.KEY_SAVED
                }
                if (provider.isConnected) ConnectedBadge()
                CardDescription(detail)
            }
            if (form == null) {
                val label = if (provider.isConnected) kind.replaceLabel else kind.addLabel
                OutlinedButton(
                    onClick = {
                        if (provider.isConnected && kind == CredentialsKind.ENDPOINTS) actions.onEdit(provider) else actions.management.onConnect(provider)
                    },
                    enabled = !state.busy,
                    modifier = Modifier.testTag(
                        "${if (provider.isConnected) ProviderSettingsTags.EDIT_PREFIX else ProviderSettingsTags.ADD_PREFIX}${provider.id}",
                    ),
                ) { Text(label) }
            }
            if (provider.isConnected) RemoveButton(provider, enabled = !state.busy) { actions.management.onDisconnect(provider) }
        }
        if (form != null) InlineForm(kind, form, state, actions)
    }
}

@Composable
private fun InlineForm(
    kind: CredentialsKind,
    form: com.letta.mobile.data.repository.modelcontrol.ProviderConnectForm,
    state: ProviderManagementState,
    actions: ProviderSettingsActions,
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(top = LettaDimens.Space.md).testTag(ProviderSettingsTags.INLINE_FORM),
        verticalArrangement = Arrangement.spacedBy(LettaDimens.Space.sm),
    ) {
        ProviderFormFields(form, actions.management.form.onChange)
        if (kind == CredentialsKind.ENDPOINTS && form.provider.isConnected) Hint(ModelControlStrings.ENDPOINT_KEY_HINT)
        state.error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
        Row(horizontalArrangement = Arrangement.spacedBy(LettaDimens.Space.sm)) {
            Button(
                onClick = actions.management.form.onSubmit,
                enabled = form.canSubmit && !state.busy,
                modifier = Modifier.testTag(ProviderPaneTags.SUBMIT),
            ) { Text(if (kind == CredentialsKind.API_KEYS) ModelControlStrings.SAVE_AND_VERIFY else ModelControlStrings.SAVE) }
            TextButton(onClick = actions.management.form.onDismiss) { Text(ModelControlStrings.CANCEL) }
        }
    }
}

@Composable
private fun ProviderCard(
    provider: ConnectableProvider,
    description: String,
    leading: ImageVector,
    busy: Boolean,
    onClick: () -> Unit,
    connected: Boolean = false,
    onRemove: (() -> Unit)? = null,
) {
    CardSurface(provider, onClick = onClick, enabled = !busy) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(LettaDimens.Space.md)) {
            MethodIcon(leading)
            Column(modifier = Modifier.weight(1f)) {
                CardTitle(provider.displayName)
                if (connected) ConnectedBadge()
                CardDescription(description)
            }
            onRemove?.let { RemoveButton(provider, enabled = !busy, onClick = it) }
            // A navigation affordance (opens steps, a form or a page), not a disclosure.
            Icon(
                LettaIcons.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(LettaDimens.Control.icon),
            )
        }
    }
}

@Composable
private fun CardSurface(
    provider: ConnectableProvider,
    onClick: (() -> Unit)? = null,
    enabled: Boolean = true,
    content: @Composable () -> Unit,
) {
    val modifier = Modifier.fillMaxWidth().testTag("${ProviderSettingsTags.CARD_PREFIX}${provider.id}")
    val inner: @Composable () -> Unit = { Column(modifier = Modifier.padding(LettaDimens.Space.md)) { content() } }
    if (onClick != null) {
        Surface(
            onClick = onClick,
            enabled = enabled,
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.surfaceContainer,
            modifier = modifier,
            content = inner,
        )
    } else {
        Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainer, modifier = modifier, content = inner)
    }
}

@Composable
private fun MethodIcon(icon: ImageVector) {
    Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(LettaDimens.Control.icon))
}

@Composable
private fun CardTitle(text: String) {
    Text(text, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
}

@Composable
private fun CardDescription(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
    )
}

/** "✓ Connected" */
@Composable
private fun ConnectedBadge() {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(LettaDimens.Space.xs)) {
        Icon(LettaIcons.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(LettaDimens.Control.iconSm))
        Text(ModelControlStrings.CONNECTED_BADGE, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
    }
}

@Composable
private fun RemoveButton(provider: ConnectableProvider, enabled: Boolean, onClick: () -> Unit) {
    IconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.testTag("${ProviderSettingsTags.REMOVE_PREFIX}${provider.id}"),
    ) {
        Icon(LettaIcons.Delete, contentDescription = ModelControlStrings.removeLabel(provider.displayName))
    }
}

@Composable
private fun SectionTitle(title: String, subtitle: String? = null) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(top = LettaDimens.Space.md, bottom = LettaDimens.Space.xs),
        verticalArrangement = Arrangement.spacedBy(LettaDimens.Space.xs),
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
        subtitle?.let { Hint(it) }
    }
}

@Composable
private fun Hint(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

private fun methodIcon(provider: ConnectableProvider): ImageVector = when (provider.connectMethod) {
    ProviderConnectMethod.TERMINAL_SIGN_IN -> LettaIcons.Terminal
    ProviderConnectMethod.ENDPOINT -> LettaIcons.Server
    ProviderConnectMethod.API_KEY, ProviderConnectMethod.NONE -> LettaIcons.Key
}

private fun methodLabel(provider: ConnectableProvider): String = when (provider.connectMethod) {
    ProviderConnectMethod.TERMINAL_SIGN_IN -> ModelControlStrings.METHOD_TERMINAL
    ProviderConnectMethod.ENDPOINT -> ModelControlStrings.PAGE_ENDPOINTS
    ProviderConnectMethod.API_KEY, ProviderConnectMethod.NONE -> ModelControlStrings.METHOD_API_KEY
}

private fun connectedDescription(provider: ConnectableProvider): String = when (provider.connectMethod) {
    ProviderConnectMethod.TERMINAL_SIGN_IN -> ModelControlStrings.METHOD_TERMINAL_CONNECTED
    ProviderConnectMethod.ENDPOINT -> provider.connectedBaseUrl ?: ModelControlStrings.PAGE_ENDPOINTS
    ProviderConnectMethod.API_KEY, ProviderConnectMethod.NONE -> ModelControlStrings.METHOD_API_KEY
}
