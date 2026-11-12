package com.letta.mobile.data.voice.audio.line

import com.letta.mobile.data.voice.VoiceErrorCode
import com.letta.mobile.data.voice.audio.AudioFrameFormat
import com.letta.mobile.data.voice.audio.pcm.VoiceAudioException
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

/** How a fake microphone line behaves. */
private data class LineScript(
    /** Samples handed out per read; 0 makes every read empty. */
    val perRead: Int = 512,
    /** After this many samples the line blocks until stopped; null never blocks. */
    val blockAfter: Long? = null,
    /** After this many samples a read fails with this code. */
    val failAfter: Pair<Long, VoiceErrorCode>? = null,
)

/** A ramp-producing line: sample n has the value n (mod 2^15). */
private class FakeInputLine(private val script: LineScript) : PcmInputLine {
    private val stopped = CountDownLatch(1)
    val blocked = CountDownLatch(1)
    var served = 0L
        private set
    val events = mutableListOf<String>()

    override fun read(buffer: ShortArray): Int {
        script.failAfter?.let { (after, code) -> if (served >= after) throw VoiceAudioException(code) }
        if (script.blockAfter?.let { served >= it } == true) {
            blocked.countDown()
            stopped.await(5, TimeUnit.SECONDS)
            return 0
        }
        val count = minOf(buffer.size, script.perRead)
        for (index in 0 until count) buffer[index] = ((served + index) % Short.MAX_VALUE).toInt().toShort()
        served += count
        return count
    }

    override fun stop() {
        synchronized(events) { events += "stop" }
        stopped.countDown()
    }

    override fun release() {
        synchronized(events) { events += "release" }
    }
}

private val format = AudioFrameFormat()

private fun source(line: FakeInputLine, options: PumpOptions = PumpOptions()) = LineFrameSource({ line }, format, options)

class LineFrameSourceTest {
    @Test
    fun framesAreFixedSizeWithContiguousPositionsAndNoLostSamples() = runBlocking {
        val line = FakeInputLine(LineScript(perRead = 100, blockAfter = 10 * 512L))
        val frames = source(line).frames().take(10).toList()
        assertTrue(frames.all { it.samples.size == 512 })
        assertEquals((0 until 10).map { it * 512L }, frames.map { it.start.value })
        frames.forEach { frame ->
            frame.samples.forEachIndexed { index, sample -> assertEquals(frame.start.value + index, sample.toLong()) }
        }
    }

    @Test
    fun theLineIsStoppedAndReleasedBeforeTheCollectionCompletes() = runBlocking {
        val line = FakeInputLine(LineScript(blockAfter = 2_048))
        source(line).frames().take(4).toList()
        assertEquals(listOf("stop", "release"), line.events)
    }

    @Test
    fun aSlowCollectorLosesTheOldestFramesAndTheyAreCounted() {
        val line = FakeInputLine(LineScript(blockAfter = 10 * 512L))
        val dropped = AtomicLong()
        val pump = CapturePump(line, format, PumpOptions(capacity = 4), dropped)
        pump.start()
        assertTrue(line.blocked.await(5, TimeUnit.SECONDS))
        val kept = generateSequence { pump.frames.tryReceive().getOrNull() }.toList()
        pump.stopAndRelease()
        assertEquals(6L, dropped.get())
        assertEquals((6 until 10).map { it * 512L }, kept.map { it.start.value })
    }

    @Test
    fun aFailingLineEndsTheCollectionWithItsCode() = runBlocking {
        val line = FakeInputLine(LineScript(failAfter = 1_024L to VoiceErrorCode.UNAVAILABLE))
        val error = assertFailsWith<VoiceAudioException> { source(line).frames().toList() }
        assertEquals(VoiceErrorCode.UNAVAILABLE, error.code)
        assertEquals(listOf("stop", "release"), line.events)
    }

    @Test
    fun aLineThatGoesSilentIsTreatedAsGone() = runBlocking {
        val line = FakeInputLine(LineScript(perRead = 0))
        val options = PumpOptions(stallTimeout = 30.milliseconds, pollInterval = 1.milliseconds)
        val error = assertFailsWith<VoiceAudioException> { source(line, options).frames().toList() }
        assertEquals(VoiceErrorCode.UNAVAILABLE, error.code)
    }

    @Test
    fun aDeviceThatCannotOpenFailsTyped() = runBlocking {
        val source = LineFrameSource({ throw VoiceAudioException(VoiceErrorCode.PERMISSION_REQUIRED) }, format)
        val error = assertFailsWith<VoiceAudioException> { source.frames().toList() }
        assertEquals(VoiceErrorCode.PERMISSION_REQUIRED, error.code)
    }

    @Test
    fun aNegativeReadIsAnEngineFailure() = runBlocking {
        val line = object : PcmInputLine {
            override fun read(buffer: ShortArray): Int = -1
            override fun stop() = Unit
            override fun release() = Unit
        }
        val error = assertFailsWith<VoiceAudioException> { LineFrameSource({ line }, format).frames().toList() }
        assertEquals(VoiceErrorCode.ENGINE_FAILED, error.code)
    }
}
