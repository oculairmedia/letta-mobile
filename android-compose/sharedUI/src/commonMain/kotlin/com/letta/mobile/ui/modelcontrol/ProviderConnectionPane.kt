package com.letta.mobile.ui.modelcontrol

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import com.letta.mobile.data.repository.modelcontrol.ConnectableProvider
import com.letta.mobile.data.repository.modelcontrol.ProviderAdminState
import com.letta.mobile.ui.components.LettaSectionLabel
import com.letta.mobile.ui.theme.LettaDimens

/** Callbacks of [ProviderConnectionPane]; bound to a ProviderAdminController by each host. */
data class ProviderPaneActions(
    val onConnect: (ConnectableProvider) -> Unit,
    val onDisconnect: (ConnectableProvider) -> Unit,
    val form: ProviderFormActions,
    val onConfirmDisconnect: () -> Unit,
    val onDismissDisconnect: () -> Unit,
)

/**
 * App Server providers (letta-mobile-w4q4p): connected rows first with a
 * Disconnect action, then every connectable provider with Connect. Shared by
 * the Android Provider Admin screen and the desktop Providers pane.
 */
@Composable
fun ProviderConnectionPane(
    state: ProviderAdminState,
    actions: ProviderPaneActions,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize().testTag(ProviderPaneTags.PANE)) {
        if (state.loading || state.busy) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        ModelControlNotice(error = state.error, message = state.message)
        val (connected, available) = state.sortedProviders.partition { it.isConnected }
        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(LettaDimens.Space.xs),
            modifier = Modifier.fillMaxSize().padding(horizontal = LettaDimens.Space.lg),
        ) {
            item { LettaSectionLabel("Connected (${connected.size})") }
            items(connected, key = { "c-${it.id}" }) { provider ->
                ProviderRow(provider, busy = state.busy, onAction = { actions.onDisconnect(provider) })
            }
            item { LettaSectionLabel("Available (${available.size})") }
            items(available, key = { "a-${it.id}" }) { provider ->
                ProviderRow(provider, busy = state.busy, onAction = { actions.onConnect(provider) })
            }
        }
    }
    state.form?.let { ProviderConnectDialog(it, busy = state.busy, actions = actions.form) }
    state.pendingDisconnect?.let { provider ->
        DisconnectConfirmDialog(provider, onConfirm = actions.onConfirmDisconnect, onDismiss = actions.onDismissDisconnect)
    }
}

@Composable
private fun ProviderRow(provider: ConnectableProvider, busy: Boolean, onAction: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth().testTag("${ProviderPaneTags.ROW_PREFIX}${provider.id}")) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(LettaDimens.Space.md),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(LettaDimens.Space.sm),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(provider.displayName, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    text = providerSubtitle(provider),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            ProviderRowAction(provider, busy, onAction)
        }
    }
}

@Composable
private fun ProviderRowAction(provider: ConnectableProvider, busy: Boolean, onAction: () -> Unit) {
    when {
        provider.isConnected -> OutlinedButton(onClick = onAction, enabled = !busy) { Text("Disconnect") }
        provider.canConnectFromApp -> TextButton(onClick = onAction, enabled = !busy) { Text("Connect") }
        else -> Text(
            text = "Sign-in not supported on this device yet",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun providerSubtitle(provider: ConnectableProvider): String {
    val connection = provider.connections.firstOrNull()
    return listOfNotNull(
        connection?.providerName ?: provider.providerName,
        connection?.baseUrl,
        provider.description.takeIf { connection == null && it.isNotBlank() },
    ).joinToString(" · ")
}

@Composable
private fun DisconnectConfirmDialog(provider: ConnectableProvider, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Disconnect ${provider.displayName}?") },
        text = { Text("Its models disappear from every app's model picker until you connect it again.") },
        confirmButton = { TextButton(onClick = onConfirm) { Text("Disconnect") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
internal fun ModelControlNotice(error: String?, message: String?) {
    val text = error ?: message ?: return
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = if (error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(horizontal = LettaDimens.Space.lg, vertical = LettaDimens.Space.sm),
    )
}

object ProviderPaneTags {
    const val PANE = "provider_connection_pane"
    const val ROW_PREFIX = "provider_row_"
    const val SUBMIT = "provider_connect_submit"
}
