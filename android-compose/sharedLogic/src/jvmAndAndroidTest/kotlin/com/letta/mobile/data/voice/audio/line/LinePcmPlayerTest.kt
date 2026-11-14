package com.letta.mobile.data.voice.audio.line

import com.letta.mobile.data.voice.AudioLevel
import com.letta.mobile.data.voice.VoiceErrorCode
import com.letta.mobile.data.voice.audio.AudioClip
import com.letta.mobile.data.voice.audio.AudioEncoding
import com.letta.mobile.data.voice.audio.PcmFormat
import com.letta.mobile.data.voice.audio.pcm.PcmAudio
import com.letta.mobile.data.voice.audio.pcm.VoiceAudioException
import com.letta.mobile.data.voice.audio.pcm.WavCodec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.measureTime

/**
 * How a fake speaker behaves: its rate, whether writes block until abort (all of them, or every one
 * after the first, like a device that stops consuming), whether endOfStream blocks until abort,
 * whether it never plays, and whether each write takes 30 ms while playing its audio as it goes.
 */
private data class SpeakerScript(
    val rateHz: Int? = null,
    val blockWrites: Boolean = false,
    val stuck: Boolean = false,
    val jamAfterFirstWrite: Boolean = false,
    val blockEndOfStream: Boolean = false,
    val slowButPlaying: Boolean = false,
)

private class FakeSpeaker(private val script: SpeakerScript) : PcmOutputDevice {
    val written = Collections.synchronizedList(mutableListOf<Short>())
    val events = Collections.synchronizedList(mutableListOf<String>())
    val writing = CountDownLatch(1)
    val draining = CountDownLatch(1)
    private val aborted = CountDownLatch(1)
    private val playedSoFar = AtomicLong()

    /** Writes that queued audio after the line was aborted; must stay 0. */
    @Volatile
    var writesAfterAbort = 0
    var opens = 0
        private set

    override fun open(format: PcmFormat): PcmOutputLine {
        opens++
        val opened = PcmFormat(script.rateHz ?: format.sampleRateHz)
        return object : PcmOutputLine {
            override val format = opened

            override fun write(samples: ShortArray, count: Int): Int {
                if (aborted.count == 0L) writesAfterAbort++
                writing.countDown()
                val jammed = script.jamAfterFirstWrite && written.isNotEmpty()
                if (script.blockWrites || jammed) return if (aborted.await(5, TimeUnit.SECONDS)) 0 else count
                for (index in 0 until count) written += samples[index]
                if (script.slowButPlaying) playSlowly(count)
                return count
            }

            /** Three 10 ms steps, each playing a third of [count]: blocked for 30 ms but always moving. */
            private fun playSlowly(count: Int) {
                repeat(3) { step ->
                    Thread.sleep(10)
                    playedSoFar.addAndGet(if (step < 2) count / 3L else count - 2 * (count / 3L))
                }
            }

            override fun playedFrames(): Long = when {
                script.stuck -> 0
                script.slowButPlaying -> playedSoFar.get()
                else -> written.size.toLong()
            }

            override fun endOfStream() {
                draining.countDown()
                if (script.blockEndOfStream) aborted.await(5, TimeUnit.SECONDS)
            }

            override fun abort() {
                events += "abort"
                aborted.countDown()
            }

            override fun release() {
                events += "release"
            }
        }
    }
}

private val options = PlayerOptions(stallTimeout = 50.milliseconds, drainPoll = 1.milliseconds, dispatcher = Dispatchers.IO)

private fun wavClip(samples: Int, rate: Int = 24_000) =
    AudioClip(WavCodec.encode(PcmAudio(ShortArray(samples) { 1_000 }, PcmFormat(rate))), AudioEncoding.WAV)

/**
 * A clip shaped like the local Qwen3-TTS server's streamed answer: 24 kHz mono 16-bit PCM WAVE
 * whose RIFF and data sizes are the streaming placeholder 0xFFFFFFFF.
 */
private fun streamedWavClip(samples: ShortArray): AudioClip {
    val canonical = WavCodec.encode(PcmAudio(samples, PcmFormat(24_000)))
    for (sizeField in listOf(4, 40)) for (byte in 0 until 4) canonical[sizeField + byte] = 0xFF.toByte()
    return AudioClip(canonical, AudioEncoding.WAV)
}

