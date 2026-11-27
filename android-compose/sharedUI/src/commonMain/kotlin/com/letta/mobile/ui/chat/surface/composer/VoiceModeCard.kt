package com.letta.mobile.ui.chat.surface.composer

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.withInfiniteAnimationFrameNanos
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import com.letta.mobile.data.voice.session.VoiceModeState
import com.letta.mobile.sharedui.resources.Res
import com.letta.mobile.sharedui.resources.voice_card_agent
import com.letta.mobile.sharedui.resources.voice_card_label
import com.letta.mobile.sharedui.resources.voice_card_status_failed
import com.letta.mobile.sharedui.resources.voice_card_status_hearing
import com.letta.mobile.sharedui.resources.voice_card_status_listening
import com.letta.mobile.sharedui.resources.voice_card_status_speaking
import com.letta.mobile.sharedui.resources.voice_card_status_thinking
import com.letta.mobile.sharedui.resources.voice_card_you
import com.letta.mobile.ui.chat.voice.VoiceCardConversation
import com.letta.mobile.ui.chat.voice.VoiceCardFeed
import com.letta.mobile.ui.chat.voice.VoiceCardSpeaker
import com.letta.mobile.ui.chat.voice.VoiceCardTiming
import com.letta.mobile.ui.chat.voice.VoiceCardTurn
import com.letta.mobile.ui.chat.voice.VoiceModeHandle
import com.letta.mobile.ui.chat.voice.VoiceModeTags
import com.letta.mobile.ui.components.foundation.LettaPanel
import com.letta.mobile.ui.theme.LettaDimens
import com.letta.mobile.ui.theme.LocalReducedMotion
import com.letta.mobile.ui.theme.customColors
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/** Whose turn [state] is. */
internal fun voiceSpeaker(state: VoiceModeState): VoiceCardSpeaker = when (state) {
    VoiceModeState.WaitingForReply, VoiceModeState.Speaking -> VoiceCardSpeaker.Agent
    else -> VoiceCardSpeaker.You
}

/**
 * The voice card docked above the prompt while voice mode runs (it draws nothing otherwise), the
 * composer's voice controls and glow while it shows. A header line: whose turn it is (the person, or
 * the agent by name), how long they have had it and what voice mode is doing, then round controls
 * (the reply's stop while it is spoken, the microphone and speaker mutes, the speaking speed), fold
 * and a red End. Under it, unless folded, two to four lines of large text: the person's words as they
 * are heard, then the reply with its spoken words lit and the rest dimmed, or a failed utterance with
 * Retry or Set up. It extends from the prompt bar (VoiceCardDock places it behind the bar, its
 * bottom tucked under), and the glow at that seam ([seamGlow]) lights its lower interior.
 *
 * Built from the design system: the panel container, the shared fold controls and menu, the theme's
 * type scale, control sizes and voice tints (customColors.voiceYouColor and voiceAgentColor). Under
 * reduced motion it holds still: no fold animation, a still thinking mark and glow, and the reply lit
 * a piece at a time.
 *
 * It shows the live state whenever voice mode runs, so every change of turn shows at once. Once voice
 * mode stops it draws nothing, unless given [leaving], the last running state to keep showing while it
 * folds away (VoiceCardDock passes it during the exit motion).
 */
@Composable
internal fun VoiceModeCard(
    handle: VoiceModeHandle,
    conversation: VoiceCardConversation,
    seamGlow: () -> Color,
    modifier: Modifier = Modifier,
    leaving: VoiceModeState? = null,
) {
    LocalRecompositionProbe.current?.invoke(PROBE_CARD)
    val live by handle.state.collectAsState()
    // The live state whenever voice mode runs; [leaving] only once it has stopped, while the card folds away.
    val state = live.takeIf { it.active } ?: leaving ?: return
    val reducedMotion = LocalReducedMotion.current
    val speaker = voiceSpeaker(state)
    val now = rememberCardClock(handle.cardFeed)
    val turnStart = remember(speaker) { handle.cardFeed.now() }
    val elapsedMs = remember(turnStart, now) { { now.value - turnStart } }
    val label = stringResource(Res.string.voice_card_label)
    LettaPanel(
        modifier = modifier
            .fillMaxWidth()
            .testTag(VoiceModeTags.CARD)
            .semantics {
                paneTitle = label
                isTraversalGroup = true
            },
        border = true,
    ) {
        Box {
            // The glow rising from the seam with the prompt bar, under the words.
            Spacer(Modifier.matchParentSize().drawBehind { drawSeamGlow(seamGlow()) })
            Column(
                modifier = Modifier
                    .then(if (reducedMotion) Modifier else Modifier.animateContentSize())
                    // The bottom carries the edge tucked under the prompt bar (VoiceCardDock).
                    .padding(
                        start = LettaDimens.Space.md,
                        end = LettaDimens.Space.md,
                        top = LettaDimens.Space.sm,
                        bottom = LettaDimens.Space.sm + LettaDimens.Radius.lg,
                    ),
                verticalArrangement = Arrangement.spacedBy(LettaDimens.Space.sm),
            ) {
                val turn = VoiceCardTurn(
                    speaker = speaker,
                    name = speakerName(speaker, conversation.name),
                    tint = speakerTint(speaker),
                    status = stringResource(statusOf(state)),
                    thinking = state == VoiceModeState.WaitingForReply,
                    speaking = state == VoiceModeState.Speaking,
                    elapsedMs = elapsedMs,
                )
                BoxWithConstraints {
                    VoiceCardHeader(handle, turn, oneLine = maxWidth >= LettaDimens.Pane.voiceCardOneLine)
                }
                if (!handle.cardCollapsed) {
                    VoiceCardBody(handle, state, conversation.reply, remember(now, turnStart) { VoiceCardTiming(now, turnStart) })
                }
            }
        }
    }
}

