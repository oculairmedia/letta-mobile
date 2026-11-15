package com.letta.mobile.ui.canvas

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.IntOffset
import com.letta.mobile.data.canvas.CanvasPresence
import kotlin.math.roundToInt
import com.letta.mobile.ui.theme.LettaDimens
import com.letta.mobile.ui.components.foundation.LettaTonalPill
import com.letta.mobile.ui.theme.pill.LettaPillColors

/**
 * Parses hex color strings (#RRGGBB or #RRGGBBAA) into Compose [Color].
 */
fun parsePresenceColor(hex: String, fallback: Color = PresenceFallbackColor): Color {
    val clean = hex.trim().removePrefix("#")
    return try {
        when (clean.length) {
            6 -> {
                val rgb = clean.toLong(16)
                Color(0xFF000000 or rgb)
            }
            8 -> {
                val rgba = clean.toLong(16)
                val a = (rgba and 0xFF) shl 24
                val rgb = (rgba ushr 8) and 0xFFFFFF
                Color(a or rgb)
            }
            else -> fallback
        }
    } catch (_: Exception) {
        fallback
    }
}

/**
 * Overlay layer displaying collaborative remote peer cursors and presence badges.
 *
 * Designed to sit above the DrawBox canvas without intercepting pointer touches.
 */
@Composable
fun PresenceLayer(
    presences: List<CanvasPresence>,
    currentPeerId: String? = null,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .testTag("presence_layer")
    ) {
        for (presence in presences) {
            if (presence.peerId == currentPeerId || !presence.isActive) continue

            val color = remember(presence.colorHex) { parsePresenceColor(presence.colorHex) }
            val x = presence.cursorX.roundToInt()
            val y = presence.cursorY.roundToInt()

            Box(
                modifier = Modifier
                    .offset { IntOffset(x, y) }
                    .testTag("presence_cursor_${presence.peerId}")
            ) {
                // Draw cursor pointer
                Canvas(modifier = Modifier.size(LettaDimens.Control.icon)) {
                    val path = Path().apply {
                        moveTo(0f, 0f)
                        lineTo(size.width, size.height * 0.6f)
                        lineTo(size.width * 0.4f, size.height * 0.6f)
                        lineTo(0f, size.height)
                        close()
                    }
                    drawPath(path, color)
                }

                // Name badge pill, in the peer's own colour.
                LettaTonalPill(
                    text = presence.displayName,
                    colors = LettaPillColors(container = color, content = Color.White),
                    modifier = Modifier.offset { IntOffset(14, 14) },
                )
            }
        }
    }
}
