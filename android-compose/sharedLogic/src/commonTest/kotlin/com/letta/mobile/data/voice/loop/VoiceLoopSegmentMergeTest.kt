package com.letta.mobile.data.voice.loop

import com.letta.mobile.data.voice.VoiceLoopConfig
import com.letta.mobile.data.voice.VoiceText
import com.letta.mobile.data.voice.engine.VoiceReplyEvent
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * A reply is spoken in few, longer segments (steadier voice from chunk to chunk) and its mood
 * changes only where the agent's cue says so, always at a segment boundary.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class VoiceLoopSegmentMergeTest {
    // Tail absorption off: these cases pin the merge-to-minimum behaviour on its own; the tail cases below turn it on.
    private val merging = VoiceLoopConfig(minSegmentLength = MIN, firstClauseMinLength = null, tailAbsorbLength = null)
    private val absorbing = merging.copy(tailAbsorbLength = TAIL)

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

    @Test
    fun aLateReplyStartsWithItsFirstSentenceAtOnce() = runTest {
        val h = LoopHarness(this, merging)
        h.speaking(VoiceText("On it. "))
        h.reply(VoiceReplyEvent.AwaitingLateReply)
        h.finishSpeech()

        h.replyText(VoiceText("Research done. It found two things. "))
        assertEquals(listOf("On it.", "Research done."), h.mouth.spokenTexts, "first sentence of the late reply is not held")
        h.reply(VoiceReplyEvent.Completed)
        h.finishSpeech()
        assertEquals(listOf("On it.", "Research done.", "It found two things."), h.mouth.spokenTexts)
    }

    @Test
    fun aStoppedReplyReportsItsSegmentsOnceWithoutSpeakingWhatWasHeld() = runTest {
        val h = LoopHarness(this, merging)
        h.listening()
        h.turn()

        h.replyText(VoiceText("It is noon. It is sunny. "))
        assertEquals(listOf("It is noon."), h.mouth.spokenTexts)
        h.loop.stop()
        h.settle()

        assertEquals(listOf("1/11/11/0"), h.segmented, "reported once, for the segment that went out")
        assertEquals(listOf("It is noon."), h.mouth.spokenTexts, "the held sentence is not flushed")
    }

    @Test
    fun theDefaultsMatchTheSpeechServersRequestSize() {
        val defaults = VoiceLoopConfig()
        assertEquals(200, defaults.minSegmentLength)
        assertEquals(400, defaults.maxSegmentLength)
        assertEquals(60, defaults.tailAbsorbLength)
    }

    @Test
    fun aTinyLastSentenceJoinsTheSegmentBeforeItAndIsReported() = runTest {
        val h = LoopHarness(this, absorbing)
        h.listening()
        h.turn()

        h.replyText(VoiceText("It is noon. $LONG_SECOND "))
        assertEquals(listOf("It is noon."), h.mouth.spokenTexts, "the long segment waits for a possible tail")
        h.replyText(VoiceText("Ok."))
        h.reply(VoiceReplyEvent.Completed)

        assertEquals(listOf("It is noon.", "$LONG_SECOND Ok."), h.mouth.spokenTexts)
        assertEquals(listOf("2/11/${(11 + LONG_SECOND.length + 4) / 2}/0"), h.segmented)
        assertEquals(listOf(1), h.absorbed)
    }

    @Test
    fun aTailLengthWithoutAMinimumLengthKeepsOneSentencePerSegment() = runTest {
        val h = LoopHarness(this, merging.copy(minSegmentLength = null, tailAbsorbLength = TAIL))
        h.listening()
        h.turn()

        // One delta per sentence: sentences released from the same delta are joined by the burst batching, which is separate.
        h.replyText(VoiceText("It is noon. "))
        h.replyText(VoiceText("$LONG_SECOND "))
        h.replyText(VoiceText("Ok. "))
        h.reply(VoiceReplyEvent.Completed)

        assertEquals(listOf("It is noon.", LONG_SECOND, "Ok."), h.mouth.spokenTexts)
        assertEquals(listOf(0), h.absorbed)
    }

    @Test
    fun aTailAfterAMoodCueIsNotJoinedAcrossTheMoodChange() = runTest {
        val h = LoopHarness(this, absorbing)
        h.listening()
        h.turn()

        h.replyText(VoiceText("[warm] It is noon. $LONG_SECOND [quiet] Ok. "))
        h.reply(VoiceReplyEvent.Completed)

        assertEquals(listOf("It is noon." to "warm", LONG_SECOND to "warm", "Ok." to "quiet"), h.spoken())
        assertEquals(listOf(0), h.absorbed)
    }

    @Test
    fun aTailAfterACodeBlockIsNotJoinedToTheTextBeforeIt() = runTest {
        val h = LoopHarness(this, absorbing)
        h.listening()
        h.turn()

        h.replyText(VoiceText("It is noon. $LONG_SECOND\n```\nval x = 1\n```\nOk. "))
        h.reply(VoiceReplyEvent.Completed)

        assertEquals(listOf("It is noon.", LONG_SECOND, "Ok."), h.mouth.spokenTexts)
    }

    @Test
    fun aMinimumLengthThatIsNotPositiveIsRejectedWhenConfigured() {
        assertFailsWith<IllegalArgumentException> { VoiceLoopConfig(minSegmentLength = 0) }
        assertFailsWith<IllegalArgumentException> { VoiceLoopConfig(minSegmentLength = -1) }
        assertEquals(null, VoiceLoopConfig(minSegmentLength = null).minSegmentLength)
        assertFailsWith<IllegalArgumentException> { VoiceLoopConfig(tailAbsorbLength = 0) }
    }

    private companion object {
        const val MIN = 30
        const val TAIL = 10
        const val LONG_SECOND = "This second sentence is comfortably long enough."
    }
}
