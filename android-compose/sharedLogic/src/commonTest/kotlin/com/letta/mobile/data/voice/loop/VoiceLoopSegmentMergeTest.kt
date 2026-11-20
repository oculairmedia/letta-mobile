package com.letta.mobile.data.voice.loop

import com.letta.mobile.data.voice.VoiceLoopConfig
import com.letta.mobile.data.voice.VoiceText
import com.letta.mobile.data.voice.engine.VoiceReplyEvent
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A reply is spoken in few, longer segments (steadier voice from chunk to chunk) and its mood
 * changes only where the agent's cue says so, always at a segment boundary.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class VoiceLoopSegmentMergeTest {
    private val merging = VoiceLoopConfig(minSegmentLength = MIN, firstClauseMinLength = null)

    private fun LoopHarness.spoken(): List<Pair<String, String?>> = mouth.segments.map { it.text.value to it.tone?.instructions }

    @Test
    fun sentencesAfterTheFirstAreMergedAsTheyStreamIn() = runTest {
        val h = LoopHarness(this, merging)
        h.listening()
        h.turn()

        h.replyText(VoiceText("It is noon. It is sunny. "))
        assertEquals(listOf("It is noon."), h.mouth.spokenTexts, "first sentence at once; the second waits to be merged")
        h.replyText(VoiceText("It is warm. It is late. "))
        assertEquals(listOf("It is noon.", "It is sunny. It is warm. It is late."), h.mouth.spokenTexts)

        h.replyText(VoiceText("Good night."))
        h.reply(VoiceReplyEvent.Completed)
        assertEquals(listOf("It is noon.", "It is sunny. It is warm. It is late.", "Good night."), h.mouth.spokenTexts)
    }

    @Test
    fun theEndOfTheReplyReleasesWhatIsHeld() = runTest {
        val h = LoopHarness(this, merging)
        h.listening()
        h.turn()

        h.replyText(VoiceText("It is noon. It is sunny. "))
        h.reply(VoiceReplyEvent.Completed)

        assertEquals(listOf("It is noon.", "It is sunny."), h.mouth.spokenTexts)
    }

    @Test
    fun aCueEndsTheSegmentBeforeItAndTheMoodChangesFromTheNextOne() = runTest {
        val h = LoopHarness(this, merging)
        h.listening()
        h.turn()

        h.replyText(VoiceText("[warm] It is noon. It is sunny. It is warm. [quiet] It is late. It is dark. "))
        h.reply(VoiceReplyEvent.Completed)

        assertEquals(
            listOf(
                "It is noon." to "warm",
                "It is sunny. It is warm." to "warm",
                "It is late. It is dark." to "quiet",
            ),
            h.spoken(),
        )
    }

    @Test
    fun theMoodCarriesAcrossSegmentsWithoutACue() = runTest {
        val h = LoopHarness(this, merging)
        h.listening()
        h.turn()

        h.replyText(VoiceText("[excited] It is noon. It is sunny. It is warm. It is late. "))
        h.replyText(VoiceText("It is dark. It is cold. It is over. "))
        h.reply(VoiceReplyEvent.Completed)

        assertEquals(setOf<String?>("excited"), h.spoken().map { it.second }.toSet(), "every segment keeps the mood")
        assertEquals(3, h.mouth.segments.size)
    }

    @Test
    fun aMergedSegmentNeverCrossesACodeBlock() = runTest {
        val h = LoopHarness(this, merging)
        h.listening()
        h.turn()

        h.replyText(VoiceText("It is noon. It is sunny.\n```\nval x = 1\n```\nIt is warm. It is late. "))
        h.reply(VoiceReplyEvent.Completed)

        assertEquals(listOf("It is noon.", "It is sunny.", "It is warm. It is late."), h.mouth.spokenTexts)
    }

    @Test
    fun theReplyReportsItsSegmentCountsAndMoodChanges() = runTest {
        val h = LoopHarness(this, merging)
        h.listening()
        h.turn()

        h.replyText(VoiceText("[warm] It is noon. It is sunny. It is warm. [quiet] It is late. It is dark. "))
        h.reply(VoiceReplyEvent.Completed)

        // Segments: "It is noon." (11), "It is sunny. It is warm." (24), "It is late. It is dark." (23).
        assertEquals(listOf("3/11/23/1"), h.segmented)
    }

    @Test
    fun anEmptyReplyReportsNothing() = runTest {
        val h = LoopHarness(this, merging)
        h.listening()
        h.turn()

        h.reply(VoiceReplyEvent.Completed)

        assertEquals(emptyList(), h.segmented)
    }

    private companion object {
        const val MIN = 30
    }
}
