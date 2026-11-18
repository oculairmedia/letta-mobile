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
import com.letta.mobile.sharedui.resources.voice_card_you
import com.letta.mobile.ui.chat.voice.VoiceCardFeed
import com.letta.mobile.ui.chat.voice.VoiceModeHandle
import com.letta.mobile.ui.chat.voice.VoiceSpeaker
import com.letta.mobile.ui.chat.voice.VoiceCardTurn
import com.letta.mobile.ui.chat.voice.VoiceCardConversation
import com.letta.mobile.ui.chat.voice.VoiceModeTags
import com.letta.mobile.ui.components.foundation.LettaPanel
import com.letta.mobile.ui.theme.LettaDimens
import com.letta.mobile.ui.theme.LocalReducedMotion
import com.letta.mobile.ui.theme.customColors
import org.jetbrains.compose.resources.stringResource

/** Whose turn [state] is. */
internal fun voiceSpeaker(state: VoiceModeState): VoiceSpeaker = when (state) {
    VoiceModeState.WaitingForReply, VoiceModeState.Speaking -> VoiceSpeaker.Agent
    else -> VoiceSpeaker.You
}

/**
 * The voice card docked above the prompt while voice mode runs (it draws nothing otherwise): a header
 * line with whose turn it is (the person, or the agent by name), how long they have had it, and the
 * controls (microphone and speaker mutes and the speaking speed where the controller offers them,
 * fold, End); under it, unless folded, the person's words as they are heard, the agent thinking, the
 * reply with its spoken words lit and the rest dimmed, or a failed utterance with Retry or Set up.
 *
 * Built from the design system: the panel container, the shared icon buttons, chip, menu and fold
 * controls, the theme's type scale and its voice tints (customColors.voiceYouColor and
 * voiceAgentColor). Under reduced motion it holds still: no fold animation, a still thinking mark,
 * and the reply lit a piece at a time.
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
            .testTag(VoiceModeTags.CARD)
            .semantics {
                paneTitle = label
                isTraversalGroup = true
            },
    ) {
        Column(
            modifier = Modifier
                .then(if (reducedMotion) Modifier else Modifier.animateContentSize())
                .padding(horizontal = LettaDimens.Space.md, vertical = LettaDimens.Space.sm),
            verticalArrangement = Arrangement.spacedBy(LettaDimens.Space.sm),
        ) {
            val turn = VoiceCardTurn(
                speaker = speaker,
                name = speakerName(speaker, conversation.name),
                tint = speakerTint(speaker),
                thinking = state == VoiceModeState.WaitingForReply,
                elapsedMs = elapsedMs,
            )
            BoxWithConstraints {
                VoiceCardHeader(handle, turn, oneLine = maxWidth >= LettaDimens.Pane.voiceCardOneLine)
            }
            if (!handle.cardCollapsed) VoiceCardBody(handle, state, conversation.reply, now)
        }
    }
}

/** "You", or the agent's [agentName] ("Agent" when it has none). */
@Composable
private fun speakerName(speaker: VoiceSpeaker, agentName: String): String = when (speaker) {
    VoiceSpeaker.You -> stringResource(Res.string.voice_card_you)
    VoiceSpeaker.Agent -> agentName.ifBlank { stringResource(Res.string.voice_card_agent) }
}

/** The speaker's voice tint: the theme's cool tint for the person, its warm tint for the agent. */
@Composable
internal fun speakerTint(speaker: VoiceSpeaker): Color = when (speaker) {
    VoiceSpeaker.You -> MaterialTheme.customColors.voiceYouColor
    VoiceSpeaker.Agent -> MaterialTheme.customColors.voiceAgentColor
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
