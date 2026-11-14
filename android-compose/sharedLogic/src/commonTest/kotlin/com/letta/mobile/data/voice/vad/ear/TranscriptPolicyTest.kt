package com.letta.mobile.data.voice.vad.ear

import app.cash.turbine.test
import com.letta.mobile.data.voice.VoiceText
import com.letta.mobile.data.voice.VoiceUtterance
import com.letta.mobile.data.voice.endpoint.RecognizerConfidence
import com.letta.mobile.data.voice.engine.VoiceEarEvent
import com.letta.mobile.data.voice.vad.EndOfTurnPolicy
import com.letta.mobile.data.voice.vad.SpeechDetectorFactory
import com.letta.mobile.data.voice.vad.TurnDiscardReason
import com.letta.mobile.data.voice.vad.TurnEndReason
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterNot
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class TranscriptPolicyTest {
    private val policy = TranscriptPolicy()
    private val shortQuiet = EarTurnReport(voiced = 400.milliseconds, peakDb = -40.0, floorDb = -55.0)
    private val shortLoud = EarTurnReport(voiced = 400.milliseconds, peakDb = -15.0, floorDb = -55.0)
    private val longQuiet = EarTurnReport(voiced = 3.seconds, peakDb = -40.0, floorDb = -55.0)

    /** A short, loud turn just over the filler limit: a real one-word answer said clearly in a loud room. */
    private val shortLoudAnswer = EarTurnReport(voiced = 700.milliseconds, peakDb = -12.0, floorDb = -40.0)

    /** What a recogniser reports for clear speech. */
    private val confidentSpeech = RecognizerConfidence(noSpeechProbability = 0.05, averageLogProbability = -0.3)

    private fun reason(text: String, report: EarTurnReport = shortLoud, confidence: RecognizerConfidence? = null) =
        policy.discardReason(VoiceText(text), report, confidence)

    @Test
    fun emptyAndWordlessTranscriptsAreDroppedWhateverTheTurn() {
        assertEquals(TurnDiscardReason.EMPTY_TRANSCRIPT, reason(""))
        assertEquals(TurnDiscardReason.EMPTY_TRANSCRIPT, reason("   ", longQuiet))
        assertEquals(TurnDiscardReason.NO_WORDS, reason(" . ? ...", longQuiet))
        assertEquals(TurnDiscardReason.NO_WORDS, reason(" I."))
    }

    @Test
    fun stockNoisePhrasesAreDroppedForShortTurnsHoweverLoud() {
        assertEquals(TurnDiscardReason.STOCK_PHRASE, reason(" you", shortQuiet))
        assertEquals(TurnDiscardReason.STOCK_PHRASE, reason("Thank you.", shortQuiet))
        assertEquals(TurnDiscardReason.STOCK_PHRASE, reason("Thanks for watching!", shortQuiet))
        assertEquals(TurnDiscardReason.STOCK_PHRASE, reason("Thank you.", shortLoud))
        assertEquals(TurnDiscardReason.STOCK_PHRASE, reason(" Thank you.", shortLoudAnswer))
        assertNull(reason("Thank you.", longQuiet))
    }

    @Test
    fun aLoneFillerWordFromAVeryShortTurnIsDropped() {
        assertEquals(TurnDiscardReason.FILLER_WORD, reason("So", shortLoud))
        assertEquals(TurnDiscardReason.FILLER_WORD, reason(" So.", shortQuiet))
        assertEquals(TurnDiscardReason.FILLER_WORD, reason("Okay.", shortLoud))
        assertNull(reason("So.", shortLoudAnswer), "a longer turn may mean it")
        assertNull(reason("So what time is it?", shortLoud))
    }

    @Test
    fun aTurnWithNoFloorMeasuredIsJudgedTheSame() {
        assertEquals(TurnDiscardReason.STOCK_PHRASE, reason("you", EarTurnReport(400.milliseconds, -10.0, null)))
        assertEquals(TurnDiscardReason.FILLER_WORD, reason("so", EarTurnReport(400.milliseconds, -10.0, null)))
    }

    @Test
    fun theRecognisersOwnNoSpeechVerdictDropsTheTranscript() {
        val certain = RecognizerConfidence(noSpeechProbability = 0.95, averageLogProbability = -0.2)
        val whisperSilence = RecognizerConfidence(noSpeechProbability = 0.7, averageLogProbability = -1.2)
        val garbled = RecognizerConfidence(noSpeechProbability = 0.1, averageLogProbability = -2.4)
        assertEquals(TurnDiscardReason.NOT_SPEECH, reason("Thank you.", longQuiet, certain))
        assertEquals(TurnDiscardReason.NOT_SPEECH, reason("I'll see you next time.", longQuiet, whisperSilence))
        assertEquals(TurnDiscardReason.NOT_SPEECH, reason("what time is it", longQuiet, garbled))
        val unsureButSpeech = RecognizerConfidence(noSpeechProbability = 0.7, averageLogProbability = -0.6)
        assertNull(reason("what time is it", longQuiet, unsureButSpeech), "Whisper keeps a confident transcript")
    }

    @Test
    fun shortGenuineAnswersPass() {
        listOf("yes", "Yes.", "no", "No.", "ok", "Yeah.", "Sure.", "Nope.").forEach { answer ->
            assertNull(reason(answer, shortLoud), answer)
            assertNull(reason(answer, shortQuiet), answer)
            assertNull(reason(answer, shortLoudAnswer, confidentSpeech), answer)
        }
    }

    @Test
    fun realWordsAreKept() {
        assertNull(reason("ok", shortQuiet))
        assertNull(reason("What time is it?", shortQuiet))
        assertNull(reason("you know what, never mind", shortQuiet))
        assertNull(reason("What time is it?", shortLoud, confidentSpeech))
    }
}

