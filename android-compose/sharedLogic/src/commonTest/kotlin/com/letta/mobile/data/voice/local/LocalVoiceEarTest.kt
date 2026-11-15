package com.letta.mobile.data.voice.local

import com.letta.mobile.data.voice.VoiceErrorCode
import com.letta.mobile.data.voice.VoiceText
import com.letta.mobile.data.voice.engine.VoiceEarEvent
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

private fun partial(text: String) = LocalRecognitionEvent.Partial(VoiceText(text))

private fun final(text: String) = LocalRecognitionEvent.Final(VoiceText(text))

private fun failed(error: LocalRecognitionError) = LocalRecognitionEvent.Failed(error)

/** An ear under test, collected in the background, with everything it emitted. */
private class Listening(val ear: LocalVoiceEar, val events: MutableList<VoiceEarEvent>) {
    val turns: List<VoiceEarEvent.EndOfTurn> get() = events.filterIsInstance<VoiceEarEvent.EndOfTurn>()
    val partials: List<String> get() = events.filterIsInstance<VoiceEarEvent.PartialTranscript>().map { it.text.value }
}

@OptIn(ExperimentalCoroutinesApi::class)
class LocalVoiceEarTest {
    private val recognizer = FakeLocalRecognizer()
    private val fallbackEar = FallbackEar()

    private fun TestScope.listen(config: LocalEarConfig = LocalEarConfig(), fallback: Boolean = true): Listening {
        val ear = LocalVoiceEar(recognizer, if (fallback) VoiceEarFallback { fallbackEar } else null, config)
        val events = mutableListOf<VoiceEarEvent>()
        backgroundScope.launch { ear.listen().collect { events += it } }
        runCurrent()
        return Listening(ear, events)
    }

    @Test
    fun partialsStreamAsTheTurnInProgress() = runTest {
        val heard = listen()
        recognizer.current.send(LocalRecognitionEvent.Ready, partial("hel"), partial("hello"))
        runCurrent()

        assertEquals(VoiceEarEvent.ListeningStarted, heard.events[0])
        assertEquals(VoiceEarEvent.SpeechStarted, heard.events[1])
        assertEquals(listOf("hel", "hello"), heard.partials)
        assertTrue(heard.turns.isEmpty(), "nothing is an utterance before the final result")
    }

    @Test
    fun aFinalResultIsExactlyOneUtteranceAndListeningGoesOn() = runTest {
        val heard = listen()
        recognizer.current.send(
            LocalRecognitionEvent.Ready,
            partial("hello"),
            LocalRecognitionEvent.EndOfSpeech,
            final("hello there"),
        )
        runCurrent()

        assertEquals(listOf("hello there"), heard.turns.map { it.utterance.text.value })
        assertTrue(VoiceEarEvent.SpeechEnded in heard.events)
        assertEquals(2, recognizer.recognitions.size, "the recogniser listens again at once")
        assertEquals(1, recognizer.sessions.size, "on the same recogniser")
        assertEquals(1, heard.events.count { it == VoiceEarEvent.ListeningStarted })
    }

    @Test
    fun silenceYieldsNoUtteranceAndRestartsAtOnce() = runTest {
        val heard = listen()
        recognizer.current.send(LocalRecognitionEvent.Ready, failed(LocalRecognitionError.NO_MATCH))
        runCurrent()
        recognizer.current.send(LocalRecognitionEvent.Ready, failed(LocalRecognitionError.SPEECH_TIMEOUT))
        runCurrent()

        assertTrue(heard.turns.isEmpty())
        assertEquals(3, recognizer.recognitions.size)
        assertEquals(1, recognizer.sessions.size)
    }

    @Test
    fun anEmptyFinalYieldsNoUtterance() = runTest {
        val heard = listen()
        recognizer.current.send(LocalRecognitionEvent.Ready, final(""))
        runCurrent()

        assertTrue(heard.turns.isEmpty())
    }

    @Test
    fun aHeardTurnWhoseFinalIsDroppedEndsEmpty() = runTest {
        val heard = listen()
        recognizer.current.send(LocalRecognitionEvent.Ready, partial("a"), final("."))
        runCurrent()

        assertEquals(1, heard.turns.size, "the loop heard the turn start, so it hears it end")
        assertTrue(heard.turns.single().utterance.isEmpty)
    }

    @Test
    fun shortRepliesAreNotTreatedAsWhisperNoise() = runTest {
        val heard = listen()
        recognizer.current.send(LocalRecognitionEvent.Ready, final("Thank you"))
        runCurrent()

        assertEquals(listOf("Thank you"), heard.turns.map { it.utterance.text.value })
    }

    @Test
    fun aFailureBeforeReadyIsThrottled() = runTest {
        listen()
        recognizer.current.send(failed(LocalRecognitionError.NO_MATCH))
        runCurrent()
        assertEquals(1, recognizer.recognitions.size, "no tight restart loop")

        advanceTimeBy(LocalEarConfig.DEFAULT_RETRY_DELAYS.first() + 1.milliseconds)
        assertEquals(2, recognizer.recognitions.size)
        assertEquals(2, recognizer.sessions.size, "the recogniser is recreated")
        assertTrue(recognizer.sessions.first().closed)
    }

    @Test
    fun busyRecreatesTheRecogniserThenHandsOverToTheServer() = runTest {
        val heard = listen()
        repeat(LocalEarConfig.DEFAULT_RETRY_DELAYS.size + 1) {
            recognizer.current.send(LocalRecognitionEvent.Ready, failed(LocalRecognitionError.BUSY))
            advanceTimeBy(3_000.milliseconds)
        }

        assertEquals(LocalEarConfig.DEFAULT_RETRY_DELAYS.size + 1, recognizer.sessions.size)
        assertEquals(1, fallbackEar.listens)
        assertEquals(LocalHandoverReason.KEEPS_FAILING, heard.ear.handover.value)
        assertTrue(recognizer.sessions.all { it.closed })
    }

