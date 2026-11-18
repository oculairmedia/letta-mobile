package com.letta.mobile.ui.chat.surface.composer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.isSpecified
import com.letta.mobile.data.voice.VoiceUserAction
import com.letta.mobile.data.voice.session.VoiceModeState
import com.letta.mobile.sharedui.resources.Res
import com.letta.mobile.sharedui.resources.voice_action_retry
import com.letta.mobile.sharedui.resources.voice_action_setup
import com.letta.mobile.sharedui.resources.voice_status_hearing
import com.letta.mobile.sharedui.resources.voice_status_listening
import com.letta.mobile.sharedui.resources.voice_status_speaking
import com.letta.mobile.sharedui.resources.voice_status_speaking_press
import com.letta.mobile.sharedui.resources.voice_status_thinking
import com.letta.mobile.ui.chat.voice.VoiceModeHandle
import com.letta.mobile.ui.chat.voice.VoiceModeTags
import com.letta.mobile.ui.chat.voice.voiceErrorText
import com.letta.mobile.ui.components.foundation.LettaChip
import com.letta.mobile.ui.theme.LettaDimens
import com.letta.mobile.ui.theme.LocalReducedMotion
import com.letta.mobile.ui.theme.customColors
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import kotlin.math.roundToInt

/**
 * The card's body for [state]: the person's words as they are heard (the status until there are
 * any), "Thinking…" while the reply is awaited, the reply as it is spoken, or the failed utterance
 * with what helps. [now] is the card's clock.
 */
@Composable
internal fun VoiceCardBody(handle: VoiceModeHandle, state: VoiceModeState, reply: String?, now: State<Long>) {
    when (state) {
        is VoiceModeState.TurnFailed -> FailedTurn(handle, state)
        VoiceModeState.WaitingForReply -> StatusText(Res.string.voice_status_thinking)
        VoiceModeState.Speaking -> SpokenReply(handle, reply, now)
        VoiceModeState.Hearing -> HeardWords(handle, Res.string.voice_status_hearing)
        else -> HeardWords(handle, Res.string.voice_status_listening)
    }
}

/** The person's words so far, in full ink, the newest kept in view; [status] until there are any. */
@Composable
private fun HeardWords(handle: VoiceModeHandle, status: StringResource) {
    val words by handle.partialTranscript.collectAsState()
    val text = words?.value?.takeIf { it.isNotBlank() } ?: return StatusText(status)
    val ink = MaterialTheme.colorScheme.onSurface
    FollowingText(spokenAnnotated(text, text.length, ink, ink), follow = text.length)
}

/**
 * The reply as it is spoken: with the controller's spoken pieces, the words spoken so far in full
 * ink and the rest of the reply dimmed, the view following the spoken edge; without them, the reply's text in
 * full ink; with neither, the speaking status.
 */
@Composable
private fun SpokenReply(handle: VoiceModeHandle, reply: String?, now: State<Long>) {
    val segments by handle.cardFeed.spokenSegments.collectAsState()
    val interpolate = !LocalReducedMotion.current
    if (segments.isEmpty()) {
        val byVoice by handle.controller.bargeInByVoice.collectAsState()
        val text = reply?.takeIf { it.isNotBlank() }
            ?: return StatusText(if (byVoice) Res.string.voice_status_speaking else Res.string.voice_status_speaking_press)
        val ink = MaterialTheme.colorScheme.onSurface
        return FollowingText(spokenAnnotated(text, text.length, ink, ink), follow = 0)
    }
    // Pieces arrive as they start playing, so the reply's text supplies what is still to be said.
    val text = remember(segments, reply) { replyToShow(spokenReply(segments), reply) }
    val spoken by remember(segments, interpolate) { derivedStateOf { spokenCharacters(segments, now.value, interpolate) } }
    val bright = MaterialTheme.colorScheme.onSurface
    val dim = MaterialTheme.customColors.onSurfaceMutedColor
    FollowingText(spokenAnnotated(text, spoken, bright, dim), follow = spoken)
}

/**
 * The text to light: the whole [reply] when it holds at least what has been spoken (so the words
 * still to come show dimmed), else the [spoken] pieces themselves.
 */
internal fun replyToShow(spoken: String, reply: String?): String {
    val full = reply?.trim()?.takeIf { it.isNotEmpty() } ?: return spoken
    return if (full.length >= spoken.length) full else spoken
}

/** What went wrong with the last utterance, in the error colour, and Retry or Set up where one helps. */
@Composable
private fun FailedTurn(handle: VoiceModeHandle, state: VoiceModeState.TurnFailed) {
    Column(verticalArrangement = Arrangement.spacedBy(LettaDimens.Space.xs)) {
        Text(
            text = stringResource(voiceErrorText(state.code)),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.testTag(VoiceModeTags.CARD_BODY).semantics { liveRegion = LiveRegionMode.Polite },
        )
        when {
            state.retryable -> LettaChip(
                text = stringResource(Res.string.voice_action_retry),
                onClick = { handle.controller.retry() },
                modifier = Modifier.testTag(VoiceModeTags.CARD_ACTION),
            )
            state.code.action == VoiceUserAction.OPEN_SETTINGS -> LettaChip(
                text = stringResource(Res.string.voice_action_setup),
                onClick = { handle.setupOpen = true },
                modifier = Modifier.testTag(VoiceModeTags.CARD_ACTION),
            )
        }
    }
}

/** A status line ("Listening…", "Thinking…") in the large body type, muted, announced politely when it changes. */
@Composable
private fun StatusText(status: StringResource) {
    Text(
        text = stringResource(status),
        style = MaterialTheme.typography.titleLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.testTag(VoiceModeTags.CARD_BODY).semantics { liveRegion = LiveRegionMode.Polite },
    )
}

/**
 * [text] in the large body type, at most [BODY_LINES] lines tall and scrolling beyond: whenever
 * [follow] (a character offset) moves to another line, the view scrolls to keep that line and the one
 * before it in sight (in one step under reduced motion).
 */
@Composable
private fun FollowingText(text: AnnotatedString, follow: Int) {
    val style = MaterialTheme.typography.titleLarge
    val density = LocalDensity.current
    val lineHeight = if (style.lineHeight.isSpecified) style.lineHeight else style.fontSize
    val maxHeight: Dp = with(density) { lineHeight.toDp() } * BODY_LINES
    val scroll = rememberScrollState()
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    val reducedMotion = LocalReducedMotion.current
    val line = layout?.takeIf { it.layoutInput.text == text }?.getLineForOffset(follow.coerceIn(0, text.length)) ?: 0
    LaunchedEffect(line, layout) {
        val result = layout ?: return@LaunchedEffect
        val target = result.getLineTop((line - 1).coerceAtLeast(0)).roundToInt()
        if (reducedMotion) scroll.scrollTo(target) else scroll.animateScrollTo(target)
    }
    Box(Modifier.heightIn(max = maxHeight).verticalScroll(scroll)) {
        Text(
            text = text,
            style = style,
            onTextLayout = { layout = it },
            modifier = Modifier.testTag(VoiceModeTags.CARD_BODY),
        )
    }
}

private const val BODY_LINES = 4
