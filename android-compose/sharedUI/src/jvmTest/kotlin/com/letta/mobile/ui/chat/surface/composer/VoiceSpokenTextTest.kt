package com.letta.mobile.ui.chat.surface.composer

import androidx.compose.ui.graphics.Color
import com.letta.mobile.ui.chat.voice.VoiceSpokenSegment
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * How much of the reply the voice card lights: finished pieces whole, the playing piece in proportion
 * to how far its audio has played (characters standing in for words), nothing of a piece still
 * queued; under reduced motion the playing piece lights whole, so the light moves a piece at a time.
 */
class VoiceSpokenTextTest {
    private val first = VoiceSpokenSegment(0, "I can help refactor code,", startedAtMs = 0, audioDurationMs = 2_000, finishedAtMs = 2_000)
    private val second = VoiceSpokenSegment(1, "draft pull requests,", startedAtMs = 2_000, audioDurationMs = 2_000)
    private val third = VoiceSpokenSegment(2, "or summarize documents.")
    private val reply = listOf(first, second, third)

    @Test
    fun thePiecesJoinWithOneSpaceInIndexOrder() {
        assertEquals(
            "I can help refactor code, draft pull requests, or summarize documents.",
            spokenReply(listOf(third, first, second).map { it.copy(text = " ${it.text} ") }),
        )
    }

    @Test
    fun theFinishedPieceLightsWholeAndThePlayingOneByItsShareOfAudio() {
        val firstLength = first.text.length
        // The second piece (with the space before it) is half played.
        val half = ((" " + second.text).length * 0.5f).roundToInt()
        assertEquals(firstLength, spokenCharacters(reply, nowMs = 2_000, interpolate = true))
        assertEquals(firstLength + half, spokenCharacters(reply, nowMs = 3_000, interpolate = true))
        assertEquals(firstLength + (" " + second.text).length, spokenCharacters(reply, nowMs = 9_000, interpolate = true))
    }

    @Test
    fun aQueuedPieceAndEverythingAfterItStayDim() {
        val queued = listOf(first, second.copy(startedAtMs = null), third.copy(startedAtMs = 5_000, finishedAtMs = 6_000))
        assertEquals(first.text.length, spokenCharacters(queued, nowMs = 10_000, interpolate = true))
        assertEquals(0, spokenCharacters(listOf(third), nowMs = 10_000, interpolate = true))
    }

    @Test
    fun underReducedMotionThePlayingPieceLightsWholeAtItsStart() {
        val bothLit = first.text.length + (" " + second.text).length
        assertEquals(bothLit, spokenCharacters(reply, nowMs = 2_001, interpolate = false))
        assertEquals(bothLit, spokenCharacters(reply, nowMs = 3_000, interpolate = false))
    }

    @Test
    fun aPieceWhoseAudioLengthIsUnknownLightsWholeWhilePlaying() {
        val unknown = listOf(first, second.copy(audioDurationMs = null))
        assertEquals(first.text.length + (" " + second.text).length, spokenCharacters(unknown, nowMs = 2_100, interpolate = true))
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

    @Test
    fun speedsAndTimesReadPlainly() {
        assertEquals(listOf("0.75", "1", "1.25", "1.5"), listOf(0.75f, 1f, 1.25f, 1.5f).map(::speedText))
        assertEquals(listOf("0:00", "0:06", "1:35", "12:04"), listOf(-5L, 6_400L, 95_000L, 724_999L).map(::formatElapsed))
    }
}
