package com.letta.mobile.data.media

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.Base64
import javax.sound.sampled.AudioFileFormat
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioInputStream
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.LineUnavailableException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The desktop adapter's loading, format and state rules. No output line is opened (CI machines have
 * no audio device): the clip factory throws, which must surface as Failed, not a crash.
 */
class JavaSoundMediaPlayerTest {
    private val scope = CoroutineScope(SupervisorJob())

    @AfterTest
    fun cancelScope() = scope.cancel()

    private fun player() = JavaSoundMediaPlayer(
        scope = scope,
        io = Dispatchers.Unconfined,
        openClip = { throw LineUnavailableException("no device in tests") },
        fetch = { _, _ -> wav(seconds = 1) },
    )

    @Test
    fun anInlineWavLoadsReadyWithItsDuration() {
        val player = player()
        player.load(MediaSource.Inline(Base64.getEncoder().encodeToString(wav(seconds = 2)), "audio/wav"))
        assertEquals(MediaPlaybackStatus.Ready, player.state.value.status)
        assertEquals(2_000L, player.state.value.durationMs)
    }

    @Test
    fun aRemoteWavIsFetchedWithinTheBufferLimit() {
        val player = player()
        player.load(MediaSource.Remote("https://host/a.wav"))
        assertEquals(1_000L, player.state.value.durationMs)
    }

    @Test
    fun formatsJavaSoundCannotDecodeAreUnsupported() {
        val player = player()
        player.load(MediaSource.Inline(Base64.getEncoder().encodeToString("ID3 not a wave".toByteArray()), "audio/mpeg"))
        assertEquals(MediaPlaybackStatus.Unsupported, player.state.value.status)
    }

    @Test
    fun aFailedFetchIsFailed() {
        val player = JavaSoundMediaPlayer(
            scope = scope,
            io = Dispatchers.Unconfined,
            fetch = { _, _ -> throw java.io.IOException("offline") },
        )
        player.load(MediaSource.Remote("https://host/a.wav"))
        assertEquals(MediaPlaybackStatus.Failed, player.state.value.status)
    }

    @Test
    fun seekVolumeAndMuteApplyBeforeAnyLineOpens() {
        val player = player()
        player.load(MediaSource.Inline(Base64.getEncoder().encodeToString(wav(seconds = 2)), "audio/wav"))
        player.seekTo(5_000L)
        player.setVolume(0.5f)
        player.setMuted(true)
        val state = player.state.value
        assertEquals(2_000L, state.positionMs)
        assertEquals(0.5f, state.volume)
        assertEquals(true, state.muted)
        assertEquals(MediaPlaybackStatus.Ready, state.status)
    }

    @Test
    fun noAudioDeviceFailsPlaybackWithoutThrowing() {
        val player = player()
        player.load(MediaSource.Inline(Base64.getEncoder().encodeToString(wav(seconds = 1)), "audio/wav"))
        player.play()
        assertEquals(MediaPlaybackStatus.Failed, player.state.value.status)
        player.release()
        assertEquals(MediaPlaybackStatus.Released, player.state.value.status)
    }

    @Test
    fun remoteReadsStopAtTheLimit() {
        val result = runCatching { JavaSoundBuffers.readBounded(ByteArrayInputStream(ByteArray(10)), 4L) }
        assertEquals(true, result.isFailure)
        assertEquals(3, JavaSoundBuffers.readBounded(ByteArrayInputStream(ByteArray(3)), 4L).size)
    }

    private fun wav(seconds: Int): ByteArray {
        val format = AudioFormat(SAMPLE_RATE, BITS, 1, true, false)
        val frames = SAMPLE_RATE.toInt() * seconds
        val stream = AudioInputStream(ByteArrayInputStream(ByteArray(frames * 2)), format, frames.toLong())
        return ByteArrayOutputStream().also { AudioSystem.write(stream, AudioFileFormat.Type.WAVE, it) }.toByteArray()
    }

    private companion object {
        const val SAMPLE_RATE = 8_000f
        const val BITS = 16
    }
}
