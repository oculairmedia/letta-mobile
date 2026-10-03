package com.letta.mobile.ui.chat.provenance

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import com.letta.mobile.ui.text.LettaSelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import com.letta.mobile.ui.components.DisclosureChevron
import com.letta.mobile.ui.theme.LettaDimens

/**
 * letta-mobile-bccty: shared (Android + Desktop + Web) VISUAL render for
 * structured inter-agent (a2a) provenance. The provenance itself
 * (sender, recipient, delivery state, ids) is built exclusively in
 * `sharedLogic/commonMain` (`AgentMessageProvenanceProjection`) — this
 * file only decides how it LOOKS, on every UI target that has Compose.
 *
 * Deliberately restrained: a compact one-line label with a small identity
 * tint + directional glyph, not a banner. Expansion (click) surfaces the
 * technical metadata (full agent ids, msgId, transport, routing
 * conversation, delivery state, failure detail) without ever touching the
 * message body's own readability.
 *
 * Shell wiring (resolve a stable agentId to a display name, open-tap to
 * navigate to the named agent) is the caller's responsibility — see
 * `LocalDesktopAgentMessageContext` on desktop for the equivalent
 * shell-side CompositionLocal. This file deliberately does NOT
 * CompositionLocal-look those up, so a brand-new shell (Android
 * feature-chat, future wasmWeb chat) can pass its own resolver/handler.
 */
@Composable
fun AgentMessageProvenanceLabel(
    provenance: AgentMessageProvenance,
    expanded: Boolean,
    onToggleExpand: () -> Unit,
    resolveName: ((agentId: String) -> String?)? = null,
    onAgentClick: ((agentId: String) -> Unit)? = null,
    /**
     * Set when the label is drawn inside a tinted bubble (the shared timeline's inter-agent prompt):
     * the bubble's content colour then carries the names and the quiet text, so they read on its
     * container. Null keeps the page tints (tertiary names, onSurfaceVariant text).
     */
    contentColor: Color? = null,
) {
    val spec = provenance.toLabelSpec(resolveName ?: { null })
    // Semantic identity tint — restrained (tertiary is the M3 "accent
    // distinct from primary" role), not a loud banner color. Failures use
    // the standard destructive (error) role regardless of direction, since a
    // failed send/receipt needs to be noticed.
    val tint = when {
        spec.isFailed -> MaterialTheme.colorScheme.error
        contentColor != null -> contentColor
        else -> MaterialTheme.colorScheme.tertiary
    }
    val muted = contentColor?.copy(alpha = IN_BUBBLE_MUTED_ALPHA) ?: MaterialTheme.colorScheme.onSurfaceVariant

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .semantics { contentDescription = spec.contentDescription },
    ) {
        AgentMessageProvenanceHeader(
            provenance = provenance,
            spec = spec,
            colors = ProvenanceHeaderColors(tint, muted),
            expanded = expanded,
            onToggleExpand = onToggleExpand,
            onAgentClick = onAgentClick ?: {},
        )
        if (expanded) AgentMessageProvenanceMetadata(provenance, tint)
    }
}

/** Public so any shell (desktop tool-cards, future wasm chat) can reuse the metadata block. */
@Composable
fun AgentMessageProvenanceMetadata(provenance: AgentMessageProvenance, tint: Color) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = LettaDimens.Space.sm),
        shape = RoundedCornerShape(LettaDimens.Radius.sm),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, tint.copy(alpha = 0.3f)),
    ) {
        LettaSelectionContainer {
            Column(
                modifier = Modifier.padding(LettaDimens.Space.md),
                verticalArrangement = Arrangement.spacedBy(LettaDimens.Space.xs),
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

/** The quiet text's strength against a bubble's content colour. */
private const val IN_BUBBLE_MUTED_ALPHA = 0.72f

/** The header's two inks: the agent names (and failures), and the quiet text around them. */
private data class ProvenanceHeaderColors(val tint: Color, val muted: Color)

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
    colors: ProvenanceHeaderColors,
    expanded: Boolean,
    onToggleExpand: () -> Unit,
    onAgentClick: (String) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggleExpand)
            .padding(bottom = LettaDimens.Space.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(LettaDimens.Space.sm),
    ) {
        AgentRoute(provenance, spec, colors, onAgentClick)
        DeliveryState(provenance.deliveryState, spec.isFailed, colors.muted)
        DisclosureChevron(
            expanded = expanded,
            contentDescription = if (expanded) {
                "Collapse agent message details"
            } else {
                "Expand agent message details"
            },
        )
    }
}

@Composable
private fun RowScope.AgentRoute(
    provenance: AgentMessageProvenance,
    spec: ProvenanceLabelSpec,
    colors: ProvenanceHeaderColors,
    onAgentClick: (String) -> Unit,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(LettaDimens.Space.hair),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.weight(1f, fill = false),
    ) {
        AgentLink(spec.fromLabel, provenance.fromAgentId, colors.tint, onAgentClick)
        Text("→", style = MaterialTheme.typography.labelMedium, color = colors.muted)
        AgentLink(spec.toLabel, provenance.toAgentId, colors.tint, onAgentClick)
        Text(
            text = " · Agent message",
            style = MaterialTheme.typography.labelMedium,
            color = colors.muted,
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
private fun DeliveryState(state: AgentMessageDeliveryState, isFailed: Boolean, muted: Color) {
    if (state == AgentMessageDeliveryState.RECEIVER_CONFIRMED) return
    Text(
        text = state.displayLabel(),
        style = MaterialTheme.typography.labelSmall,
        color = if (isFailed) MaterialTheme.colorScheme.error else muted,
    )
}

@Composable
private fun MetadataRow(label: String, value: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(LettaDimens.Space.sm)) {
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
