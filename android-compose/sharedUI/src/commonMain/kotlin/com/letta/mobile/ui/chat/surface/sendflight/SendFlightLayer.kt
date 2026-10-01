package com.letta.mobile.ui.chat.surface.sendflight

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.lerp
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
import com.letta.mobile.sharedui.resources.Res
import com.letta.mobile.sharedui.resources.rows_role_you
import com.letta.mobile.ui.theme.ChatBubbleShapes
import com.letta.mobile.ui.theme.ChatMotionTokens
import com.letta.mobile.ui.theme.ChatRowAlpha
import com.letta.mobile.ui.theme.ChatRowDimens
import com.letta.mobile.ui.theme.ChatRowSpacing
import com.letta.mobile.ui.theme.ChatRowType
import org.jetbrains.compose.resources.stringResource
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
 * The flying prompt, placed on the eased path between the field and the row. It morphs from the
 * field's text at take-off into the new prompt bubble on landing, drawn as UserPromptRow draws
 * it: the primaryContainer fill on the user bubble shape, the bubble's padding, the "You" label
 * over the text, and the text in the bubble's style and colour. Landed, it is the bubble.
 */
@Composable
private fun SendFlightGhost(flight: SendFlight, modifier: Modifier) {
    val progress = flight.progress
    val typography = MaterialTheme.typography
    val scheme = MaterialTheme.colorScheme
    val fillAlpha = (progress / ChatMotionTokens.SendFlight.FILL_IN_FRACTION).coerceIn(0f, 1f)
    Box(modifier) {
        Box(
            Modifier
                .placedOnPath(flight)
                .graphicsLayer { alpha = flight.ghostAlpha }
                // Hidden from accessibility: the real row already announces the prompt.
                .clearAndSetSemantics { testTag = SendFlightTestTags.GHOST }
                .clip(ChatBubbleShapes.user())
                .background(scheme.primaryContainer.copy(alpha = scheme.primaryContainer.alpha * fillAlpha)),
        ) {
            Column(
                modifier = Modifier.padding(
                    horizontal = ChatRowSpacing.bubblePaddingHorizontal * progress,
                    vertical = ChatRowSpacing.bubblePaddingVertical * progress,
                ),
            ) {
                // The label grows in above the text: its height opens with the trip, so at
                // take-off the text sits where the field drew it.
                Text(
                    text = stringResource(Res.string.rows_role_you),
                    style = ChatRowType.roleLabel,
                    color = scheme.onPrimaryContainer.copy(alpha = ChatRowAlpha.userRoleLabel * progress),
                    maxLines = 1,
                    modifier = Modifier.growIn(progress),
                )
                Spacer(Modifier.height(ChatRowSpacing.messagePart * progress))
                Text(
                    text = flight.text,
                    style = lerp(typography.bodyLarge, typography.bodyMedium, progress),
                    color = lerp(scheme.onSurface, scheme.onPrimaryContainer, progress),
                    maxLines = ChatRowDimens.promptCollapsedMaxLines,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** Measures at full size but reports [fraction] of the height, so the content below rises with it. */
private fun Modifier.growIn(fraction: Float): Modifier = layout { measurable, constraints ->
    val placeable = measurable.measure(constraints)
    layout(placeable.width, (placeable.height * fraction).roundToInt()) { placeable.place(0, 0) }
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
