package com.letta.mobile.ui.chat.surface.composer

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Constraints
import com.letta.mobile.data.voice.session.VoiceModeState
import com.letta.mobile.ui.chat.render.ChatUiState
import com.letta.mobile.ui.chat.voice.LocalVoiceMode
import com.letta.mobile.ui.chat.voice.VoiceCardConversation
import com.letta.mobile.ui.chat.voice.VoiceModeHandle
import com.letta.mobile.ui.components.motion.LettaPanelMotion
import com.letta.mobile.ui.components.motion.rememberPanelSettle
import com.letta.mobile.ui.theme.ChatMotionTokens
import com.letta.mobile.ui.theme.LettaDimens
import com.letta.mobile.ui.theme.LocalReducedMotion

/**
 * Counts compositions by name where a test asks to ([recompositions while voice mode runs are measured
 * by VoiceRecompositionTest]); null, and free, otherwise.
 */
internal val LocalRecompositionProbe = staticCompositionLocalOf<((String) -> Unit)?> { null }

internal const val PROBE_COMPOSER = "composer"
internal const val PROBE_DOCK = "dock"
internal const val PROBE_CARD = "card"
internal const val PROBE_SPOKEN = "card-spoken"
internal const val PROBE_TIMER = "card-timer"
internal const val PROBE_BODY = "card-body"

/** Hears the reply's lit edge (in characters) each time it is drawn, where a test asks to; null otherwise. */
internal val LocalSpokenEdgeProbe = staticCompositionLocalOf<((Int) -> Unit)?> { null }

/**
 * A prompt bar ([bar]) with the voice card extending from it while voice mode runs: the card sits
 * behind the bar, a little narrower ([LettaDimens.Space.lg] in from each side) and anchored to its top
 * edge, its bottom tucked [LettaDimens.Radius.lg] (the bar's corner) under it, so the two read as one
 * object. One glow, in the speaker's colour and with the voice's level, rises from the seam: it lights
 * the card's lower interior and the bar's top rim. While voice mode is off only the bar draws; the
 * layout is the same either way, so the bar (and the draft in it) never remounts when voice mode starts.
 *
 * The card extends up out of the bar when voice mode starts and folds back into it when it ends
 * ([LettaPanelMotion], anchored at the card's bottom edge), and the seam glow flares as it lands and
 * settles ([rememberPanelSettle]); under reduced motion the card only fades in and out.
 */
@Composable
internal fun VoiceCardDock(uiState: ChatUiState, modifier: Modifier = Modifier, bar: @Composable () -> Unit) {
    LocalRecompositionProbe.current?.invoke(PROBE_DOCK)
    val handle = LocalVoiceMode.current
    val state = handle?.state?.collectAsState()?.value
    val running = handle != null && state?.active == true
    val reducedMotion = LocalReducedMotion.current
    val level = if (running) handle!!.glowLevel(state!!).takeIf { !reducedMotion } else null
    val glow = rememberSeamGlow(state?.takeIf { running }, level, rememberPanelSettle(running))
    val reply = remember(uiState.messages) { latestReplyText(uiState) }
    // Where the bar starts in the dock: written as it is placed, read by the rim when it draws.
    val seam = remember { IntArray(1) }
    Layout(
        modifier = modifier.drawWithContent {
            drawContent()
            if (running) drawSeamRim(glow(), seam[0].toFloat())
        },
        content = {
            Box {
                if (handle != null) {
                    ExtendingCard(handle, visible = running) { shown ->
                        VoiceModeCard(
                            handle = handle,
                            conversation = VoiceCardConversation(uiState.agentName, reply),
                            seamGlow = glow,
                            leaving = shown,
                            modifier = Modifier.semantics {
                                shown?.let { voiceOutlineRole = voiceGlowRole(it) }
                                voiceOutlineAnimated = level != null
                            },
                        )
                    }
                }
            }
            bar()
        },
    ) { measurables, constraints ->
        val barPlaceable = measurables[1].measure(constraints)
        val inset = LettaDimens.Space.lg.roundToPx()
        val tuck = LettaDimens.Radius.lg.roundToPx()
        val cardWidth = (barPlaceable.width - 2 * inset).coerceAtLeast(0)
        val cardPlaceable = measurables[0].measure(Constraints(minWidth = cardWidth, maxWidth = cardWidth))
        val overlap = if (cardPlaceable.height > 0) tuck.coerceAtMost(cardPlaceable.height) else 0
        val barTop = cardPlaceable.height - overlap
        layout(barPlaceable.width, barTop + barPlaceable.height) {
            // The card first: the bar is placed after it, so it draws over the card's tucked edge.
            cardPlaceable.place(inset, 0)
            barPlaceable.place(0, barTop)
            seam[0] = barTop
        }
    }
}

