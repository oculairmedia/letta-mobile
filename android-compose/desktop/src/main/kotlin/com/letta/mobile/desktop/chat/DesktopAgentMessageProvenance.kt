package com.letta.mobile.desktop.chat

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.letta.mobile.data.messaging.AgentMessageDeliveryState
import com.letta.mobile.data.messaging.AgentMessageDirection
import com.letta.mobile.data.messaging.AgentMessageProvenance
import com.letta.mobile.data.messaging.agentMessageDisplayLabel
import com.letta.mobile.data.messaging.displayLabel

internal data class DesktopAgentMessageContext(
    val resolveName: (String) -> String? = { null },
    val onAgentClick: (String) -> Unit = {},
)

internal val LocalDesktopAgentMessageContext = staticCompositionLocalOf { DesktopAgentMessageContext() }

/**
 * letta-mobile-slqfp: desktop-only VISUAL render for structured inter-agent
 * (a2a) provenance. The provenance itself (sender, recipient, delivery
 * state, ids) is built exclusively in `sharedLogic/commonMain`
 * (`AgentMessageProvenanceProjection`) so Android reuses the identical
 * projection once its chat render lands (letta-mobile-wq0c8) — this file
 * only decides how it LOOKS on desktop.
 *
 * Deliberately restrained: a compact one-line label with a small identity
 * tint + directional glyph, not a banner. Expansion (click) surfaces the
 * technical metadata (full agent ids, msgId, transport, routing
 * conversation, delivery state / failure) without ever touching the message
 * body's own readability.
 */

/**
 * Compact label + icon + restrained identity tint for an inbound or
 * outbound inter-agent message. Click toggles the technical-metadata
 * expansion (agent ids, msgId, transport, routing conversation, delivery
 * state, failure detail). [onAgentClick] is an optional navigation hook —
 * fires with the agentId the user tapped (sender or recipient name); wiring
 * that to an actual "open this agent" action is owned by the shell
 * (letta-mobile-0lm22 / the LettaDesktopApp `openAgent` entry point), so it
 * defaults to a no-op here rather than reaching into shell state this file
 * doesn't own.
 */
@Composable
internal fun AgentMessageProvenanceLabel(
    provenance: AgentMessageProvenance,
    resolveName: ((agentId: String) -> String?)? = null,
    expanded: Boolean,
    onToggleExpand: () -> Unit,
    onAgentClick: ((agentId: String) -> Unit)? = null,
) {
    val context = LocalDesktopAgentMessageContext.current
    val spec = provenance.toLabelSpec(resolveName ?: context.resolveName)
    val effectiveAgentClick = onAgentClick ?: context.onAgentClick
    // Semantic identity tint — restrained (tertiary is the M3 "accent
    // distinct from primary" role), not a loud banner color. Failures use
    // the standard destructive (error) role regardless of direction, since a
    // failed send/receipt needs to be noticed.
    val tint = if (spec.isFailed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.tertiary

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .semantics { contentDescription = spec.contentDescription },
    ) {
        AgentMessageProvenanceHeader(
            provenance = provenance,
            spec = spec,
            tint = tint,
            expanded = expanded,
            onToggleExpand = onToggleExpand,
            onAgentClick = effectiveAgentClick,
        )
        if (expanded) AgentMessageProvenanceMetadata(provenance, tint)
    }
}

private data class ProvenanceLabelSpec(
    val fromLabel: String,
    val toLabel: String,
    val isInbound: Boolean,
    val isFailed: Boolean,
    val contentDescription: String,
)

private fun AgentMessageProvenance.toLabelSpec(resolveName: (String) -> String?): ProvenanceLabelSpec {
    val fromLabel = agentMessageDisplayLabel(fromAgentId, fromAgentName ?: resolveName(fromAgentId))
    val toLabel = agentMessageDisplayLabel(toAgentId, toAgentName ?: resolveName(toAgentId))
    val failureDetail = failureReason?.let { ": $it" }.orEmpty()
    return ProvenanceLabelSpec(
        fromLabel = fromLabel,
        toLabel = toLabel,
        isInbound = direction == AgentMessageDirection.INBOUND,
        isFailed = deliveryState == AgentMessageDeliveryState.FAILED,
        contentDescription = "Agent message, ${direction.name.lowercase()}, " +
            "from $fromLabel to $toLabel, ${deliveryState.displayLabel().lowercase()}$failureDetail",
    )
}

@Composable
private fun AgentMessageProvenanceHeader(
    provenance: AgentMessageProvenance,
    spec: ProvenanceLabelSpec,
    tint: Color,
    expanded: Boolean,
    onToggleExpand: () -> Unit,
    onAgentClick: (String) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggleExpand)
            .padding(bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        AgentRoute(provenance, spec, tint, onAgentClick)
        DeliveryState(provenance.deliveryState, spec.isFailed)
        ExpansionIcon(expanded)
    }
}

@Composable
private fun RowScope.AgentRoute(
    provenance: AgentMessageProvenance,
    spec: ProvenanceLabelSpec,
    tint: Color,
    onAgentClick: (String) -> Unit,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.weight(1f, fill = false),
    ) {
        AgentLink(spec.fromLabel, provenance.fromAgentId, tint, onAgentClick)
        Text("→", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        AgentLink(spec.toLabel, provenance.toAgentId, tint, onAgentClick)
        Text(
            text = " · Agent message",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun AgentLink(label: String, agentId: String, tint: Color, onAgentClick: (String) -> Unit) {
    Text(
        text = label,
        style = MaterialTheme.typography.labelMedium,
        color = tint,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.clickable(role = Role.Button) { onAgentClick(agentId) },
    )
}

@Composable
private fun DeliveryState(state: AgentMessageDeliveryState, isFailed: Boolean) {
    if (state == AgentMessageDeliveryState.RECEIVER_CONFIRMED) return
    Text(
        text = state.displayLabel(),
        style = MaterialTheme.typography.labelSmall,
        color = if (isFailed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun ExpansionIcon(expanded: Boolean) {
    Icon(
        imageVector = if (expanded) Icons.Outlined.KeyboardArrowUp else Icons.Outlined.KeyboardArrowDown,
        contentDescription = if (expanded) "Collapse agent message details" else "Expand agent message details",
        modifier = Modifier.padding(0.dp),
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
internal fun AgentMessageProvenanceMetadata(provenance: AgentMessageProvenance, tint: Color) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp),
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, tint.copy(alpha = 0.3f)),
    ) {
        SelectionContainer {
            Column(
                modifier = Modifier.padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                MetadataRow("Direction", provenance.direction.name.lowercase())
                MetadataRow("From agent id", provenance.fromAgentId)
                MetadataRow("To agent id", provenance.toAgentId)
                MetadataRow("Message id", provenance.msgId.ifBlank { "(unknown — transport did not confirm)" })
                MetadataRow("Transport", provenance.transport)
                provenance.routingConversationId?.let { MetadataRow("Routing conversation", it) }
                MetadataRow("Delivery state", provenance.deliveryState.displayLabel())
                provenance.failureReason?.let { MetadataRow("Failure detail", it) }
                provenance.ackLatencyMs?.let { MetadataRow("Ack latency", "$it ms") }
            }
        }
    }
}

@Composable
private fun MetadataRow(label: String, value: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = "$label:",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}
