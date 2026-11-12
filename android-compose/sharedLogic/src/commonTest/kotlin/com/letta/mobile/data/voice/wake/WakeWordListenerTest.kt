package com.letta.mobile.data.voice.wake

import com.letta.mobile.data.voice.VoiceError
import com.letta.mobile.data.voice.VoiceErrorCode
import com.letta.mobile.data.voice.VoicePort
import com.letta.mobile.data.voice.WakePhrase
import com.letta.mobile.data.voice.WakeSensitivity
import com.letta.mobile.data.voice.WakeWordEngineId
import com.letta.mobile.data.voice.activation.VoiceActivationEvent
import com.letta.mobile.data.voice.activation.VoiceActivationKind
import com.letta.mobile.data.voice.activation.WakeWordActivationSource
import com.letta.mobile.data.voice.audio.AudioFrameFormat
import com.letta.mobile.data.voice.wake.assets.Sha256Digest
import com.letta.mobile.data.voice.wake.assets.WakeAssetId
import com.letta.mobile.data.voice.wake.assets.WakeWordAsset
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class WakeWordListenerTest {
    private val hey = WakePhrase("hey letta")
    private val other = WakePhrase("okay computer")

    private fun engines(vararg engines: FakeWakeWordEngine): WakeWordEngines = WakeWordEngines().apply {
        engines.forEach { register(WakeWordBinding(it, FakeWakeWordAssets())) }
    }

    private fun listener(source: FakeFrameSource, engine: FakeWakeWordEngine, phrases: List<WakePhrase> = listOf(hey)) =
        WakeWordListener(source, engines(engine)).apply { setPhrases(phrases) }

    private fun hits(vararg at: Pair<Long, ScriptedHit>) = FakeEngineScript(hits = mapOf(*at))

    private fun List<WakeWordEvent>.detectedFrames(): List<Long> =
        filterIsInstance<WakeWordEvent.Detected>().map { it.hit.position.value / FakeFormat.frameSamples }

    @Test
    fun cooldownDropsHitsWithinTwoSecondsOfAnAcceptedOne() = runTest {
        val heard = ScriptedHit(hey.text)
        val engine = FakeWakeWordEngine(script = hits(0L to heard, 10L to heard, 70L to heard))
        val events = listener(FakeFrameSource(80), engine).events.toList()
        assertEquals(listOf(0L, 70L), events.detectedFrames())
        assertIs<WakeWordEvent.Stopped>(events.last())
    }

    @Test
    fun sensitivityThresholdIsPerPhrase() = runTest {
        val cases = listOf(
            WakeSensitivity(0.5f) to 0.3f to false,
            WakeSensitivity(0.5f) to 0.5f to true,
            WakeSensitivity(0.8f) to 0.3f to true,
        )
        cases.forEach { (setup, expected) ->
            val (sensitivity, confidence) = setup
            val phrase = WakePhrase(hey.text, sensitivity = sensitivity)
            val engine = FakeWakeWordEngine(script = hits(2L to ScriptedHit(hey.text, confidence)))
            val detected = listener(FakeFrameSource(4), engine, listOf(phrase)).events.toList().detectedFrames().isNotEmpty()
            assertEquals(expected, detected, "sensitivity ${sensitivity.value} confidence $confidence")
        }
    }

    @Test
    fun hitsAreSuppressedWhileTheAppSpeaksAndForTheTail() = runTest {
        val engine = FakeWakeWordEngine(
            script = hits(3L to ScriptedHit(hey.text), 10L to ScriptedHit(hey.text), 40L to ScriptedHit(hey.text)),
        )
        lateinit var wake: WakeWordListener
        val source = FakeFrameSource(45) { index ->
            if (index == 0) wake.setAppSpeaking(true)
            if (index == 5) wake.setAppSpeaking(false)
        }
        wake = listener(source, engine)
        assertEquals(listOf(40L), wake.events.toList().detectedFrames())
    }

    @Test
    fun phraseChangesApplyLiveWithoutReopening() = runTest {
        val engine = FakeWakeWordEngine(script = hits(2L to ScriptedHit(other.text), 8L to ScriptedHit(other.text)))
        lateinit var wake: WakeWordListener
        val source = FakeFrameSource(10) { index -> if (index == 5) wake.setPhrases(listOf(other)) }
        wake = listener(source, engine)
        val events = wake.events.toList()
        assertEquals(listOf(8L), events.detectedFrames())
        assertEquals(2, events.filterIsInstance<WakeWordEvent.PhrasesApplied>().size)
        assertEquals(1, engine.sessions.size)
    }

    @Test
    fun missingAssetsAreUnavailableAndNeverOpenTheMicrophone() = runTest {
        val asset = WakeWordAsset(WakeAssetId("model.onnx"), 10, Sha256Digest("a".repeat(64)))
        val source = FakeFrameSource(5)
        val wake = listener(source, FakeWakeWordEngine(required = listOf(asset)))
        val event = wake.events.toList().single()
        assertIs<WakeWordEvent.Unavailable>(event)
        assertEquals(VoiceErrorCode.ASSET_MISSING, event.error.code)
        assertEquals(0, source.opens)
        assertEquals(WakeListenerState.UNAVAILABLE, wake.state.value)
    }

    @Test
    fun noEngineAndWrongFormatAreUnavailable() = runTest {
        val none = WakeWordListener(FakeFrameSource(1), WakeWordEngines()).events.toList().single()
        assertEquals(VoiceErrorCode.UNAVAILABLE, (none as WakeWordEvent.Unavailable).error.code)
        val source = FakeFrameSource(1, format = AudioFrameFormat(sampleRateHz = 48_000))
        val engine = FakeWakeWordEngine()
        val mismatch = listener(source, engine).events.toList().single()
        assertEquals(VoiceErrorCode.UNSUPPORTED_FORMAT, (mismatch as WakeWordEvent.Unavailable).error.code)
        assertEquals(0, source.opens)
        assertEquals(1, engine.sessions.single().closes)
    }

    @Test
    fun selectionFallsBackAndReportsWhyEnginesWereSkipped() = runTest {
        val preferred = FakeWakeWordEngine("alkove", FakeEngineScript(openFailure = WakeWordOpenFailure.UNSUPPORTED_PLATFORM))
        val fallback = FakeWakeWordEngine("vad")
        val selection = engines(fallback, preferred).open(WakeWordEngineId("alkove"))
        assertIs<WakeWordSelection.Opened>(selection)
        assertEquals(WakeWordEngineId("vad"), selection.binding.engine.descriptor.id)
        assertEquals(listOf(SkippedEngine(WakeWordEngineId("alkove"), WakeWordOpenFailure.UNSUPPORTED_PLATFORM)), selection.skipped)
    }

    @Test
    fun stoppingReleasesTheSourceAndClosesTheSession() = runTest {
        val source = FakeFrameSource(2, hold = true)
        val engine = FakeWakeWordEngine()
        val wake = listener(source, engine)
        val job = launch { wake.events.collect { } }
        runCurrent()
        assertEquals(WakeListenerState.LISTENING, wake.state.value)
        job.cancelAndJoin()
        assertTrue(source.released)
        assertEquals(1, engine.sessions.single().closes)
        assertEquals(WakeListenerState.STOPPED, wake.state.value)
    }

    @Test
    fun engineFailureMidStreamIsUnavailableWithTheSourceReleased() = runTest {
        val source = FakeFrameSource(10)
        val engine = FakeWakeWordEngine(script = FakeEngineScript(failAtFrame = 3))
        val last = listener(source, engine).events.toList().last()
        assertEquals(VoiceErrorCode.ENGINE_FAILED, (last as WakeWordEvent.Unavailable).error.code)
        assertTrue(source.released)
        assertEquals(1, engine.sessions.single().closes)
    }

    @Test
    fun activationSourceWrapsTheListener() = runTest {
        val engine = FakeWakeWordEngine(script = hits(1L to ScriptedHit(hey.text)))
        val activations = WakeWordActivationSource(listener(FakeFrameSource(3), engine)).events.toList()
        val endedError = VoiceError(VoiceErrorCode.UNAVAILABLE, VoicePort.ACTIVATION)
        val ended = VoiceActivationEvent.Degraded(VoiceActivationKind.WAKE_WORD, endedError)
        assertEquals(listOf(VoiceActivationEvent.Activated(VoiceActivationKind.WAKE_WORD), ended), activations)
        val missing = WakeWordActivationSource(WakeWordListener(FakeFrameSource(1), WakeWordEngines())).events.toList()
        assertIs<VoiceActivationEvent.Degraded>(missing.single())
    }

    @Test
    fun secondConcurrentCollectionIsBusyAndLeavesTheFirstRunning() = runTest {
        val source = FakeFrameSource(2, hold = true)
        val wake = listener(source, FakeWakeWordEngine())
        val first = launch { wake.events.collect { } }
        runCurrent()
        val second = wake.events.toList().single()
        assertEquals(VoiceErrorCode.BUSY, (second as WakeWordEvent.Unavailable).error.code)
        assertEquals(WakeListenerState.LISTENING, wake.state.value)
        assertEquals(1, source.opens)
        first.cancelAndJoin()
    }

    @Test
    fun cooldownStartsOnlyAfterTheConfirmerAccepts() = runTest {
        val heard = ScriptedHit(hey.text)
        val engine = FakeWakeWordEngine(script = hits(0L to heard, 10L to heard))
        val confirmer = WakeHitConfirmer { hit -> hit.position.value > 0 }
        val config = WakeWordListenerConfig(policy = WakeWordPolicy(confirmer = confirmer))
        val wake = WakeWordListener(FakeFrameSource(12), engines(engine), config).apply { setPhrases(listOf(hey)) }
        assertEquals(listOf(10L), wake.events.toList().detectedFrames())
    }

    @Test
    fun phraseListIsCopiedSoAMutatedListStillApplies() = runTest {
        val engine = FakeWakeWordEngine(script = hits(8L to ScriptedHit(other.text)))
        val phrases = mutableListOf(hey)
        lateinit var wake: WakeWordListener
        val source = FakeFrameSource(10) { index ->
            if (index == 5) {
                phrases[0] = other
                wake.setPhrases(phrases)
            }
        }
        wake = listener(source, engine, phrases)
        assertEquals(listOf(8L), wake.events.toList().detectedFrames())
    }

    @Test
    fun speechStartAndStopBetweenFramesStillStartsTheTail() = runTest {
        val heard = ScriptedHit(hey.text)
        val engine = FakeWakeWordEngine(script = hits(5L to heard, 40L to heard))
        lateinit var wake: WakeWordListener
        val source = FakeFrameSource(45) { index ->
            if (index == 3) {
                wake.setAppSpeaking(true)
                wake.setAppSpeaking(false)
            }
        }
        wake = listener(source, engine)
        assertEquals(listOf(40L), wake.events.toList().detectedFrames())
    }

    @Test
    fun listeningIsReportedOnlyOnceFramesArrive() = runTest {
        val wake = listener(FakeFrameSource(0, hold = true), FakeWakeWordEngine())
        val events = mutableListOf<WakeWordEvent>()
        val job = launch { wake.events.collect { events += it } }
        runCurrent()
        assertEquals(WakeListenerState.STOPPED, wake.state.value)
        assertTrue(events.none { it is WakeWordEvent.Listening })
        job.cancelAndJoin()
    }

    @Test
    fun calibrationRecordingOwnsItsFrames() {
        val frame = frameAt(0)
        val recording = CalibrationRecording(listOf(frame))
        frame.samples[0] = 42
        assertEquals(0, recording.frames.single().samples[0].toInt())
    }
}
