package com.letta.mobile.data.voice.loop

import com.letta.mobile.data.voice.VoiceText
import com.letta.mobile.data.voice.engine.VoiceReplyEvent
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** Tone cues are never spoken; each segment carries the cue in force for it. */
@OptIn(ExperimentalCoroutinesApi::class)
class VoiceLoopToneCueTest {
    private fun LoopHarness.spoken(): List<Pair<String, String?>> = mouth.segments.map { it.text.value to it.tone?.instructions }

    @Test
    fun aCueAppliesToItsSentenceAndTheNextUntilNeutral() = runTest {
        val h = LoopHarness(this)
        h.listening()
        h.turn()

        h.replyText(VoiceText("[excited] It is noon. It is sunny. [neutral] Bye."))
        h.reply(VoiceReplyEvent.Completed)

        assertEquals(
            listOf("It is noon." to "excited", "It is sunny." to "excited", "Bye." to null),
            h.spoken(),
        )
    }

    @Test
    fun aCueStreamedInPiecesIsNeverSpokenAndTakesEffectAtItsSentence() = runTest {
        val h = LoopHarness(this)
        h.listening()
        h.turn()

        h.replyText(VoiceText("It is late. [qui"))
        assertEquals(listOf("It is late." to null), h.spoken(), "the sentence before the cue keeps no tone")
        h.replyText(VoiceText("et] Good night. "))
        h.reply(VoiceReplyEvent.Completed)

        assertEquals(listOf("It is late." to null, "Good night." to "quiet"), h.spoken())
    }

    @Test
    fun theSpeakingViewShowsTheWordsNeverTheCue() = runTest {
        val h = LoopHarness(this)
        h.listening()
        h.turn()

        h.replyText(VoiceText("[warm] It is noon. "))
        h.mouth.started(h.mouth.segments.single().id)
        h.settle()

        assertEquals(VoiceText("It is noon."), h.state.speaking)
    }

    @Test
    fun bracketsThatAreNotCuesAreLeftToTheChunker() = runTest {
        val h = LoopHarness(this)
        h.listening()
        h.turn()

        h.replyText(VoiceText("See [1] for more. "))
        h.reply(VoiceReplyEvent.Completed)

        assertEquals(listOf<String?>(null), h.spoken().map { it.second })
    }
}