/**
 * [card] extending up out of the bar while [visible] and folding back into it once not, anchored at
 * its bottom edge ([LettaPanelMotion]; a short fade under reduced motion), given the state to show
 * ([rememberShownState]) so it keeps its face while it leaves.
 */
@Composable
private fun ExtendingCard(handle: VoiceModeHandle, visible: Boolean, card: @Composable (VoiceModeState?) -> Unit) {
    val reduced = LocalReducedMotion.current
    AnimatedVisibility(
        visible = visible,
        enter = LettaPanelMotion.enter(reduced, Alignment.Bottom),
        exit = LettaPanelMotion.exit(reduced, Alignment.Bottom),
        label = "voiceCard",
    ) {
        card(rememberShownState(handle))
    }
}

/**
 * The running state the card shows: the live one while voice mode runs and, once it has stopped, the
 * last of them, so the card keeps its face while it folds away. Null before voice mode first runs.
 */
@Composable
internal fun rememberShownState(handle: VoiceModeHandle): VoiceModeState? {
    val live by handle.state.collectAsState()
    val last = remember(handle) { arrayOfNulls<VoiceModeState>(1) }
    if (live.active) last[0] = live
    return last[0]
}

/**
 * The seam's glow for running voice mode [state] (null while it is not running): the speaker's colour
 * (gliding between roles, snapping under reduced motion) at the voice's [level] (still at a resting
 * brightness when there is none: the reply awaited, reduced motion), brightened by the card's arrival
 * [settle] (1 as it lands, 0 once settled), read at draw time.
 */
@Composable
private fun rememberSeamGlow(state: VoiceModeState?, level: (() -> Float)?, settle: () -> Float): () -> Color {
    val reducedMotion = LocalReducedMotion.current
    val color = animateColorAsState(
        targetValue = state?.let { outlineColor(voiceGlowRole(it)) } ?: Color.Transparent,
        animationSpec = if (reducedMotion) snap() else tween(ChatMotionTokens.AmbientGlow.GLIDE_MILLIS),
        label = "voiceSeamColor",
    )
    val brightness = rememberOutlineBrightness(level)
    return remember(color, brightness, settle) {
        {
            val lit = brightness.floatValue * (1f + LettaPanelMotion.SETTLE_GLOW_GAIN * settle())
            color.value.copy(alpha = (color.value.alpha * lit).coerceAtMost(1f))
        }
    }
}

/**
 * The bar's top rim lit from the seam, across the card's span (where the bar's top edge is straight,
 * clear of its rounded corners): a hairline in [tint], fading toward both ends, and a short wash below it.
 */
private fun DrawScope.drawSeamRim(tint: Color, y: Float) {
    if (tint.alpha <= HIDDEN_ALPHA) return
    val inset = LettaDimens.Space.lg.toPx()
    val span = (size.width - 2 * inset).coerceAtLeast(0f)
    val rim = Brush.horizontalGradient(
        0f to Color.Transparent,
        RIM_SHOULDER to tint,
        1f - RIM_SHOULDER to tint,
        1f to Color.Transparent,
        startX = inset,
        endX = inset + span,
    )
    val line = LettaDimens.Space.hair.toPx()
    drawRect(rim, topLeft = Offset(inset, y), size = Size(span, line))
    val wash = LettaDimens.Space.md.toPx()
    drawRect(
        Brush.verticalGradient(0f to tint.copy(alpha = tint.alpha * WASH_ALPHA), 1f to Color.Transparent, startY = y, endY = y + wash),
        topLeft = Offset(inset, y),
        size = Size(span, wash),
    )
}

private const val RIM_SHOULDER = 0.18f
private const val WASH_ALPHA = 0.35f
private const val HIDDEN_ALPHA = 0.001f
