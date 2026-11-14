package com.letta.mobile.data.voice.audio.device

import com.letta.mobile.data.voice.VoiceErrorCode
import com.letta.mobile.data.voice.audio.AudioCapture
import com.letta.mobile.data.voice.audio.AudioFrame
import com.letta.mobile.data.voice.audio.AudioFrameFormat
import com.letta.mobile.data.voice.audio.AudioFrameSource
import com.letta.mobile.data.voice.audio.PcmFormat
import com.letta.mobile.data.voice.audio.SamplePosition
import com.letta.mobile.data.voice.audio.pcm.VoiceAudioException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** One open of a [FlakySource]: [frames] frames, then [failure] (null holds open). */
private data class Run(val frames: Int, val failure: VoiceErrorCode? = VoiceErrorCode.UNAVAILABLE)

/** A source whose n-th open plays [runs][n] (the last run repeats); positions restart at 0 each open. */
private class FlakySource(private val runs: List<Run>) : AudioFrameSource {
    override val format = AudioFrameFormat(frameSamples = 4)
    var opens = 0
        private set

    override fun frames(): Flow<AudioFrame> = flow {
        val run = runs[minOf(opens, runs.size - 1)]
        opens++
        repeat(run.frames) { emit(AudioFrame(ShortArray(4) { opens.toShort() }, SamplePosition(it * 4L))) }
        run.failure?.let { throw VoiceAudioException(it) } ?: awaitCancellation()
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class RecoveringFrameSourceTest {
    private val policy = CaptureRecoveryPolicy(maxAttempts = 3, initialBackoff = 250.milliseconds, maxBackoff = 1.seconds)

    private suspend fun failureOf(source: FlakySource, changes: Flow<Unit> = MutableSharedFlow()): VoiceAudioException =
        assertFailsWith { RecoveringFrameSource(source, policy, changes).frames().toList() }

    @Test
    fun backoffDoublesUpToTheCap() {
        assertEquals(listOf(250, 500, 1_000, 1_000).map { it.milliseconds }, (1..4).map { policy.backoff(it) })
    }

    @Test
    fun aLostDeviceIsReopenedWithBackoffAndPositionsContinue() = runTest {
        val source = FlakySource(listOf(Run(2), Run(0), Run(2, failure = null)))
        val frames = RecoveringFrameSource(source, policy).frames().take(4).toList()
        assertEquals(listOf(0L, 4L, 8L, 12L), frames.map { it.start.value })
        assertEquals(listOf(1, 1, 3, 3), frames.map { it.samples[0].toInt() })
        assertEquals(250L + 500L, currentTime)
    }

    @Test
    fun aDeliveredFrameResetsTheAttemptCount() = runTest {
        val source = FlakySource(listOf(Run(1), Run(1), Run(1), Run(1), Run(1, failure = null)))
        val frames = RecoveringFrameSource(source, policy).frames().take(5).toList()
        assertEquals(5, frames.size)
        assertEquals(4 * 250L, currentTime)
    }

    @Test
    fun missingPermissionAndWrongFormatAreNeverRetried() = runTest {
        listOf(VoiceErrorCode.PERMISSION_REQUIRED, VoiceErrorCode.UNSUPPORTED_FORMAT).forEach { code ->
            val source = FlakySource(listOf(Run(0, code)))
            assertEquals(code, failureOf(source).code)
            assertEquals(1, source.opens)
        }
        assertEquals(0L, currentTime)
    }

    @Test
    fun attemptsAreBounded() = runTest {
        val source = FlakySource(listOf(Run(0)))
        assertEquals(VoiceErrorCode.UNAVAILABLE, failureOf(source).code)
        assertEquals(4, source.opens)
        assertEquals(250L + 500L + 1_000L, currentTime)
    }

    @Test
    fun aSourceEndingOnItsOwnIsRetriedAsAnEngineFailure() = runTest {
        val source = object : AudioFrameSource {
            override val format = AudioFrameFormat()
            override fun frames(): Flow<AudioFrame> = flow { }
        }
        val failure = assertFailsWith<VoiceAudioException> { RecoveringFrameSource(source, policy).frames().toList() }
        assertEquals(VoiceErrorCode.ENGINE_FAILED, failure.code)
    }

    @Test
    fun aDeviceChangeEndsTheWaitAfterItsDebounce() = runTest {
        val slow = policy.copy(initialBackoff = 10.seconds, maxBackoff = 10.seconds)
        val changes = MutableSharedFlow<Unit>()
        val source = FlakySource(listOf(Run(0), Run(1, failure = null)))
        val frame = backgroundScope.launch { RecoveringFrameSource(source, slow, changes).frames().first() }
        advanceTimeBy(100)
        changes.emit(Unit)
        advanceTimeBy(300)
        changes.emit(Unit)
        frame.join()
        assertEquals(400L + 500L, currentTime)
    }

    @Test
    fun aFailingCollectorIsNotRetried() = runTest {
        val source = FlakySource(listOf(Run(3, failure = null)))
        assertFailsWith<IllegalStateException> {
            RecoveringFrameSource(source, policy).frames().collect { error("collector") }
        }
        assertEquals(1, source.opens)
    }

    @Test
    fun captureTurnsFramesIntoLittleEndianChunks() = runTest {
        val capture: AudioCapture = FrameAudioCapture({ asked ->
            object : AudioFrameSource {
                override val format = asked
                override fun frames(): Flow<AudioFrame> = flow { emit(AudioFrame(shortArrayOf(0x0102, -2), SamplePosition(0))) }
            }
        })
        val chunk = capture.capture(PcmFormat()).first()
        assertContentEquals(byteArrayOf(2, 1, -2, -1), chunk.bytes)
    }
}
