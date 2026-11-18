package com.letta.mobile.data.voice.output

import com.letta.mobile.data.voice.VoiceText
import com.letta.mobile.data.voice.endpoint.SpeechSpeed
import com.letta.mobile.data.voice.engine.SpeechSegment
import com.letta.mobile.data.voice.engine.SpeechSegmentId
import com.letta.mobile.data.voice.engine.VoiceEarEvent
import com.letta.mobile.data.voice.engine.VoiceEar
import com.letta.mobile.data.voice.engine.VoiceMouth
import com.letta.mobile.data.voice.engine.VoiceMouthEvent
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TestTimeSource

/**
 * The share of a segment the card lights, `(now - startedAtMs) / audioDurationMs`, against where
 * playback really is, at several speeds. The fake voice speaks 18 characters a second at normal
 * speed (not the tracker's starting guess of 15) and the server time-compresses its audio by the
 * speed; audio streams in at twice real time, 200 ms of it every 100 ms, on a fake clock.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SpokenProgressSpeedTest {
    private class Mouth : VoiceMouth {
        val emitted = MutableSharedFlow<VoiceMouthEvent>(extraBufferCapacity = 16)

        override val events: Flow<VoiceMouthEvent> = emitted

        override fun speak(segment: SpeechSegment) = Unit

        override fun flush() = Unit

        override suspend fun stop() = Unit
    }

    private object Ear : VoiceEar {
        override fun listen(): Flow<VoiceEarEvent> = emptyFlow()

        override fun commitTurn() = Unit
    }

    /** The worst gap between the lit share and playback, while audio still streams and once it has all arrived. */
    private class Gaps(var streaming: Double = 0.0, var arrived: Double = 0.0)

    /** Plays one reply of [SEGMENTS] segments at [speed] through the controls on a fake clock. */
    private class Reply(private val speed: SpeechSpeed, private val clock: TestTimeSource) {
        val controls = VoiceSessionControls(clock, MutableStateFlow(speed)) {}
        val inner = Mouth()
        val mouth = controls.engines(Ear, inner).second
        val gaps = Gaps()

        /** Segment [number] from its first audio to its end. */
        fun play(number: Int, settle: () -> Unit) {
            val stream = Stream(SpeechSegmentId(number.toLong()), CHARACTERS * MILLIS / (VOICE_RATE * speed.value))
            mouth.speak(SpeechSegment(stream.id, VoiceText("A".repeat(CHARACTERS))))
            stream.receive(controls)
            inner.emitted.tryEmit(VoiceMouthEvent.Started(stream.id))
            settle()
            var elapsedMs = 0L
            while (elapsedMs + STEP_MS <= stream.totalMs) {
                clock += STEP_MS.milliseconds
                elapsedMs += STEP_MS
                if (elapsedMs % ARRIVAL_EVERY_MS == 0L) stream.receive(controls)
                record(number, stream, gap(elapsedMs / stream.totalMs))
            }
            clock += (stream.totalMs - elapsedMs).toLong().milliseconds
            inner.emitted.tryEmit(VoiceMouthEvent.Finished(stream.id))
            settle()
        }

        private fun gap(played: Double): Double {
            val segment = controls.spokenProgress.value.last()
            val playedMs = (controls.nowMs() - segment.startedAtMs).toDouble()
            val shown = (playedMs / checkNotNull(segment.audioDurationMs)).coerceIn(0.0, 1.0)
            return abs(shown - played)
        }

        /** The first segment has nothing to learn the voice's rate from while it streams: it is not judged then. */
        private fun record(number: Int, stream: Stream, gap: Double) {
            when {
                stream.complete -> gaps.arrived = maxOf(gaps.arrived, gap)
                number > 0 -> gaps.streaming = maxOf(gaps.streaming, gap)
            }
        }
    }

    /** One segment's audio arriving: [CHUNK_MS] at a time, [totalMs] in all. */
    private class Stream(val id: SpeechSegmentId, val totalMs: Double) {
        private var receivedMs = 0.0
        val complete: Boolean get() = receivedMs >= totalMs

        fun receive(controls: VoiceSessionControls) {
            if (complete) return
            receivedMs = minOf(totalMs, receivedMs + CHUNK_MS)
            controls.meter.measured(id, receivedMs.toLong().milliseconds, complete = complete)
        }
    }

    private fun TestScope.worstGaps(speed: SpeechSpeed): Gaps {
        val reply = Reply(speed, TestTimeSource())
        backgroundScope.launch { reply.mouth.events.collect {} }
        runCurrent()
        repeat(SEGMENTS) { reply.play(it) { runCurrent() } }
        return reply.gaps
    }

    @Test
    fun theLitShareTracksPlaybackAtNormalSpeed() = runTest { assertTracks(SpeechSpeed.Normal) }

    @Test
    fun theLitShareTracksPlaybackAtOneAndAHalfTimes() = runTest { assertTracks(SpeechSpeed(1.5f)) }

    @Test
    fun theLitShareTracksPlaybackAtThreeQuarters() = runTest { assertTracks(SpeechSpeed(0.75f)) }

    private fun TestScope.assertTracks(speed: SpeechSpeed) {
        val gaps = worstGaps(speed)
        assertTrue(gaps.streaming <= TOLERANCE, "at ${speed.value}x the light is off by up to ${gaps.streaming} while audio still arrives")
        assertTrue(gaps.arrived <= TOLERANCE, "at ${speed.value}x the light is off by up to ${gaps.arrived} once all audio arrived")
    }

    private companion object {
        const val SEGMENTS = 3
        const val CHARACTERS = 90
        const val VOICE_RATE = 18.0
        const val MILLIS = 1_000.0
        const val CHUNK_MS = 200.0
        const val ARRIVAL_EVERY_MS = 100L
        const val STEP_MS = 20L
        const val TOLERANCE = 0.03
    }
}
