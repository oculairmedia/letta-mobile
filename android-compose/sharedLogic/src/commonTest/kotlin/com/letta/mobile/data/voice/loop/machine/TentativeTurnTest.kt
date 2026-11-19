package com.letta.mobile.data.voice.loop.machine

import com.letta.mobile.data.voice.SpeechSkipReason
import com.letta.mobile.data.voice.VoiceLoopConfig
import com.letta.mobile.data.voice.VoicePhase
import com.letta.mobile.data.voice.VoiceText
import com.letta.mobile.data.voice.VoiceUtterance
import com.letta.mobile.data.voice.activation.VoiceActivationKind
import com.letta.mobile.data.voice.engine.SpeechSegmentId
import com.letta.mobile.data.voice.engine.VoiceEarEvent
import com.letta.mobile.data.voice.engine.VoiceMouthEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The revision gate: words offered before the turn is over are answered at once, but nothing of the
 * answer plays until the turn ends with the same words; going on speaking, or other final words,
 * drops that answer and its audio.
 */
class TentativeTurnTest {
    private val idle = LoopModel(VoiceLoopConfig())

    private fun LoopModel.then(vararg inputs: LoopInput): Step =
        inputs.fold(Step(this)) { step, input -> VoiceLoopMachine.reduce(step.model, input).after(step.effects) }

    private fun ear(event: VoiceEarEvent) = LoopInput.FromEar(event)

    private fun offer(words: String) = ear(VoiceEarEvent.TentativeEndOfTurn(VoiceUtterance(VoiceText(words))))

    private fun end(words: String) = ear(VoiceEarEvent.EndOfTurn(VoiceUtterance(VoiceText(words))))

    private val withdrawn = ear(VoiceEarEvent.TentativeWithdrawn)

    private fun segment(key: Long, text: String) = LoopInput.FromReply(TurnKey(key), ReplySignal.Segment(VoiceText(text)))

    /** Listening, with the person mid-turn. */
    private fun speaking(model: LoopModel = idle): LoopModel = model.then(
        LoopInput.Activate(VoiceActivationKind.TAP),
        ear(VoiceEarEvent.ListeningStarted),
        ear(VoiceEarEvent.SpeechStarted),
    ).model

    private fun Step.responds(): List<LoopEffect.Respond> = effects.filterIsInstance<LoopEffect.Respond>()

    private fun Step.speaks(): List<String> = effects.filterIsInstance<LoopEffect.Speak>().map { it.segment.text.value }

    @Test
    fun anOfferIsSentAtOnceButItsAnswerWaitsForTheTurnToEnd() {
        val offered = speaking().then(offer("what time is it"))
        assertEquals(listOf("what time is it"), offered.responds().map { it.utterance.text.value }, "the agent starts on the offer")

        val answered = offered.model.then(segment(1, "It is noon."))
        assertEquals(emptyList(), answered.speaks(), "held behind the gate while the person may still go on")

        val confirmed = answered.model.then(ear(VoiceEarEvent.SpeechEnded), end("What time is it?"))
        assertEquals(listOf("It is noon."), confirmed.speaks(), "the same words open the gate")
        assertTrue(confirmed.responds().isEmpty(), "the confirmed words are not sent again")
        assertEquals(VoicePhase.SPEAKING, confirmed.model.phase)
        assertNull(confirmed.model.turn.tentative)
    }

    @Test
    fun goingOnSpeakingDropsTheOfferedAnswerAndItsAudio() {
        val offered = speaking().then(offer("book a table"), segment(1, "Sure, for when?"))
        val resumed = offered.model.then(withdrawn)
        assertTrue(LoopEffect.RequestInterrupt(TurnKey(1)) in resumed.effects, "its run is asked to stop")
        assertTrue(LoopEffect.SpeechSkipped(SpeechSkipReason.REVISED, 1) in resumed.effects, "its held text is never spoken")
        assertTrue(resumed.model.turn.replies.isEmpty())

        val late = resumed.model.then(segment(1, "Anything else?"))
        assertEquals(emptyList(), late.speaks(), "the stale answer's later text is dropped")

        val finished = late.model.then(ear(VoiceEarEvent.SpeechEnded), end("book a table for two at eight"))
        assertEquals(listOf("book a table for two at eight"), finished.responds().map { it.utterance.text.value })
        assertEquals(listOf(TurnKey(2)), finished.responds().map { it.turn }, "the continued turn is a new turn")
        val spoken = finished.model.then(segment(2, "Booked."))
        assertEquals(listOf("Booked."), spoken.speaks())
    }