    @Test
    fun aMissingLanguageModelHandsOverAtOnce() = runTest {
        val heard = listen()
        recognizer.current.send(LocalRecognitionEvent.Ready, failed(LocalRecognitionError.LANGUAGE_UNAVAILABLE))
        runCurrent()

        assertEquals(1, fallbackEar.listens)
        assertEquals(LocalHandoverReason.LANGUAGE, heard.ear.handover.value)
        assertTrue(heard.events.none { it is VoiceEarEvent.Failed })
    }

    @Test
    fun noRecogniserHandsOverWithoutOpeningOne() = runTest {
        recognizer.availability = LocalSpeechAvailability.UNAVAILABLE
        val heard = listen()

        assertTrue(recognizer.sessions.isEmpty())
        assertEquals(1, fallbackEar.listens)
        assertEquals(LocalHandoverReason.UNAVAILABLE, heard.ear.handover.value)
    }

    @Test
    fun localOnlyReportsUnavailableAndNeverTouchesTheServer() = runTest {
        recognizer.availability = LocalSpeechAvailability.UNAVAILABLE
        val heard = listen(fallback = false)

        assertEquals(VoiceErrorCode.UNAVAILABLE, heard.events.filterIsInstance<VoiceEarEvent.Failed>().single().error.code)
        assertEquals(0, fallbackEar.listens)
        assertNull(heard.ear.handover.value)
    }

    @Test
    fun aMissingPermissionIsReportedNotHandedOver() = runTest {
        val heard = listen()
        recognizer.current.send(failed(LocalRecognitionError.PERMISSION))
        runCurrent()

        assertEquals(VoiceErrorCode.PERMISSION_REQUIRED, heard.events.filterIsInstance<VoiceEarEvent.Failed>().single().error.code)
        assertEquals(0, fallbackEar.listens)
    }

    @Test
    fun afterAHandOverTheServerEarServesEveryCall() = runTest {
        recognizer.availability = LocalSpeechAvailability.UNAVAILABLE
        val heard = listen()
        heard.ear.setOutputActive(true)
        heard.ear.commitTurn()
        heard.ear.close()

        assertTrue(fallbackEar.outputActive)
        assertEquals(1, fallbackEar.commits)
        assertTrue(fallbackEar.closed)
    }

    @Test
    fun theAppsOwnSpeechIsNeverTranscribed() = runTest {
        val heard = listen()
        val first = recognizer.current
        first.send(LocalRecognitionEvent.Ready, partial("hi"))
        runCurrent()

        heard.ear.setOutputActive(true)
        runCurrent()
        assertFalse(first.collecting, "the recognition stops as the app starts speaking")
        assertTrue(heard.turns.single().utterance.isEmpty, "the turn the loop heard start ends empty")
        assertEquals(1, recognizer.recognitions.size, "nothing listens while the app speaks")

        advanceTimeBy(5_000.milliseconds)
        assertEquals(1, recognizer.recognitions.size)
        heard.ear.setOutputActive(false)
        advanceTimeBy(LocalEarConfig().outputTail - 1.milliseconds)
        assertEquals(1, recognizer.recognitions.size, "nor through the output's tail")
        advanceTimeBy(2.milliseconds)
        assertEquals(2, recognizer.recognitions.size, "listening resumes after the tail")
    }

    @Test
    fun aResultArrivingAsTheAppStartsSpeakingIsDropped() = runTest {
        val heard = listen()
        recognizer.current.send(LocalRecognitionEvent.Ready, final("my own reply echoed"))
        heard.ear.setOutputActive(true)
        runCurrent()

        assertTrue(heard.turns.none { !it.utterance.isEmpty })
    }

    @Test
    fun outputStartingAgainDuringTheTailKeepsThePause() = runTest {
        val heard = listen()
        heard.ear.setOutputActive(true)
        runCurrent()
        heard.ear.setOutputActive(false)
        advanceTimeBy(100.milliseconds)
        heard.ear.setOutputActive(true)
        advanceTimeBy(1_000.milliseconds)

        assertEquals(1, recognizer.recognitions.size)
    }

    @Test
    fun anEchoCancelledRecogniserKeepsListeningWhileTheAppSpeaks() = runTest {
        val heard = listen(LocalEarConfig(pauseDuringOutput = false))
        recognizer.current.send(LocalRecognitionEvent.Ready)
        runCurrent()
        heard.ear.setOutputActive(true)
        runCurrent()
        recognizer.current.send(partial("stop"))
        runCurrent()

        assertTrue(recognizer.current.collecting)
        assertEquals(listOf("stop"), heard.partials)
    }

    @Test
    fun committingAsksTheRecogniserForItsResultNow() = runTest {
        val heard = listen()
        recognizer.current.send(LocalRecognitionEvent.Ready, partial("send this"))
        runCurrent()
        heard.ear.commitTurn()
        runCurrent()

        assertTrue(recognizer.current.finished)
    }

    @Test
    fun stoppingReleasesTheRecogniser() = runTest {
        val ear = LocalVoiceEar(recognizer)
        val job = backgroundScope.launch { ear.listen().collect { } }
        runCurrent()
        job.cancel()
        runCurrent()

        assertFalse(recognizer.current.collecting)
        assertTrue(recognizer.sessions.single().closed)
    }
}
