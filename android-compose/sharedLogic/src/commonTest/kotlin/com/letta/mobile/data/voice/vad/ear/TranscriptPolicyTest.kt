package com.letta.mobile.data.voice.vad.ear

import app.cash.turbine.test
import com.letta.mobile.data.voice.VoiceText
import com.letta.mobile.data.voice.VoiceUtterance
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

    private fun reason(text: String, report: EarTurnReport = shortLoud) = policy.discardReason(VoiceText(text), report)

    @Test
    fun emptyAndWordlessTranscriptsAreDroppedWhateverTheTurn() {
        assertEquals(TurnDiscardReason.EMPTY_TRANSCRIPT, reason(""))
        assertEquals(TurnDiscardReason.EMPTY_TRANSCRIPT, reason("   ", longQuiet))
        assertEquals(TurnDiscardReason.NO_WORDS, reason(" . ? ...", longQuiet))
        assertEquals(TurnDiscardReason.NO_WORDS, reason(" I."))
    }

    @Test
    fun stockNoisePhrasesAreDroppedOnlyForShortQuietTurns() {
        assertEquals(TurnDiscardReason.STOCK_PHRASE, reason(" you", shortQuiet))
        assertEquals(TurnDiscardReason.STOCK_PHRASE, reason("Thank you.", shortQuiet))
        assertEquals(TurnDiscardReason.STOCK_PHRASE, reason("Thanks for watching!", shortQuiet))
        assertNull(reason("Thank you.", shortLoud))
        assertNull(reason("Thank you.", longQuiet))
    }

    @Test
    fun aTurnWithNoFloorMeasuredCountsAsQuiet() {
        assertEquals(TurnDiscardReason.STOCK_PHRASE, reason("you", EarTurnReport(400.milliseconds, -10.0, null)))
    }

    @Test
    fun realWordsAreKept() {
        assertNull(reason("ok", shortQuiet))
        assertNull(reason("What time is it?", shortQuiet))
        assertNull(reason("you know what, never mind", shortQuiet))
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
        ear(ScriptedSource(turn() + turn()), transcriber, observer).listen().filterNot { it is VoiceEarEvent.InputLevel }.test {
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
