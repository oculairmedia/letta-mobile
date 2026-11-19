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
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.distinctUntilChanged
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.rememberUpdatedState
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
import com.letta.mobile.sharedui.resources.voice_card_status_speaking
import com.letta.mobile.sharedui.resources.voice_status_hearing
import com.letta.mobile.sharedui.resources.voice_status_listening
import com.letta.mobile.sharedui.resources.voice_status_thinking
import com.letta.mobile.ui.chat.voice.VoiceCardTiming
import com.letta.mobile.ui.chat.voice.VoiceModeHandle
import com.letta.mobile.ui.chat.voice.VoiceModeTags
import com.letta.mobile.ui.chat.voice.voiceErrorText
import com.letta.mobile.ui.components.lettaFadingEdges
import com.letta.mobile.ui.components.foundation.LettaChip
import com.letta.mobile.ui.theme.LettaDimens
import com.letta.mobile.ui.theme.LocalReducedMotion
import com.letta.mobile.ui.theme.customColors
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import kotlin.math.roundToInt

/**
 * The card's body for [state], in large type: the person's words live as they are heard (one quiet
 * caption line until there are any); while the reply is awaited, their last words dimmed; while it is
 * spoken, the reply with its spoken words lit and the rest dimmed; after a failed utterance, what went
 * wrong and what helps. [reply] is the chat's newest reply text; [now] is the card's clock.
 */
