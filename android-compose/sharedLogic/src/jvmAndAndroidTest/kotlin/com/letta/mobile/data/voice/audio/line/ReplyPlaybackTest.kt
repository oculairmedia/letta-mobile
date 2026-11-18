package com.letta.mobile.data.voice.audio.line

import com.letta.mobile.data.voice.audio.PcmChunk
import com.letta.mobile.data.voice.audio.PcmFormat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import java.util.Collections
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** A speaker that plays everything written at once, and logs opens, aborts and releases. */
private class InstantSpeaker : PcmOutputDevice {
    val log = Collections.synchronizedList(mutableListOf<String>())
    val written = Collections.synchronizedList(mutableListOf<Short>())

    /** When set, the line takes audio but never plays it (a stuck device). */
    @Volatile
    var stuck = false

    override fun open(format: PcmFormat): PcmOutputLine {
        val number = log.count { it.startsWith("open") } + 1
        log += "open$number@${format.sampleRateHz}"
        val played = AtomicLong()
        return object : PcmOutputLine {
            override val format = format

            override fun write(samples: ShortArray, count: Int): Int {
                for (index in 0 until count) written += samples[index]
                if (!stuck) played.addAndGet(count.toLong())
                return count
            }

            override fun playedFrames(): Long = played.get()

            override fun abort() {
                log += "abort$number"
            }

            override fun release() {
                log += "release$number"
            }
        }
    }
}

private val fast = PlayerOptions(stallTimeout = 50.milliseconds, drainPoll = 1.milliseconds, dispatcher = Dispatchers.IO)

private fun stream(samples: Int, value: Short = 1_000, rate: Int = 24_000): Flow<PcmChunk> =
    flowOf(PcmChunk(ShortArray(samples) { value }, PcmFormat(rate)))

@OptIn(ExperimentalCoroutinesApi::class)
class ReplyPlaybackTest {
    private val timers = TestScope()

    @Test
    fun aReplysSegmentsShareOneLineThatClosesAfterTheIdle() = runBlocking {
        val speaker = InstantSpeaker()
        val player = LinePcmPlayer(HeldOutputDevice(speaker, IDLE, timers), fast)
        repeat(3) { player.play(stream(480)) {} }
        assertEquals(listOf("open1@24000"), speaker.log, "three segments, one line, nothing released between them")
        assertEquals(3 * 480, speaker.written.size)
        timers.advanceTimeBy(IDLE / 2)
        timers.runCurrent()
        assertEquals(listOf("open1@24000"), speaker.log, "still parked within the idle")
        timers.advanceTimeBy(IDLE)
        timers.runCurrent()
        assertEquals(listOf("open1@24000", "release1"), speaker.log, "released once the idle passed")
        player.play(stream(480)) {}
        assertEquals("open2@24000", speaker.log.last(), "the next reply opens a fresh line")
    }

    @Test
    fun aSegmentStartingWithinTheIdleKeepsTheLine() = runBlocking {
        val speaker = InstantSpeaker()
        val player = LinePcmPlayer(HeldOutputDevice(speaker, IDLE, timers), fast)
        player.play(stream(480)) {}
        timers.advanceTimeBy(IDLE / 2)
        player.play(stream(480)) {}
        timers.advanceTimeBy(IDLE * 3 / 4)
        timers.runCurrent()
        assertEquals(listOf("open1@24000"), speaker.log, "the second segment restarted the idle")
    }

    @Test
    fun anInterruptAbortsAndReleasesTheLineAtOnce() = runBlocking {
        val speaker = InstantSpeaker()
        val player = LinePcmPlayer(HeldOutputDevice(speaker, IDLE, timers), fast)
        player.play(stream(480)) {}
        val endless = flow {
            emit(PcmChunk(ShortArray(480) { 1_000 }, PcmFormat(24_000)))
            awaitCancellation()
        }
        val playing = launch(Dispatchers.IO) { player.play(endless) {} }
        while (speaker.written.size < 960) Thread.sleep(1)
        playing.cancelAndJoin()
        assertEquals(listOf("open1@24000", "abort1", "release1"), speaker.log, "the reused line is aborted and released, not parked")
        player.play(stream(480)) {}
        assertEquals("open2@24000", speaker.log.last())
    }

    @Test
    fun anotherRateOpensANewLine() = runBlocking {
        val speaker = InstantSpeaker()
        val player = LinePcmPlayer(HeldOutputDevice(speaker, IDLE, timers), fast)
        player.play(stream(480)) {}
        player.play(stream(320, rate = 16_000)) {}
        assertEquals(listOf("open1@24000", "release1", "open2@16000"), speaker.log)
    }

    @Test
    fun aLineThatFailedIsNeverReused() = runBlocking {
        val speaker = InstantSpeaker()
        val player = LinePcmPlayer(HeldOutputDevice(speaker, IDLE, timers), fast)
        speaker.stuck = true
        runCatching { player.play(stream(480)) {} }
        speaker.stuck = false
        player.play(stream(480)) {}
        assertEquals("open2@24000", speaker.log.last(), "the failed line was not taken back")
        assertTrue("release1" in speaker.log, "the failed line was released, not parked")
    }

    @Test
    fun aMutedPlayerWritesSilenceAtTheSamePaceAndReportsSilentLevels() = runBlocking {
        val speaker = InstantSpeaker()
        val player = LinePcmPlayer(speaker, fast)
        val levels = mutableListOf<Float>()
        player.setMuted(true)
        player.play(stream(4_800)) { levels += it.value }
        assertEquals(4_800, speaker.written.size, "every frame is still written, so timing holds")
        assertTrue(speaker.written.all { it == 0.toShort() }, "but as silence")
        assertTrue(levels.isNotEmpty() && levels.all { it == 0f })
        player.setMuted(false)
        player.play(stream(480)) {}
        assertTrue(speaker.written.takeLast(480).all { it == 1_000.toShort() }, "unmuted plays the audio again")
    }

    private companion object {
        val IDLE = 2.seconds
    }
}
