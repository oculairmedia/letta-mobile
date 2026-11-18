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

    /** One reply of [SEGMENTS] segments at [speed]; returns the worst gap seen while streaming (after the first segment) and after. */
    private fun TestScope.worstGaps(speed: SpeechSpeed): Pair<Double, Double> {
        val clock = TestTimeSource()
        val controls = VoiceSessionControls(clock, MutableStateFlow(speed)) {}
        val inner = Mouth()
        val mouth = controls.engines(Ear, inner).second
        backgroundScope.launch { mouth.events.collect {} }
        runCurrent()
        var streaming = 0.0
        var arrived = 0.0
        repeat(SEGMENTS) { number ->
            val id = SpeechSegmentId(number.toLong())
            val text = VoiceText("A".repeat(CHARACTERS))
            val totalMs = CHARACTERS * MILLIS / (VOICE_RATE * speed.value)
            mouth.speak(SpeechSegment(id, text))
            var receivedMs = 0.0
            var elapsedMs = 0L
            fun receive() {
                receivedMs = minOf(totalMs, receivedMs + CHUNK_MS)
                controls.meter.measured(id, receivedMs.toLong().milliseconds, complete = receivedMs >= totalMs)
            }
            receive()
            inner.emitted.tryEmit(VoiceMouthEvent.Started(id))
            runCurrent()
            while (elapsedMs + STEP_MS <= totalMs) {
                clock += STEP_MS.milliseconds
                elapsedMs += STEP_MS
                if (elapsedMs % ARRIVAL_EVERY_MS == 0L && receivedMs < totalMs) receive()
                val segment = controls.spokenProgress.value.last()
                val playedMs = (controls.nowMs() - segment.startedAtMs).toDouble()
                val shown = (playedMs / checkNotNull(segment.audioDurationMs)).coerceIn(0.0, 1.0)
                val gap = abs(shown - elapsedMs / totalMs)
                if (receivedMs < totalMs) {
                    if (number > 0) streaming = maxOf(streaming, gap)
                } else {
                    arrived = maxOf(arrived, gap)
                }
            }
            clock += (totalMs - elapsedMs).toLong().milliseconds
            inner.emitted.tryEmit(VoiceMouthEvent.Finished(id))
            runCurrent()
        }
        return streaming to arrived
    }

    @Test
    fun theLitShareTracksPlaybackAtNormalSpeed() = runTest { assertTracks(SpeechSpeed.Normal) }

    @Test
    fun theLitShareTracksPlaybackAtOneAndAHalfTimes() = runTest { assertTracks(SpeechSpeed(1.5f)) }

    @Test
    fun theLitShareTracksPlaybackAtThreeQuarters() = runTest { assertTracks(SpeechSpeed(0.75f)) }

    private fun TestScope.assertTracks(speed: SpeechSpeed) {
        val (streaming, arrived) = worstGaps(speed)
        assertTrue(streaming <= TOLERANCE, "at ${speed.value}x the light is off by up to $streaming while audio still arrives")
        assertTrue(arrived <= TOLERANCE, "at ${speed.value}x the light is off by up to $arrived once all audio arrived")
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
