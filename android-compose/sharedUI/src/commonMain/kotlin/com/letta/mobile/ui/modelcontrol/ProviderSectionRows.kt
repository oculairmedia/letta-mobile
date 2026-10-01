package com.letta.mobile.ui.modelcontrol

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import com.letta.mobile.data.repository.modelcontrol.CatalogRow
import com.letta.mobile.data.repository.modelcontrol.ExposureChange
import com.letta.mobile.data.repository.modelcontrol.ProviderSection
import com.letta.mobile.ui.components.DisclosureChevron
import com.letta.mobile.ui.theme.LettaDimens

/** Rows of [ProviderManagementPane]: the provider card header, its action strip, and one model. */

private const val HIDDEN_ALPHA = 0.5f

/** Rows under a provider indent past its mark so the model names line up with the provider name. */
private val rowIndent = LettaDimens.Orb.md + LettaDimens.Space.sm + LettaDimens.Space.md

@Composable
internal fun ProviderHeaderRow(section: ProviderSection, expanded: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth().testTag("${ProviderManagementTags.PROVIDER_PREFIX}${section.key}"),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(LettaDimens.Space.md),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(LettaDimens.Space.sm),
        ) {
            ProviderMark(section)
            Column(modifier = Modifier.weight(1f)) {
                Text(section.displayName, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(LettaDimens.Space.xs)) {
                    StatusDot(connected = section.isConnected)
                    Text(
                        text = providerSubtitle(section),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (section.models.isNotEmpty()) CountPill(section)
            DisclosureChevron(expanded = expanded, compact = true)
        }
    }
}

/** A circle with the provider's initial, filled when it is connected. */
@Composable
private fun ProviderMark(section: ProviderSection) {
    val connected = section.isConnected
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(LettaDimens.Orb.md)
            .background(
                color = if (connected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHighest,
                shape = CircleShape,
            ),
    ) {
        Text(
            text = section.displayName.take(1).uppercase(),
            style = MaterialTheme.typography.titleSmall,
            color = if (connected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun StatusDot(connected: Boolean) {
    Box(
        modifier = Modifier
            .size(LettaDimens.Space.sm)
            .background(
                color = if (connected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                shape = CircleShape,
            ),
    )
}

/** "12 / 80" — exposed over total models, so a half-hidden provider reads at a glance. */
@Composable
private fun CountPill(section: ProviderSection) {
    Text(
        text = "${section.exposedCount} / ${section.models.size}",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .background(MaterialTheme.colorScheme.surfaceContainerHighest, MaterialTheme.shapes.small)
            .padding(horizontal = LettaDimens.Space.sm, vertical = LettaDimens.Space.hair),
    )
}

private fun providerSubtitle(section: ProviderSection): String {
    val provider = section.provider ?: return "Served by the host"
    val connection = provider.connections.firstOrNull()
    return when {
        connection != null -> listOfNotNull("Connected", connection.baseUrl ?: connection.providerName).joinToString(" · ")
        !provider.canConnectFromApp -> "Sign-in not supported on this device yet"
        provider.description.isNotBlank() -> provider.description
        else -> "Not connected"
    }
}

/** Connect / Disconnect on the left; show-all / hide-all on the right when there are models. */
@Composable
internal fun ProviderActionsRow(section: ProviderSection, busy: Boolean, actions: ProviderManagementActions) {
    val provider = section.provider
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = rowIndent, top = LettaDimens.Space.xs, bottom = LettaDimens.Space.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(LettaDimens.Space.sm),
    ) {
        when {
            provider == null -> Unit
            provider.isConnected -> OutlinedButton(
                onClick = { actions.onDisconnect(provider) },
                enabled = !busy,
                modifier = Modifier.testTag("${ProviderManagementTags.DISCONNECT_PREFIX}${section.key}"),
            ) { Text("Disconnect") }
            provider.canConnectFromApp -> Button(
                onClick = { actions.onConnect(provider) },
                enabled = !busy,
                modifier = Modifier.testTag("${ProviderManagementTags.CONNECT_PREFIX}${section.key}"),
            ) { Text("Connect") }
        }
        Spacer(modifier = Modifier.weight(1f))
        if (section.models.isNotEmpty()) {
            TextButton(
                onClick = { actions.onProviderExposedChange(section.key, true) },
                enabled = !section.allExposed,
                modifier = Modifier.testTag("${ProviderManagementTags.SHOW_ALL_PREFIX}${section.key}"),
            ) { Text("Show all") }
            TextButton(
                onClick = { actions.onProviderExposedChange(section.key, false) },
                enabled = !section.noneExposed,
                modifier = Modifier.testTag("${ProviderManagementTags.HIDE_ALL_PREFIX}${section.key}"),
            ) { Text("Hide all") }
        }
    }
}

/** Name, then the handle with where else the same model is served; hidden rows are dimmed. */
@Composable
internal fun ModelRow(row: CatalogRow, onExposedChange: (ExposureChange) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .alpha(if (row.exposed) 1f else HIDDEN_ALPHA)
            .padding(start = rowIndent, end = LettaDimens.Space.xs, top = LettaDimens.Space.xs, bottom = LettaDimens.Space.xs)
            .testTag("${ProviderManagementTags.MODEL_PREFIX}${row.handle.value}"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(LettaDimens.Space.sm),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(row.model.model.displayName, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                text = modelDetail(row),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Switch(
            checked = row.exposed,
            onCheckedChange = { onExposedChange(ExposureChange(row.handle, it)) },
            modifier = Modifier.testTag("${ProviderManagementTags.MODEL_SWITCH_PREFIX}${row.handle.value}"),
        )
    }
}

private fun modelDetail(row: CatalogRow): String = listOfNotNull(
    row.handle.value,
    row.alsoVia.takeIf { it.isNotEmpty() }?.let { "also via ${it.joinToString()}" },
    row.model.reasoningEfforts.takeIf { it.isNotEmpty() }?.let { "reasoning ${it.joinToString("/")}" },
).joinToString(" · ")
