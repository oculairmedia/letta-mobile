package com.letta.mobile.ui.chat.surface.composer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.isSpecified
import com.letta.mobile.data.voice.VoiceUserAction
import com.letta.mobile.data.voice.session.VoiceModeState
import com.letta.mobile.sharedui.resources.Res
import com.letta.mobile.sharedui.resources.voice_action_retry
import com.letta.mobile.sharedui.resources.voice_action_setup
import com.letta.mobile.ui.chat.voice.VoiceModeHandle
import com.letta.mobile.ui.chat.voice.VoiceModeTags
import com.letta.mobile.ui.chat.voice.voiceErrorText
import com.letta.mobile.ui.components.lettaFadingEdges
import com.letta.mobile.ui.components.foundation.LettaChip
import com.letta.mobile.ui.theme.LettaDimens
import com.letta.mobile.ui.theme.LocalReducedMotion
import com.letta.mobile.ui.theme.customColors
import org.jetbrains.compose.resources.stringResource
import kotlin.math.roundToInt

/**
 * The card's body for [state], in large type, never the status (the header says that): the person's
 * words live as they are heard; while the reply is awaited, their last words dimmed; while it is
 * spoken, the reply with its spoken words lit and the rest dimmed; after a failed utterance, what went
 * wrong and what helps. [reply] is the chat's newest reply text; [now] is the card's clock.
 */
@Composable
internal fun VoiceCardBody(handle: VoiceModeHandle, state: VoiceModeState, reply: String?, now: State<Long>) {
    val words by handle.partialTranscript.collectAsState()
    var lastHeard by remember { mutableStateOf("") }
    LaunchedEffect(words) { words?.value?.takeIf { it.isNotBlank() }?.let { lastHeard = it } }
    val ink = MaterialTheme.colorScheme.onSurface
    val dim = MaterialTheme.customColors.onSurfaceMutedColor
    when (state) {
        is VoiceModeState.TurnFailed -> FailedTurn(handle, state)
        VoiceModeState.Speaking -> SpokenReply(handle, reply, now)
        VoiceModeState.WaitingForReply -> FollowingText(spokenAnnotated(lastHeard, 0, ink, dim), follow = lastHeard.length)
        else -> {
            val heard = words?.value.orEmpty()
            FollowingText(spokenAnnotated(heard, heard.length, ink, dim), follow = heard.length)
        }
    }
}

/**
 * The reply as it is spoken: its text (the chat's reply, which holds the words still to come, or the
 * pieces spoken so far) with the spoken words in full ink and the rest dimmed, the view following the
 * spoken edge. Before any piece plays the whole reply is dim; with no spoken progress at all (the
 * controller publishes none) it is in full ink.
 */
@Composable
private fun SpokenReply(handle: VoiceModeHandle, reply: String?, now: State<Long>) {
    val segments by handle.cardFeed.spokenSegments.collectAsState()
    val interpolate = !LocalReducedMotion.current
    val text = remember(segments, reply) { replyToShow(spokenReply(segments), reply) }
    val spoken by remember(segments, interpolate) { derivedStateOf { spokenCharacters(segments, now.value, interpolate) } }
    val lit = if (segments.isEmpty()) text.length else spoken
    val bright = MaterialTheme.colorScheme.onSurface
    val dim = MaterialTheme.customColors.onSurfaceMutedColor
    FollowingText(spokenAnnotated(text, lit, bright, dim), follow = lit)
}

/** What went wrong with the last utterance, in the error colour, and Retry or Set up where one helps. */
@Composable
private fun FailedTurn(handle: VoiceModeHandle, state: VoiceModeState.TurnFailed) {
    Column(verticalArrangement = Arrangement.spacedBy(LettaDimens.Space.sm)) {
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

/**
 * [text] in the card's large type in a box that always holds [MIN_LINES] lines and grows to
 * [MAX_LINES], scrolling beyond: whenever [follow] (a character offset) moves to another line, the
 * view scrolls to keep that line and the one before it in sight (in one step under reduced motion),
 * and the older lines above fade out at the top edge.
 */
@Composable
private fun FollowingText(text: AnnotatedString, follow: Int) {
    val style = bodyStyle()
    val density = LocalDensity.current
    val lineHeight = if (style.lineHeight.isSpecified) style.lineHeight else style.fontSize
    val line: Dp = with(density) { lineHeight.toDp() }
    val scroll = rememberScrollState()
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    val reducedMotion = LocalReducedMotion.current
    val followLine = layout?.takeIf { it.layoutInput.text == text }?.getLineForOffset(follow.coerceIn(0, text.length)) ?: 0
    LaunchedEffect(followLine, layout) {
        val result = layout ?: return@LaunchedEffect
        val target = result.getLineTop((followLine - 1).coerceAtLeast(0)).roundToInt()
        if (reducedMotion) scroll.scrollTo(target) else scroll.animateScrollTo(target)
    }
    Box(
        Modifier
            .fillMaxWidth()
            .heightIn(min = line * MIN_LINES, max = line * MAX_LINES)
            .lettaFadingEdges(
                topFadeAlpha = if (scroll.value > 0) 1f else 0f,
                bottomFadeAlpha = 0f,
                topFadeLength = line,
                bottomFadeLength = line,
            )
            .verticalScroll(scroll),
    ) {
        Text(
            text = text,
            style = style,
            onTextLayout = { layout = it },
            modifier = Modifier.testTag(VoiceModeTags.CARD_BODY),
        )
    }
}

/** The card's large body type. */
@Composable
private fun bodyStyle(): TextStyle = MaterialTheme.typography.headlineSmall

private const val MIN_LINES = 2
private const val MAX_LINES = 4
