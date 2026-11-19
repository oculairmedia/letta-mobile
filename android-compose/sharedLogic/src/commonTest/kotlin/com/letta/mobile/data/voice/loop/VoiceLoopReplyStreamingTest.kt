package com.letta.mobile.data.voice.loop

import com.letta.mobile.data.voice.VoicePhase
import com.letta.mobile.data.voice.VoiceText
import com.letta.mobile.data.voice.engine.VoiceReplyEvent
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** Reply text reaches the mouth while the reply is still streaming, not once it completes. */
@OptIn(ExperimentalCoroutinesApi::class)
class VoiceLoopReplyStreamingTest {
    @Test
    fun theFirstSentenceIsSpokenBeforeTheReplyCompletes() = runTest {
        val h = LoopHarness(this)
        h.listening()
        h.turn()

        h.replyText(VoiceText("It is"))
        h.replyText(VoiceText(" noon. It is"))
        assertEquals(listOf("It is noon."), h.mouth.spokenTexts, "spoken while the reply is still open")
        assertEquals(VoicePhase.SPEAKING, h.state.phase)

        h.replyText(VoiceText(" sunny. And"))
        assertEquals(listOf("It is noon.", "It is sunny."), h.mouth.spokenTexts, "each later sentence as soon as it ends")

        h.reply(VoiceReplyEvent.Completed)
        assertEquals(listOf("It is noon.", "It is sunny.", "And"), h.mouth.spokenTexts)
    }

    @Test
    fun aReplyThatArrivesAsABurstSpeaksItsFirstSentenceAloneAndBatchesTheRest() = runTest {
        val h = LoopHarness(this)
        h.listening()
        h.turn()

        h.replyText(VoiceText("It is noon. It is sunny. It is warm. "))
        assertEquals(listOf("It is noon.", "It is sunny. It is warm."), h.mouth.spokenTexts)

        h.reply(VoiceReplyEvent.Completed)
        assertEquals(listOf("It is noon.", "It is sunny. It is warm."), h.mouth.spokenTexts)
    }

    @Test
    fun aBurstEndingMidSentenceSpeaksThatSentenceWhenTheReplyCompletes() = runTest {
        val h = LoopHarness(this)
        h.listening()
        h.turn()

        h.replyText(VoiceText("It is noon. It is sunny. It is warm."))
        assertEquals(listOf("It is noon.", "It is sunny."), h.mouth.spokenTexts)

        h.reply(VoiceReplyEvent.Completed)
        assertEquals(listOf("It is noon.", "It is sunny.", "It is warm."), h.mouth.spokenTexts)
    }
}
