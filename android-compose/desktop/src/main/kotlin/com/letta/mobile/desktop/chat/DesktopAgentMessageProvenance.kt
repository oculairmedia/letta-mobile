package com.letta.mobile.desktop.chat

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CallMade
import androidx.compose.material.icons.outlined.CallReceived
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.letta.mobile.data.messaging.AgentMessageDeliveryState
import com.letta.mobile.data.messaging.AgentMessageDirection
import com.letta.mobile.data.messaging.AgentMessageProvenance

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

/** Short, human-scannable fallback for an unresolved agent id. */
internal fun agentDisplayLabel(agentId: String, resolvedName: String?): String {
    val trimmed = resolvedName?.trim()
    if (!trimmed.isNullOrEmpty()) return trimmed
    if (agentId.isBlank()) return "Unknown agent"
    val bare = agentId.removePrefix("agent-")
    return "Agent " + bare.take(8)
}

/**
 * The default compact transcript label, e.g.
 * "Meridian → PM-letta-mobile · Agent message" — matches the
 * letta-mobile-slqfp spec format exactly (proper arrow glyph, em middle dot).
 */
internal fun AgentMessageProvenance.compactLabel(
    resolveName: (agentId: String) -> String?,
): String {
    val from = agentDisplayLabel(fromAgentId, fromAgentName ?: resolveName(fromAgentId))
    val to = agentDisplayLabel(toAgentId, toAgentName ?: resolveName(toAgentId))
    return "$from → $to · Agent message"
}

internal fun AgentMessageDeliveryState.label(): String = when (this) {
    AgentMessageDeliveryState.PENDING -> "Pending"
    AgentMessageDeliveryState.SENT -> "Sent"
    AgentMessageDeliveryState.RECEIVER_CONFIRMED -> "Delivered"
    AgentMessageDeliveryState.FAILED -> "Failed"
}

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
    resolveName: (agentId: String) -> String? = { null },
    expanded: Boolean,
    onToggleExpand: () -> Unit,
    onAgentClick: (agentId: String) -> Unit = {},
) {
    val isInbound = provenance.direction == AgentMessageDirection.INBOUND
    val isFailed = provenance.deliveryState == AgentMessageDeliveryState.FAILED
    // Semantic identity tint — restrained (tertiary is the M3 "accent
    // distinct from primary" role), not a loud banner color. Failures use
    // the standard destructive (error) role regardless of direction, since a
    // failed send/receipt needs to be noticed.
    val tint = when {
        isFailed -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.tertiary
    }
    val fromLabel = agentDisplayLabel(provenance.fromAgentId, provenance.fromAgentName ?: resolveName(provenance.fromAgentId))
    val toLabel = agentDisplayLabel(provenance.toAgentId, provenance.toAgentName ?: resolveName(provenance.toAgentId))
    val a11yDescription = "Agent message, ${provenance.direction.name.lowercase()}, " +
        "from $fromLabel to $toLabel, ${provenance.deliveryState.label().lowercase()}" +
        (provenance.failureReason?.let { ": $it" } ?: "")

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .semantics { contentDescription = a11yDescription },
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onToggleExpand)
                .padding(bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(
                imageVector = when {
                    isFailed -> Icons.Outlined.ErrorOutline
                    isInbound -> Icons.Outlined.CallReceived
                    else -> Icons.Outlined.CallMade
                },
                contentDescription = null,
                modifier = Modifier.padding(0.dp),
                tint = tint.copy(alpha = 0.85f),
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f, fill = false),
            ) {
                Text(
                    text = fromLabel,
                    style = MaterialTheme.typography.labelMedium,
                    color = tint,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .clickable(role = Role.Button) { onAgentClick(provenance.fromAgentId) },
                )
                Text(
                    text = "→",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = toLabel,
                    style = MaterialTheme.typography.labelMedium,
                    color = tint,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .clickable(role = Role.Button) { onAgentClick(provenance.toAgentId) },
                )
                Text(
                    text = " · Agent message",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (provenance.deliveryState != AgentMessageDeliveryState.RECEIVER_CONFIRMED) {
                Text(
                    text = provenance.deliveryState.label(),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (isFailed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(
                imageVector = if (expanded) Icons.Outlined.KeyboardArrowUp else Icons.Outlined.KeyboardArrowDown,
                contentDescription = if (expanded) "Collapse agent message details" else "Expand agent message details",
                modifier = Modifier.padding(0.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (expanded) {
            AgentMessageProvenanceMetadata(provenance, tint)
        }
    }
}

@Composable
internal fun AgentMessageProvenanceMetadata(provenance: AgentMessageProvenance, tint: androidx.compose.ui.graphics.Color) {
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
                MetadataRow("Delivery state", provenance.deliveryState.label())
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
