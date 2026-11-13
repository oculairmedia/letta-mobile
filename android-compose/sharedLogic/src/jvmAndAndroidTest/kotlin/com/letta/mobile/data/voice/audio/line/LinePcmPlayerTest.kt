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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

/** How a fake speaker behaves: its rate, and whether writes block until abort or it never plays. */
private data class SpeakerScript(val rateHz: Int? = null, val blockWrites: Boolean = false, val stuck: Boolean = false)

private class FakeSpeaker(private val script: SpeakerScript) : PcmOutputDevice {
    val written = Collections.synchronizedList(mutableListOf<Short>())
    val events = Collections.synchronizedList(mutableListOf<String>())
    val writing = CountDownLatch(1)
    private val aborted = CountDownLatch(1)
    var opens = 0
        private set

    override fun open(format: PcmFormat): PcmOutputLine {
        opens++
        val opened = PcmFormat(script.rateHz ?: format.sampleRateHz)
        return object : PcmOutputLine {
            override val format = opened

            override fun write(samples: ShortArray, count: Int): Int {
                writing.countDown()
                if (script.blockWrites) return if (aborted.await(5, TimeUnit.SECONDS)) 0 else count
                for (index in 0 until count) written += samples[index]
                return count
            }

            override fun playedFrames(): Long = if (script.stuck) 0 else written.size.toLong()

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

class LinePcmPlayerTest {
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
        val playing = launch(Dispatchers.IO) { LinePcmPlayer(speaker, options).play(wavClip(24_000)) { } }
        assertTrue(speaker.writing.await(5, TimeUnit.SECONDS))
        playing.cancelAndJoin()
        assertEquals(listOf("abort", "release"), speaker.events)
    }

    @Test
    fun aSpeakerThatNeverPlaysFails() = runBlocking {
        val speaker = FakeSpeaker(SpeakerScript(stuck = true))
        val error = assertFailsWith<VoiceAudioException> { LinePcmPlayer(speaker, options).play(wavClip(480)) { } }
        assertEquals(VoiceErrorCode.PLAYBACK_FAILED, error.code)
        assertEquals(listOf("release"), speaker.events)
    }
}