class LinePcmPlayerTest {
    @Test
    fun aStreamedWaveWithPlaceholderSizesIsPlayedWholeAtItsRate() = runBlocking {
        val speaker = FakeSpeaker(SpeakerScript())
        val samples = ShortArray(7_200) { (it % 200 - 100).toShort() }
        var opened: PcmFormat? = null
        val recording = object : PcmOutputDevice {
            override fun open(format: PcmFormat): PcmOutputLine = speaker.open(format).also { opened = format }
        }
        LinePcmPlayer(recording, options).play(streamedWavClip(samples)) {}
        assertEquals(PcmFormat(24_000), opened)
        assertEquals(samples.toList(), speaker.written.toList())
    }

    @Test
    fun aWaveClipIsWrittenWholeWithOneLevelPerChunk() = runBlocking {
        val speaker = FakeSpeaker(SpeakerScript())
        val levels = mutableListOf<AudioLevel>()
        LinePcmPlayer(speaker, options).play(wavClip(2_400)) { levels += it }
        assertEquals(2_400, speaker.written.size)
        assertEquals(5, levels.size)
        assertEquals(listOf("release"), speaker.events)
    }

    @Test
    fun aLineAtAnotherRateGetsResampledAudio() = runBlocking {
        val speaker = FakeSpeaker(SpeakerScript(rateHz = 48_000))
        LinePcmPlayer(speaker, options).play(wavClip(2_400)) { }
        assertEquals(4_800, speaker.written.size)
        assertTrue(speaker.written.subList(100, 4_700).all { it in 990..1_010 })
    }

    @Test
    fun compressedAudioIsRefusedWithoutOpeningTheSpeaker() = runBlocking {
        val speaker = FakeSpeaker(SpeakerScript())
        val error = assertFailsWith<VoiceAudioException> {
            LinePcmPlayer(speaker, options).play(AudioClip("ID3".encodeToByteArray() + ByteArray(64), AudioEncoding.MP3)) { }
        }
        assertEquals(VoiceErrorCode.UNSUPPORTED_FORMAT, error.code)
        assertEquals(0, speaker.opens)
    }

    @Test
    fun cancellingAbortsTheLineAndReleasesItBeforeReturning() = runBlocking {
        val speaker = FakeSpeaker(SpeakerScript(blockWrites = true))
        // A long stall budget: this test cancels a blocked write, it must not race the stall watchdog.
        val patient = options.copy(stallTimeout = 10.seconds)
        val playing = launch(Dispatchers.IO) { LinePcmPlayer(speaker, patient).play(wavClip(24_000)) { } }
        assertTrue(speaker.writing.await(5, TimeUnit.SECONDS))
        playing.cancelAndJoin()
        assertEquals(listOf("abort", "release"), speaker.events)
    }

    @Test
    fun aLineOpenedWhileThePlayerIsCancelledIsStillReleased() = runBlocking {
        val speaker = FakeSpeaker(SpeakerScript())
        val opening = CountDownLatch(1)
        val proceed = CountDownLatch(1)
        val slow = PcmOutputDevice { format ->
            opening.countDown()
            proceed.await(5, TimeUnit.SECONDS)
            speaker.open(format)
        }
        val playing = launch(Dispatchers.Default) { LinePcmPlayer(slow, options).play(wavClip(2_400)) { } }
        assertTrue(opening.await(5, TimeUnit.SECONDS))
        playing.cancel()
        proceed.countDown()
        playing.join()
        assertTrue("release" in speaker.events)
        assertTrue(speaker.written.isEmpty())
    }

    @Test
    fun aSpeakerThatStopsConsumingMidClipFailsWhileTheWriteIsBlocked() = runBlocking {
        val speaker = FakeSpeaker(SpeakerScript(stuck = true, jamAfterFirstWrite = true))
        val error = assertFailsWith<VoiceAudioException> { LinePcmPlayer(speaker, options).play(wavClip(24_000)) { } }
        assertEquals(VoiceErrorCode.PLAYBACK_FAILED, error.code)
        assertEquals(listOf("abort", "release"), speaker.events.toList())
    }

