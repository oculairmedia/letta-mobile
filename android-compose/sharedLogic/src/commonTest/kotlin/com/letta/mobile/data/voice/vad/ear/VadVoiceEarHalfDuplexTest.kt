package com.letta.mobile.data.voice.vad.ear

import com.letta.mobile.data.voice.AudioLevel
import com.letta.mobile.data.voice.VoiceText
import com.letta.mobile.data.voice.VoiceUtterance
import com.letta.mobile.data.voice.audio.AudioFrame
import com.letta.mobile.data.voice.audio.AudioFrameFormat
import com.letta.mobile.data.voice.audio.AudioFrameSource
import com.letta.mobile.data.voice.audio.SamplePosition
import com.letta.mobile.data.voice.engine.VoiceEarEvent
import com.letta.mobile.data.voice.vad.EndOfTurnPolicy
import com.letta.mobile.data.voice.vad.SpeechDetectorFactory
import com.letta.mobile.data.voice.vad.TurnDiscardReason
import com.letta.mobile.data.voice.vad.TurnEndReason
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** A live-microphone stand-in: frames arrive when the test pushes them, at running sample positions. */
private class PushedSource : AudioFrameSource {
    override val format: AudioFrameFormat = AudioFrameFormat()
    private val pending = Channel<ShortArray>(Channel.UNLIMITED)

    fun push(frames: List<ShortArray>) = frames.forEach { pending.trySend(it) }

    override fun frames(): Flow<AudioFrame> = flow {
        var position = 0L
        for (samples in pending) {
            emit(AudioFrame(samples, SamplePosition(position)))
            position += samples.size
        }
    }
}

private class SeenObserver : EarObserver {
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
        seen += "accepted"
    }
}

/** The app's own speech as the microphone hears it: loud enough to pass any threshold (0.95). */
private const val ECHO_VALUE = 950

/** The output tail in 32 ms frames: 384 ms. */
private const val TAIL_FRAMES = 12

/** One listen of a half-duplex (or, with [halfDuplex] false, echo-guarded) ear over a pushed source. */
@OptIn(ExperimentalCoroutinesApi::class)
private class HalfDuplexRig(private val test: TestScope, halfDuplex: Boolean = true, outcomes: List<Outcome> = emptyList()) {
    val source = PushedSource()
    val transcriber = ScriptedTranscriber(*outcomes.toTypedArray())
    val observer = SeenObserver()
    val ear = VadVoiceEar(
        source,
        transcriber,
        VadEarConfig(
            policy = EndOfTurnPolicy(),
            detectors = SpeechDetectorFactory { ValueDetector() },
            observer = observer,
            halfDuplex = halfDuplex,
        ),
    )
    val events = mutableListOf<VoiceEarEvent>()
    val levels = mutableListOf<AudioLevel>()
    private val job: Job = test.backgroundScope.launch {
        ear.listen().collect { event ->
            if (event is VoiceEarEvent.InputLevel) levels += event.level else events += event
        }
    }

    fun hear(frames: List<ShortArray>) {
        source.push(frames)
        test.runCurrent()
    }

    suspend fun close() = job.cancelAndJoin()
}

private fun nothingSaid() = VoiceEarEvent.EndOfTurn(VoiceUtterance(VoiceText.Empty))

@OptIn(ExperimentalCoroutinesApi::class)
class VadVoiceEarHalfDuplexTest {
    @Test
    fun loudInputDuringPlaybackAndItsTailOpensNoTurnAndIsNeverTranscribed() = runTest {
        val rig = HalfDuplexRig(this)
        rig.hear(frames(0, 4))
        rig.ear.setOutputActive(true)
        rig.hear(frames(ECHO_VALUE, 40))
        rig.ear.setOutputActive(false)
        rig.levels.clear()
        rig.hear(frames(ECHO_VALUE, TAIL_FRAMES))
        assertTrue(rig.levels.all { it == AudioLevel.Silent }, "the tail is ignored, levels included")
        rig.hear(frames(0, 40))
        assertEquals(listOf<VoiceEarEvent>(VoiceEarEvent.ListeningStarted), rig.events)
        assertTrue(rig.transcriber.clips.isEmpty())
        assertTrue(rig.observer.seen.isEmpty(), "nothing was even a candidate: ${rig.observer.seen}")
        rig.close()
    }

    @Test
    fun speechAfterTheTailOpensATurnNormallyWithNoEchoInIt() = runTest {
        val rig = HalfDuplexRig(this, outcomes = listOf(heard("hello")))
        rig.hear(frames(0, 4))
        rig.ear.setOutputActive(true)
        rig.hear(frames(ECHO_VALUE, 40))
        rig.ear.setOutputActive(false)
        rig.hear(frames(ECHO_VALUE, TAIL_FRAMES) + frames(0, 8))
        rig.hear(turn())
        assertEquals(
            listOf(
                VoiceEarEvent.ListeningStarted,
                VoiceEarEvent.SpeechStarted,
                VoiceEarEvent.SpeechEnded,
                VoiceEarEvent.EndOfTurn(VoiceUtterance(VoiceText("hello"))),
            ),
            rig.events,
        )
        assertTrue(rig.transcriber.clips.single().samples.none { it.toInt() == ECHO_VALUE })
        rig.close()
    }

    @Test
    fun aTurnOpenWhenOutputStartsIsDroppedAndEndsEmpty() = runTest {
        val rig = HalfDuplexRig(this)
        rig.hear(openTurn())
        assertEquals(VoiceEarEvent.SpeechStarted, rig.events.last())
        rig.ear.setOutputActive(true)
        rig.hear(frames(ECHO_VALUE, 20))
        assertEquals(nothingSaid(), rig.events.last())
        assertTrue("discarded:OUTPUT_ACTIVE" in rig.observer.seen)
        assertTrue(rig.transcriber.clips.isEmpty())
        rig.close()
    }

    @Test
    fun aCapturedTurnOverlappingOutputIsNeverHandedToTranscription() = runTest {
        // Output starts between two frames and the turn is committed before the ear sees another
        // hop: the guard at the hand-off drops it.
        val rig = HalfDuplexRig(this, outcomes = listOf(heard("my own echo")))
        rig.hear(openTurn())
        rig.ear.setOutputActive(true)
        rig.ear.commitTurn()
        runCurrent()
        assertEquals(listOf(VoiceEarEvent.SpeechEnded, nothingSaid()), rig.events.takeLast(2))
        assertEquals(listOf("opened", "closed:COMMITTED", "discarded:OUTPUT_ACTIVE"), rig.observer.seen)
        assertTrue(rig.transcriber.clips.isEmpty())
        rig.close()
    }

    @Test
    fun withInterruptingBySpeakingOnLoudSpeechOverTheOutputStillBargesIn() = runTest {
        val rig = HalfDuplexRig(this, halfDuplex = false)
        rig.hear(frames(0, 4))
        rig.ear.setOutputActive(true)
        rig.hear(frames(PRE_ROLL_VALUE, 4) + frames(ECHO_VALUE, 14))
        assertEquals(VoiceEarEvent.SpeechStarted, rig.events.last())
        rig.close()
    }
}