@Composable
internal fun VoiceCardBody(handle: VoiceModeHandle, state: VoiceModeState, reply: String?, timing: VoiceCardTiming) {
    LocalRecompositionProbe.current?.invoke(PROBE_BODY)
    val words by handle.partialTranscript.collectAsState()
    var lastHeard by remember { mutableStateOf("") }
    LaunchedEffect(words) { words?.value?.takeIf { it.isNotBlank() }?.let { lastHeard = it } }
    val ink = MaterialTheme.colorScheme.onSurface
    val dim = MaterialTheme.customColors.onSurfaceMutedColor
    when (state) {
        is VoiceModeState.TurnFailed -> FailedTurn(handle, state)
        VoiceModeState.Speaking -> SpokenReply(handle, reply, timing)
        VoiceModeState.WaitingForReply -> {
            if (lastHeard.isEmpty()) return Caption(Res.string.voice_status_thinking)
            FollowingText(spokenAnnotated(lastHeard, 0, ink, dim), follow = lastHeard.length)
        }
        else -> {
            val waiting = if (state == VoiceModeState.Hearing) Res.string.voice_status_hearing else Res.string.voice_status_listening
            val heard = words?.value?.takeIf { it.isNotBlank() } ?: return Caption(waiting)
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
private fun SpokenReply(handle: VoiceModeHandle, reply: String?, timing: VoiceCardTiming) {
    LocalRecompositionProbe.current?.invoke(PROBE_SPOKEN)
    val all by handle.cardFeed.spokenSegments.collectAsState()
    // Only this reply's pieces: any that started before the agent's turn belong to an earlier reply.
    val segments = remember(all, timing.turnStartMs) { all.filter { it.startedAtMs >= timing.turnStartMs } }
    val interpolate = !LocalReducedMotion.current
    val text = remember(segments, reply) { replyToShow(spokenReply(segments), reply) }
    if (text.isEmpty()) return Caption(Res.string.voice_card_status_speaking)
    val now = timing.now
    // The lit edge moves every frame: a derived value read at draw time (and by the scroll), never here.
    val lit = remember(segments, interpolate, text) {
        derivedStateOf { if (segments.isEmpty()) text.length else spokenCharacters(segments, now.value, interpolate) }
    }
    LitText(text, lit)
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
 * While there are no words yet: one quiet line saying what voice mode is doing ("Listening…"), so the
 * card stays compact until words arrive.
 */
@Composable
private fun Caption(status: StringResource) {
    Text(
        text = stringResource(status),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.customColors.onSurfaceMutedColor,
        maxLines = 1,
        modifier = Modifier.testTag(VoiceModeTags.CARD_CAPTION).semantics { liveRegion = LiveRegionMode.Polite },
    )
}

/**
 * [text] in the card's large type, growing a line at a time to [MAX_LINES] and scrolling beyond:
 * whenever [follow] (a character offset) moves to another line, the view scrolls to keep that line
 * and the one before it in sight (in one step under reduced motion), and the older lines above fade
 * out at the top edge. The person's words and their last words use it; they change with each
 * transcript update, not every frame.
 */
@Composable
private fun FollowingText(text: AnnotatedString, follow: Int) {
    val follows = rememberUpdatedState(follow)
    BodyScroller(follows) { onLayout ->
        Text(text = text, style = bodyStyle(), onTextLayout = onLayout, modifier = Modifier.testTag(VoiceModeTags.CARD_BODY))
    }
}

/**
 * The reply with its first [lit] characters in full ink and the rest dimmed, drawn as two layers of
 * the same text (dim beneath, full ink clipped to the spoken characters' boxes) so the lit edge moves
 * at draw time: the text is laid out once per reply, not once per character.
 */
@Composable
private fun LitText(text: String, lit: State<Int>) {
    val style = bodyStyle()
    val bright = MaterialTheme.colorScheme.onSurface
    val dim = MaterialTheme.customColors.onSurfaceMutedColor
    val probe = LocalSpokenEdgeProbe.current
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    BodyScroller(lit) { onLayout ->
        Box {
            Text(
                text = text,
                style = style,
                color = dim,
                onTextLayout = { result -> layout = result; onLayout(result) },
                modifier = Modifier.testTag(VoiceModeTags.CARD_BODY),
            )
            Text(
                text = text,
                style = style,
                color = bright,
                modifier = Modifier
                    .clearAndSetSemantics {}
                    .drawWithContent {
                        val edge = lit.value.coerceIn(0, text.length)
                        probe?.invoke(edge)
                        val shape = layout?.takeIf { it.layoutInput.text.text == text } ?: return@drawWithContent
                        if (edge > 0) clipPath(shape.getPathForRange(0, edge)) { this@drawWithContent.drawContent() }
                    },
            )
        }
    }
}

/**
 * The body's scrolling box: at most [MAX_LINES] lines of the large type, following [follow] (a
 * character offset of the text [content] lays out, read in a coroutine, never in composition) a line
 * at a time, its older lines fading at the top once it has scrolled.
 */
@Composable
private fun BodyScroller(follow: State<Int>, content: @Composable (onLayout: (TextLayoutResult) -> Unit) -> Unit) {
    val style = bodyStyle()
    val density = LocalDensity.current
    val lineHeight = if (style.lineHeight.isSpecified) style.lineHeight else style.fontSize
    val line: Dp = with(density) { lineHeight.toDp() }
    val scroll = rememberScrollState()
    val layout = remember { mutableStateOf<TextLayoutResult?>(null) }
    val reducedMotion = LocalReducedMotion.current
    LaunchedEffect(scroll) {
        snapshotFlow {
            val result = layout.value ?: return@snapshotFlow null
            val offset = follow.value.coerceIn(0, result.layoutInput.text.length)
            result.getLineTop((result.getLineForOffset(offset) - 1).coerceAtLeast(0)).roundToInt()
        }.filterNotNull().distinctUntilChanged().collect { target ->
            if (reducedMotion) scroll.scrollTo(target) else scroll.animateScrollTo(target)
        }
    }
    val scrolled by remember { derivedStateOf { scroll.value > 0 } }
    Box(
        Modifier
            .fillMaxWidth()
            .heightIn(max = line * MAX_LINES)
            .lettaFadingEdges(
                topFadeAlpha = if (scrolled) 1f else 0f,
                bottomFadeAlpha = 0f,
                topFadeLength = line,
                bottomFadeLength = line,
            )
            .verticalScroll(scroll),
    ) {
        content { result -> layout.value = result }
    }
}

/** The card's large body type. */
@Composable
private fun bodyStyle(): TextStyle = MaterialTheme.typography.headlineSmall

private const val MAX_LINES = 4
