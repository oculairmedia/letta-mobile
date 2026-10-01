package com.letta.mobile.ui.chat.surface.sendflight

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.lerp
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.text.lerp
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import com.letta.mobile.ui.theme.ChatMotionTokens
import com.letta.mobile.ui.theme.ChatRowDimens
import com.letta.mobile.ui.theme.LettaDimens
import kotlin.math.roundToInt

/** letta-mobile-cc25e: test tags of the send flight. */
object SendFlightTestTags {
    const val GHOST = "chat-send-flight-ghost"
}

@Composable
fun rememberSendFlightState(): SendFlightState = remember { SendFlightState() }

/**
 * letta-mobile-cc25e: the chat page's send-flight layer. It provides [state] to the composer
 * and the timeline rows inside [content] and draws the flying prompt over all of them, so the
 * ghost can travel from the dock or the full composer into the reply card or the timeline.
 *
 * Min constraints pass through to [content], so the layer sizes exactly as [content] would.
 */
@Composable
fun SendFlightLayer(state: SendFlightState, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalSendFlight provides state) {
        Box(
            modifier.onGloballyPositioned { state.layerOrigin = it.positionInRoot() },
            propagateMinConstraints = true,
        ) {
            content()
            state.flight?.let { flight ->
                LaunchedEffect(flight) {
                    flight.fly()
                    state.finish(flight)
                }
                SendFlightGhost(flight, Modifier.matchParentSize())
            }
        }
    }
}

/**
 * The flying prompt: the sent text on the row's card, placed on the eased path between the
 * field and the row. It reads as the field's text at take-off and as the row's card on landing.
 */
@Composable
private fun SendFlightGhost(flight: SendFlight, modifier: Modifier) {
    val progress = flight.progress
    val typography = MaterialTheme.typography
    val fill = MaterialTheme.colorScheme.surfaceContainerLow
    val fillAlpha = (progress / ChatMotionTokens.SendFlight.FILL_IN_FRACTION).coerceIn(0f, 1f)
    val shape = RoundedCornerShape(LettaDimens.Radius.md)
    Box(modifier) {
        Box(
            Modifier
                .placedOnPath(flight)
                .graphicsLayer { alpha = flight.ghostAlpha }
                // Hidden from accessibility: the real row already announces the prompt.
                .clearAndSetSemantics { testTag = SendFlightTestTags.GHOST }
                .background(fill.copy(alpha = fillAlpha), shape),
        ) {
            Text(
                text = flight.text,
                style = lerp(typography.bodyLarge, typography.bodyMedium, progress),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = ChatRowDimens.promptCollapsedMaxLines,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(
                    start = LettaDimens.Space.lg * progress,
                    end = LettaDimens.Space.sm * progress,
                    top = LettaDimens.Space.md * progress,
                    bottom = LettaDimens.Space.md * progress,
                ),
            )
        }
    }
}

/** The ghost's bounds right now: the source until a row claims it, then along the path. */
internal fun SendFlight.currentBounds(): Rect = lerp(source, target ?: source, progress)

private fun Modifier.placedOnPath(flight: SendFlight): Modifier = layout { measurable, constraints ->
    val bounds = flight.currentBounds()
    val width = bounds.width.roundToInt().coerceAtLeast(0)
    val height = bounds.height.roundToInt().coerceAtLeast(0)
    val placeable = measurable.measure(Constraints.fixed(width, height))
    layout(constraints.maxWidth, constraints.maxHeight) {
        placeable.place(bounds.left.roundToInt(), bounds.top.roundToInt())
    }
}
