package com.letta.mobile.feature.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.Box

/**
 * Small chip surfaced in the chat top bar so we can verify at a glance
 * which transport the session is on (REST vs WS) and, when on WS,
 * whether A2UI was negotiated.
 *
 * Color semantics:
 *   - REST: neutral surface variant (informational, not a failure).
 *   - WS idle / WS connecting: tertiary tint (in-progress).
 *   - WS connected without A2UI: primary tint (WS up, but A2UI inactive).
 *   - WS connected with A2UI: tertiary container (good path).
 *   - WS disconnected: error tint (the path went away mid-session).
 *
 * A2UI rendering only fires on the WS path, so this chip is the
 * fastest end-to-end signal for the renderer dev loop. Tracking bead:
 * letta-mobile-cbjh.
 */
@Composable
internal fun ChatTransportChip(
    transport: ChatTransport,
    a2uiFrameCount: Int,
    modifier: Modifier = Modifier,
) {
    val (label, container, content) = chipStyle(transport, a2uiFrameCount)
    val description = when (transport) {
        is ChatTransport.WsDisconnected -> {
            val closeKind = if (transport.code == 1000) "clean close" else "abnormal close"
            "Chat transport: $label, $closeKind"
        }
        else -> "Chat transport: $label"
    }
    Box(
        modifier = modifier
            .semantics { contentDescription = description }
            .clip(RoundedCornerShape(8.dp))
            .background(container)
            .padding(PaddingValues(horizontal = 6.dp, vertical = 2.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(content),
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium),
                color = content,
            )
        }
    }
}

private data class ChipStyle(val label: String, val container: Color, val content: Color)

@Composable
private fun chipStyle(transport: ChatTransport, a2uiFrameCount: Int): ChipStyle {
    val cs = MaterialTheme.colorScheme
    return when (transport) {
        is ChatTransport.Rest ->
            ChipStyle("REST", cs.surfaceContainerHigh, cs.onSurfaceVariant)
        is ChatTransport.WsIdle ->
            ChipStyle("WS idle", cs.tertiaryContainer, cs.onTertiaryContainer)
        is ChatTransport.WsConnecting ->
            ChipStyle("WS…", cs.tertiaryContainer, cs.onTertiaryContainer)
        is ChatTransport.WsConnected -> {
            val frameSuffix = if (a2uiFrameCount > 0) " · $a2uiFrameCount" else ""
            val label = when {
                transport.a2uiEnabled && transport.catalog != null ->
                    "WS · A2UI ${transport.catalog}$frameSuffix"
                transport.a2uiEnabled -> "WS · A2UI$frameSuffix"
                else -> "WS"
            }
            val container = if (transport.a2uiEnabled) cs.tertiaryContainer else cs.primaryContainer
            val content = if (transport.a2uiEnabled) cs.onTertiaryContainer else cs.onPrimaryContainer
            ChipStyle(label, container, content)
        }
        is ChatTransport.WsDisconnected -> {
            // letta-mobile-ns5l: 1000 (clean close) tints muted so the
            // chip doesn't shout error for expected lifecycle events
            // (idle close, app background). Abnormal codes (1006 wire
            // drop, 1011 server error, 4xxx app-defined) keep the red
            // error tint so a real failure stays visually loud.
            val isCleanClose = transport.code == 1000
            val container = if (isCleanClose) cs.surfaceContainerHighest else cs.errorContainer
            val content = if (isCleanClose) cs.onSurfaceVariant else cs.onErrorContainer
            ChipStyle("WS off (${transport.code})", container, content)
        }
    }
}