/** The seam's glow inside the card: [tint] rising from the bottom edge and fading out by mid-height. */
private fun DrawScope.drawSeamGlow(tint: Color) {
    if (tint.alpha <= HIDDEN_ALPHA) return
    val top = size.height * (1f - GLOW_REACH)
    drawRect(
        Brush.verticalGradient(0f to Color.Transparent, 1f to tint.copy(alpha = tint.alpha * GLOW_ALPHA), startY = top, endY = size.height),
        topLeft = Offset(0f, top),
        size = Size(size.width, size.height - top),
    )
}

/** What voice mode is doing in [state], as the header says it. */
private fun statusOf(state: VoiceModeState): StringResource = when (state) {
    VoiceModeState.Hearing -> Res.string.voice_card_status_hearing
    VoiceModeState.WaitingForReply -> Res.string.voice_card_status_thinking
    VoiceModeState.Speaking -> Res.string.voice_card_status_speaking
    is VoiceModeState.TurnFailed -> Res.string.voice_card_status_failed
    else -> Res.string.voice_card_status_listening
}

/** "You", or the agent's [agentName] ("Agent" when it has none). */
@Composable
private fun speakerName(speaker: VoiceCardSpeaker, agentName: String): String = when (speaker) {
    VoiceCardSpeaker.You -> stringResource(Res.string.voice_card_you)
    VoiceCardSpeaker.Agent -> agentName.ifBlank { stringResource(Res.string.voice_card_agent) }
}

/** The speaker's voice tint: the theme's cool tint for the person, its warm tint for the agent. */
@Composable
internal fun speakerTint(speaker: VoiceCardSpeaker): Color = when (speaker) {
    VoiceCardSpeaker.You -> MaterialTheme.customColors.voiceYouColor
    VoiceCardSpeaker.Agent -> MaterialTheme.customColors.voiceAgentColor
}

/**
 * The feed's clock, read every frame while the card shows: the turn timer and the reply's progress
 * derive from it, and recompose only when what they show changes.
 */
@Composable
private fun rememberCardClock(feed: VoiceCardFeed): State<Long> {
    val now = remember(feed) { mutableLongStateOf(feed.now()) }
    LaunchedEffect(feed) {
        // The card's clock, not work a test should wait on.
        while (true) withInfiniteAnimationFrameNanos { now.longValue = feed.now() }
    }
    return now
}

/** [elapsedMs] as minutes and seconds: "0:06", "1:35", "12:04". */
internal fun formatElapsed(elapsedMs: Long): String {
    val seconds = (elapsedMs.coerceAtLeast(0L) / MILLIS_PER_SECOND)
    val minutes = seconds / SECONDS_PER_MINUTE
    val rest = seconds % SECONDS_PER_MINUTE
    return "$minutes:${rest.toString().padStart(2, '0')}"
}

/** The elapsed text that recomposes only when its second changes. */
@Composable
internal fun rememberElapsedText(elapsedMs: () -> Long): State<String> =
    remember(elapsedMs) { derivedStateOf { formatElapsed(elapsedMs()) } }

private const val MILLIS_PER_SECOND = 1_000L
private const val GLOW_REACH = 0.7f
private const val GLOW_ALPHA = 0.32f
private const val HIDDEN_ALPHA = 0.001f
private const val SECONDS_PER_MINUTE = 60L
