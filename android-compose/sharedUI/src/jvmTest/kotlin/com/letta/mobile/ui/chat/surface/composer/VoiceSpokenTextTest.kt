package com.letta.mobile.ui.chat.surface.composer

import androidx.compose.ui.graphics.Color
import com.letta.mobile.data.voice.VoiceText
import com.letta.mobile.data.voice.output.SpokenSegment
import com.letta.mobile.data.voice.speech.SentenceChunker
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * How much of the reply the voice card lights: finished pieces whole, the playing piece in proportion
 * to how far its audio has played (characters standing in for words), nothing of a piece still
 * queued; under reduced motion the playing piece lights whole, so the light moves a piece at a time.
 */
class VoiceSpokenTextTest {
    private fun piece(index: Int, text: String, startedAtMs: Long, durationMs: Long? = null, finishedAtMs: Long? = null) =
        SpokenSegment(index, VoiceText(text), startedAtMs, durationMs, finishedAtMs)

    private val first = piece(0, "I can help refactor code,", startedAtMs = 0, durationMs = 2_000, finishedAtMs = 2_000)
    private val second = piece(1, "draft pull requests,", startedAtMs = 2_000, durationMs = 2_000)
    private val third = piece(2, "or summarize documents.", startedAtMs = 4_000)
    private val reply = listOf(first, second)

    @Test
    fun thePiecesJoinWithOneSpaceInIndexOrder() {
        assertEquals(
            "I can help refactor code, draft pull requests, or summarize documents.",
            spokenReply(listOf(third, first, second).map { it.copy(text = VoiceText(" ${it.text.value} ")) }),
        )
    }

    @Test
    fun theFinishedPieceLightsWholeAndThePlayingOneByItsShareOfAudio() {
        val firstLength = first.text.value.length
        // The second piece (with the space before it) is half played.
        val half = ((" " + second.text.value).length * 0.5f).roundToInt()
        assertEquals(firstLength, spokenCharacters(reply, nowMs = 2_000, interpolate = true))
        assertEquals(firstLength + half, spokenCharacters(reply, nowMs = 3_000, interpolate = true))
        assertEquals(firstLength + (" " + second.text.value).length, spokenCharacters(reply, nowMs = 9_000, interpolate = true))
    }

    @Test
    fun aPieceThatHasJustStartedCountsNothingYet() {
        assertEquals(first.text.value.length, spokenCharacters(reply, nowMs = 2_000, interpolate = true))
        assertEquals(0, spokenCharacters(emptyList(), nowMs = 10_000, interpolate = true))
    }

    @Test
    fun underReducedMotionThePlayingPieceLightsWholeAtItsStart() {
        val bothLit = first.text.value.length + (" " + second.text.value).length
        assertEquals(bothLit, spokenCharacters(reply, nowMs = 2_001, interpolate = false))
        assertEquals(bothLit, spokenCharacters(reply, nowMs = 3_000, interpolate = false))
    }

    @Test
    fun aPieceWhoseAudioLengthIsUnknownLightsWholeWhilePlaying() {
        val unknown = listOf(first, second.copy(audioDurationMs = null))
        val bothLit = first.text.value.length + (" " + second.text.value).length
        assertEquals(bothLit, spokenCharacters(unknown, nowMs = 2_100, interpolate = true))
    }

    @Test
    fun theTextSplitsIntoTheSpokenAndTheRest() {
        val text = spokenAnnotated("spoken rest", 6, Color.White, Color.Gray)
        assertEquals("spoken rest", text.text)
        val spans = text.spanStyles.map { Triple(it.start, it.end, it.item.color) }
        assertEquals(listOf(Triple(0, 6, Color.White), Triple(6, 11, Color.Gray)), spans)
        val all = spokenAnnotated("spoken rest", 99, Color.White, Color.Gray).spanStyles.first()
        assertEquals(Triple(0, 11, Color.White), Triple(all.start, all.end, all.item.color))
    }

    @Test
    fun theReplysTextSuppliesWhatIsStillToBeSaid() {
        // Pieces arrive only as they start playing: the chat's reply shows the rest, dimmed.
        val reply = "I can help refactor code, draft pull requests, or summarize documents."
        assertEquals(reply, replyToShow("I can help refactor code,", reply))
        assertEquals("I can help", replyToShow("I can help", null))
        assertEquals("Longer spoken text than the reply", replyToShow("Longer spoken text than the reply", "Short"))
    }

    /**
     * A formatted reply lights by the same words that are spoken: the card shows it as the speech path
     * speaks it (no markdown, link target or code block), so the spoken pieces' character count ends
     * exactly where the spoken words end, at any speed.
     */
    @Test
    fun aFormattedReplyLightsAtTheSpokenPosition() {
        val reply = "Use **bold** words and see [the guide](https://example.org/guide) first.\n\n" +
            "```kotlin\nval answer = 42\n```\n\nThen run it again."
        // What the speech path speaks of it, piece by piece: the chunker the mouth is fed by.
        val chunker = SentenceChunker()
        val pieces = chunker.push(VoiceText(reply)) + chunker.flush()
        val spoken = listOf(piece(0, pieces[0].value, startedAtMs = 0, durationMs = 1_000, finishedAtMs = 1_000))
        val shown = replyToShow(spokenReply(spoken), reply)
        val lit = spokenCharacters(spoken, nowMs = 1_000, interpolate = true)

        assertEquals("Use bold words and see the guide first. Then run it again.", shown)
        assertEquals("Use bold words and see the guide first.", shown.substring(0, lit), "the light ends where the speech is")
        listOf("**", "https://", "```", "answer").forEach { assertFalse(it in shown, "$it is not shown") }
    }

    @Test
    fun speedsAndTimesReadPlainly() {
        assertEquals(listOf("0.75", "1", "1.25", "1.5"), listOf(0.75f, 1f, 1.25f, 1.5f).map(::speedText))
        assertEquals(listOf("0:00", "0:06", "1:35", "12:04"), listOf(-5L, 6_400L, 95_000L, 724_999L).map(::formatElapsed))
    }
}