    @Test
    fun aFirstWriteThatNeverReturnsFailsWithinTheStallBudget() = runBlocking {
        val speaker = assertFailsWithinTheStallBudget(SpeakerScript(blockWrites = true))
    }

    @Test
    fun aWriteThatBlocksAfterEverythingEarlierPlayedFails() = runBlocking {
        val speaker = assertFailsWithinTheStallBudget(SpeakerScript(jamAfterFirstWrite = true))
    }

    @Test
    fun anEndOfStreamThatBlocksAfterTheClipPlayedFails() = runBlocking {
        val speaker = assertFailsWithinTheStallBudget(SpeakerScript(blockEndOfStream = true))
        assertEquals(2_400, speaker.written.size)
    }

    /**
     * Plays a short clip on a speaker following [script] and asserts it fails with PLAYBACK_FAILED well
     * inside the stall budget, the line aborted then released; returns the speaker for further checks.
     */
    private suspend fun assertFailsWithinTheStallBudget(script: SpeakerScript): FakeSpeaker {
        val speaker = FakeSpeaker(script)
        val elapsed = measureTime {
            val error = assertFailsWith<VoiceAudioException> { LinePcmPlayer(speaker, options).play(wavClip(2_400)) { } }
            assertEquals(VoiceErrorCode.PLAYBACK_FAILED, error.code)
        }
        assertTrue(elapsed < 2.seconds, "took $elapsed")
        assertEquals(listOf("abort", "release"), speaker.events.toList())
        return speaker
    }

    @Test
    fun aSlowDeviceThatKeepsPlayingIsNotTreatedAsStalled() = runBlocking {
        // Five 30 ms writes against a 50 ms budget: each write blocks, but playback moves every 10 ms.
        val speaker = FakeSpeaker(SpeakerScript(slowButPlaying = true))
        LinePcmPlayer(speaker, options).play(wavClip(2_400)) { }
        assertEquals(2_400, speaker.written.size)
        assertEquals(listOf("release"), speaker.events.toList())
    }

    @Test
    fun aSpeakerThatNeverPlaysFails() = runBlocking {
        val speaker = FakeSpeaker(SpeakerScript(stuck = true))
        val error = assertFailsWith<VoiceAudioException> { LinePcmPlayer(speaker, options).play(wavClip(480)) { } }
        assertEquals(VoiceErrorCode.PLAYBACK_FAILED, error.code)
        assertEquals(listOf("release"), speaker.events)
    }
}

/** Stopping playback discards what the line still holds and writes nothing after the abort. */
class LinePcmPlayerCancellationTest {
    @Test
    fun cancellingWhileQueuedAudioPlaysOutDiscardsItInsteadOfDraining() = runBlocking {
        // Everything is written and the line is still playing it out (the barge-in case).
        val speaker = FakeSpeaker(SpeakerScript(stuck = true))
        val patient = options.copy(stallTimeout = 5_000.milliseconds)
        val playing = launch(Dispatchers.IO) { LinePcmPlayer(speaker, patient).play(wavClip(2_400)) { } }
        assertTrue(speaker.draining.await(5, TimeUnit.SECONDS))
        val writtenBeforeStop = speaker.written.size
        playing.cancelAndJoin()
        assertEquals(listOf("abort", "release"), speaker.events.toList())
        assertEquals(writtenBeforeStop, speaker.written.size)
        assertEquals(0, speaker.writesAfterAbort)
    }

    @Test
    fun cancellingMidWriteWritesNothingAfterTheAbort() = runBlocking {
        val speaker = FakeSpeaker(SpeakerScript(blockWrites = true))
        val patient = options.copy(stallTimeout = 5_000.milliseconds)
        val playing = launch(Dispatchers.IO) { LinePcmPlayer(speaker, patient).play(wavClip(24_000)) { } }
        assertTrue(speaker.writing.await(5, TimeUnit.SECONDS))
        playing.cancelAndJoin()
        assertEquals("abort", speaker.events.first())
        assertEquals(0, speaker.writesAfterAbort)
    }
}