    @Test
    fun differentFinalWordsWinOverTheOffer() {
        val offered = speaking().then(offer("call bob"), segment(1, "Calling Bob."))
        val corrected = offered.model.then(ear(VoiceEarEvent.SpeechEnded), end("call rob"))
        assertTrue(LoopEffect.RequestInterrupt(TurnKey(1)) in corrected.effects)
        assertEquals(listOf("call rob"), corrected.responds().map { it.utterance.text.value })
        assertEquals(emptyList(), corrected.speaks(), "the offered answer never plays")
        assertEquals(listOf("Calling Rob."), corrected.model.then(segment(2, "Calling Rob.")).speaks())
    }

    @Test
    fun anEmptyFinalTranscriptDropsTheOfferWithoutANewTurn() {
        val offered = speaking().then(offer("um"), segment(1, "Yes?"))
        val dropped = offered.model.then(ear(VoiceEarEvent.SpeechEnded), end(""))
        assertTrue(LoopEffect.RequestInterrupt(TurnKey(1)) in dropped.effects)
        assertTrue(dropped.responds().isEmpty())
        assertEquals(VoicePhase.LISTENING, dropped.model.phase)
    }

    @Test
    fun partialWordsOfferedAfterTheTurnClosedStillWaitForTheFinalTranscript() {
        val closed = speaking().then(ear(VoiceEarEvent.SpeechEnded), offer("turn on the lights"))
        assertEquals(VoicePhase.THINKING, closed.model.phase)
        val held = closed.model.then(segment(1, "Done."))
        assertEquals(emptyList(), held.speaks(), "held although the person is quiet: the final words are still to come")
        assertEquals(listOf("Done."), held.model.then(end("Turn on the lights.")).speaks())
    }

    @Test
    fun aCompletedAnswerHeldBehindTheGatePlaysOnceConfirmed() {
        val done = speaking().then(offer("hi"), segment(1, "Hello!"), LoopInput.FromReply(TurnKey(1), ReplySignal.Completed))
        assertEquals(emptyList(), done.speaks())
        val confirmed = done.model.then(ear(VoiceEarEvent.SpeechEnded), end("hi"))
        assertEquals(listOf("Hello!"), confirmed.speaks())
        val over = confirmed.model.then(LoopInput.FromMouth(VoiceMouthEvent.Finished(SpeechSegmentId(1))))
        assertEquals(VoicePhase.LISTENING, over.model.phase)
    }

    @Test
    fun anOfferIsIgnoredWhenTheMicrophoneClosesWhileAnswering() {
        val step = speaking(idle.copy(config = VoiceLoopConfig(bargeIn = false))).then(offer("hello"))
        assertTrue(step.responds().isEmpty(), "the confirmation could never be heard")
        assertNull(step.model.turn.tentativeWords)
    }

    @Test
    fun anOfferWhileTheAppSpeaksIsIgnoredAndTheTurnEndsTheUsualWay() {
        val answering = speaking().then(ear(VoiceEarEvent.SpeechEnded), end("first"), segment(1, "Answer one.")).model
        assertEquals(VoicePhase.SPEAKING, answering.phase)
        val offered = answering.then(offer("second"))
        assertTrue(offered.responds().isEmpty())
        assertNull(offered.model.turn.tentativeWords)
    }

    @Test
    fun aTapWhileTheOfferedAnswerIsAwaitedDoesNotResendTheSameWords() {
        val closed = speaking().then(ear(VoiceEarEvent.SpeechEnded), offer("play music")).model
        val tapped = closed.then(LoopInput.Activate(VoiceActivationKind.TAP))
        assertTrue(LoopEffect.RequestInterrupt(TurnKey(1)) in tapped.effects)
        val confirmed = tapped.model.then(end("play music"))
        assertTrue(confirmed.responds().isEmpty(), "the person interrupted these words: they are not sent again")
        assertNull(confirmed.model.turn.tentativeWords)
    }

    @Test
    fun aWithdrawalWithoutAnOfferChangesNothing() {
        val listening = speaking()
        val step = listening.then(withdrawn)
        assertEquals(listening, step.model)
        assertTrue(step.effects.isEmpty())
    }

    @Test
    fun wordsCompareWithoutCasePunctuationOrSpacing() {
        assertTrue(sameWords(VoiceText("Don't stop, now."), VoiceText("dont  stop now")))
        assertTrue(sameWords(VoiceText("It’s 5 o'clock!"), VoiceText("its 5 oclock")))
        assertFalse(sameWords(VoiceText("call bob"), VoiceText("call rob")))
        assertFalse(sameWords(VoiceText("call bob"), VoiceText("call bob now")))
    }
}
