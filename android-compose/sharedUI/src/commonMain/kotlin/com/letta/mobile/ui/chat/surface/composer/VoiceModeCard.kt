package com.letta.mobile.ui.chat.surface.composer

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.withInfiniteAnimationFrameNanos
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.ui.graphics.Color
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
import com.letta.mobile.ui.chat.voice.VoiceCardTurn
import com.letta.mobile.ui.chat.voice.VoiceModeHandle
import com.letta.mobile.ui.chat.voice.VoiceModeTags
import com.letta.mobile.ui.components.foundation.LettaPanel
import com.letta.mobile.ui.theme.LettaDimens
import com.letta.mobile.ui.theme.LocalReducedMotion
import com.letta.mobile.ui.theme.customColors
import com.letta.mobile.ui.theme.tokens.LettaShapes
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
 * Retry or Set up. Its edge glows in the speaker's colour, with the voice's level.
 *
 * Built from the design system: the panel container, the shared fold controls and menu, the theme's
 * type scale, control sizes and voice tints (customColors.voiceYouColor and voiceAgentColor). Under
 * reduced motion it holds still: no fold animation, a still thinking mark and glow, and the reply lit
 * a piece at a time.
 */
@Composable
internal fun VoiceModeCard(handle: VoiceModeHandle, conversation: VoiceCardConversation, modifier: Modifier = Modifier) {
    val state by handle.state.collectAsState()
    if (!state.active) return
    val reducedMotion = LocalReducedMotion.current
    val speaker = voiceSpeaker(state)
    val now = rememberCardClock(handle.cardFeed)
    val turnStart = remember(speaker) { handle.cardFeed.now() }
    val elapsedMs = remember(turnStart, now) { { now.value - turnStart } }
    val label = stringResource(Res.string.voice_card_label)
    LettaPanel(
        modifier = modifier
            .fillMaxWidth()
            .voiceCardOutline(LettaShapes.panel, handle, state)
            .testTag(VoiceModeTags.CARD)
            .semantics {
                paneTitle = label
                isTraversalGroup = true
            },
    ) {
        Column(
            modifier = Modifier
                .then(if (reducedMotion) Modifier else Modifier.animateContentSize())
                .padding(horizontal = LettaDimens.Space.lg, vertical = LettaDimens.Space.md),
            verticalArrangement = Arrangement.spacedBy(LettaDimens.Space.md),
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
            if (!handle.cardCollapsed) VoiceCardBody(handle, state, conversation.reply, now)
        }
    }
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
private const val SECONDS_PER_MINUTE = 60L