/** Records what the ear reports, as reasons and counts only. */
private class RecordingObserver : EarObserver {
    val seen = mutableListOf<String>()

    override fun turnOpened() {
        seen += "opened"
    }

    override fun turnClosed(report: EarTurnReport, reason: TurnEndReason) {
        seen += "closed:$reason"
    }

    override fun turnDiscarded(report: EarTurnReport, reason: TurnDiscardReason) {
        seen += "discarded:$reason"
    }

    override fun transcriptAccepted(characters: Int) {
        seen += "accepted:$characters"
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class EarTranscriptGateTest {
    private fun ear(source: ScriptedSource, transcriber: SpeechTranscriber, observer: EarObserver) = VadVoiceEar(
        source,
        transcriber,
        VadEarConfig(policy = EndOfTurnPolicy(), detectors = SpeechDetectorFactory { ValueDetector() }, observer = observer),
    )

    @Test
    fun aStockPhraseFromAShortQuietTurnEndsAsAnEmptyUtteranceAndTheEarListensOn() = runTest {
        val observer = RecordingObserver()
        val transcriber = ScriptedTranscriber(heard(" you"), heard("what time is it"))
        ear(ScriptedSource(turn() + turn()), transcriber, observer).listen()
            .filterNot { it is VoiceEarEvent.InputLevel || it == VoiceEarEvent.SpeechEnded }
            .test {
            assertEquals(VoiceEarEvent.ListeningStarted, awaitItem())
            // Transcription runs beside listening, so the second turn may start before the first is judged.
            val events = List(4) { awaitItem() }
            assertEquals(2, events.count { it == VoiceEarEvent.SpeechStarted })
            assertEquals(
                listOf(VoiceUtterance(VoiceText.Empty), VoiceUtterance(VoiceText("what time is it"))),
                events.filterIsInstance<VoiceEarEvent.EndOfTurn>().map { it.utterance },
            )
            cancelAndIgnoreRemainingEvents()
        }
        val turns = observer.seen.filter { it.startsWith("opened") || it.startsWith("closed") }
        val judged = observer.seen.filter { it.startsWith("discarded") || it.startsWith("accepted") }
        assertEquals(listOf("opened", "closed:SILENCE", "opened", "closed:SILENCE"), turns)
        assertEquals(listOf("discarded:STOCK_PHRASE", "accepted:15"), judged)
    }

    @Test
    fun aTranscriptTheRecogniserCallsNotSpeechEndsAsAnEmptyUtterance() = runTest {
        val observer = RecordingObserver()
        val noise = RecognizerConfidence(noSpeechProbability = 0.97, averageLogProbability = -0.4)
        val transcriber = ScriptedTranscriber(heard("I'm going to go to bed now", noise))
        ear(ScriptedSource(turn()), transcriber, observer).listen()
            .filterNot { it is VoiceEarEvent.InputLevel || it == VoiceEarEvent.SpeechEnded }
            .test {
                assertEquals(VoiceEarEvent.ListeningStarted, awaitItem())
                assertEquals(VoiceEarEvent.SpeechStarted, awaitItem())
                assertEquals(VoiceEarEvent.EndOfTurn(VoiceUtterance(VoiceText.Empty)), awaitItem())
                cancelAndIgnoreRemainingEvents()
            }
        assertEquals("discarded:NOT_SPEECH", observer.seen.last())
    }

    @Test
    fun aClickIsDroppedBeforeTranscription() = runTest {
        val observer = RecordingObserver()
        val transcriber = ScriptedTranscriber()
        val click = frames(PRE_ROLL_VALUE, 4) + frames(SPEECH_VALUE, 2) + frames(0, 20)
        ear(ScriptedSource(click), transcriber, observer).listen().filterNot { it is VoiceEarEvent.InputLevel }.test {
            assertEquals(VoiceEarEvent.ListeningStarted, awaitItem())
            delay(1_000)
            expectNoEvents()
            cancelAndIgnoreRemainingEvents()
        }
        assertTrue(transcriber.clips.isEmpty())
        assertEquals(listOf("discarded:NO_ONSET"), observer.seen)
    }
}
