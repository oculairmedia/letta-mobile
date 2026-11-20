package com.letta.mobile.data.media

import java.io.ByteArrayInputStream
import java.io.IOException
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.BooleanControl
import javax.sound.sampled.Clip
import javax.sound.sampled.FloatControl
import javax.sound.sampled.LineEvent
import javax.sound.sampled.LineUnavailableException
import javax.sound.sampled.UnsupportedAudioFileException
import kotlin.math.log10
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * The desktop [MediaPlayer] for audio: Java Sound, which ships with the JDK and decodes PCM WAV,
 * AIFF and AU. Other formats (MP3, AAC, Ogg) report [MediaPlaybackStatus.Unsupported] so the card
 * offers to open them elsewhere. The whole file is buffered (within [MediaLimits]) because a [Clip]
 * needs it; the output line opens only on the first play, so loading works without an audio device.
 *
 * @param scope the owner's scope (the card's composition): loading and the position clock run in it
 *   and stop with it; [release] cancels only this player's own jobs.
 * @param openClip the output line, injectable for tests.
 * @param fetch reads a remote URL up to a byte cap; injectable for tests. Never logs the URL.
 */
class JavaSoundMediaPlayer(
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val openClip: () -> Clip = AudioSystem::getClip,
    private val fetch: (url: String, maxBytes: Long) -> ByteArray = JavaSoundBuffers::fetchBounded,
) : MediaPlayer {
    private val mutableState = MutableStateFlow(MediaPlaybackState())
    override val state: StateFlow<MediaPlaybackState> = mutableState.asStateFlow()
    private val lock = Any()
    private var bytes: ByteArray? = null
    private var clip: Clip? = null
    private var ticker: Job? = null
    private var loading: Job? = null
    private var playWhenLoaded = false
    private var loadGeneration = 0

    override fun load(source: MediaSource): Unit = synchronized(lock) {
        if (isReleased) return
        closeClip()
        loading?.cancel()
        bytes = null
        playWhenLoaded = false
        mutableState.update { MediaPlaybackState(status = MediaPlaybackStatus.Loading, volume = it.volume, muted = it.muted) }
        val generation = ++loadGeneration
        loading = scope.launch(io) { decode(source, generation) }
    }

    private fun decode(source: MediaSource, generation: Int) {
        val outcome = runCatching {
            val data = when (source) {
                is MediaSource.Inline -> java.util.Base64.getMimeDecoder().decode(source.base64)
                is MediaSource.Remote -> fetch(source.url, MediaLimits.MAX_BUFFERED_REMOTE_BYTES)
            }
            data to AudioSystem.getAudioInputStream(ByteArrayInputStream(data)).use(JavaSoundBuffers::durationOf)
        }
        synchronized(lock) {
            if (isReleased || generation != loadGeneration) return
            outcome.onSuccess { (data, durationMs) ->
                bytes = data
                mutableState.update { it.copy(status = MediaPlaybackStatus.Ready, positionMs = 0L, durationMs = durationMs) }
                if (playWhenLoaded) startLocked()
            }.onFailure { error ->
                val status = if (error is UnsupportedAudioFileException) MediaPlaybackStatus.Unsupported else MediaPlaybackStatus.Failed
                mutableState.update { it.copy(status = status) }
            }
        }
    }

    override fun play(): Unit = synchronized(lock) {
        when (mutableState.value.status) {
            MediaPlaybackStatus.Loading -> playWhenLoaded = true
            MediaPlaybackStatus.Ready, MediaPlaybackStatus.Paused, MediaPlaybackStatus.Ended -> startLocked()
            else -> Unit
        }
    }

    private fun startLocked() {
        playWhenLoaded = false
        val line = clip ?: openLine() ?: return
        if (mutableState.value.status == MediaPlaybackStatus.Ended) mutableState.update { it.copy(positionMs = 0L) }
        line.microsecondPosition = mutableState.value.positionMs * MICROS_PER_MILLI
        applyVolume(line)
        line.start()
        mutableState.update { it.copy(status = MediaPlaybackStatus.Playing) }
        startTicker()
    }

    private fun openLine(): Clip? = try {
        val data = bytes ?: return null
        openClip().also { line ->
            line.open(JavaSoundBuffers.pcmStream(data))
            line.addLineListener { event -> if (event.type == LineEvent.Type.STOP) onStopped(line) }
            clip = line
        }
    } catch (expected: LineUnavailableException) {
        mutableState.update { it.copy(status = MediaPlaybackStatus.Failed) }
        null
    } catch (expected: IOException) {
        mutableState.update { it.copy(status = MediaPlaybackStatus.Failed) }
        null
    } catch (expected: IllegalArgumentException) {
        mutableState.update { it.copy(status = MediaPlaybackStatus.Unsupported) }
        null
    }

    private fun onStopped(line: Clip): Unit = synchronized(lock) {
        if (clip !== line || mutableState.value.status != MediaPlaybackStatus.Playing) return
        val atEnd = line.framePosition >= line.frameLength
        ticker?.cancel()
        mutableState.update {
            if (atEnd) {
                it.copy(status = MediaPlaybackStatus.Ended, positionMs = it.durationMs ?: it.positionMs)
            } else {
                it.copy(status = MediaPlaybackStatus.Paused)
            }
        }
    }

    override fun pause(): Unit = synchronized(lock) {
        if (mutableState.value.status == MediaPlaybackStatus.Loading) playWhenLoaded = false
        if (mutableState.value.status != MediaPlaybackStatus.Playing) return
        ticker?.cancel()
        val line = clip
        val position = line?.let { it.microsecondPosition / MICROS_PER_MILLI }
        mutableState.update { it.copy(status = MediaPlaybackStatus.Paused, positionMs = position ?: it.positionMs) }
        line?.stop()
    }

    override fun seekTo(positionMs: Long): Unit = synchronized(lock) {
        val current = mutableState.value
        if (!current.isPlayable) return
        val target = positionMs.coerceIn(0L, current.durationMs ?: Long.MAX_VALUE)
        clip?.microsecondPosition = target * MICROS_PER_MILLI
        val status = if (current.status == MediaPlaybackStatus.Ended) MediaPlaybackStatus.Paused else current.status
        mutableState.update { it.copy(positionMs = target, status = status) }
    }

    override fun setVolume(volume: Float): Unit = synchronized(lock) {
        if (isReleased) return
        mutableState.update { it.copy(volume = volume.coerceIn(0f, 1f)) }
        clip?.let(::applyVolume)
    }

    override fun setMuted(muted: Boolean): Unit = synchronized(lock) {
        if (isReleased) return
        mutableState.update { it.copy(muted = muted) }
        clip?.let(::applyVolume)
    }

    override fun release(): Unit = synchronized(lock) {
        if (isReleased) return
        loading?.cancel()
        closeClip()
        bytes = null
        mutableState.update { it.copy(status = MediaPlaybackStatus.Released) }
    }

    private fun applyVolume(line: Clip) {
        val current = mutableState.value
        if (line.isControlSupported(BooleanControl.Type.MUTE)) {
            (line.getControl(BooleanControl.Type.MUTE) as BooleanControl).value = current.muted
        }
        if (line.isControlSupported(FloatControl.Type.MASTER_GAIN)) {
            val gain = line.getControl(FloatControl.Type.MASTER_GAIN) as FloatControl
            val level = if (current.muted) 0f else current.volume
            val decibels = if (level <= 0f) gain.minimum else (DECIBELS_PER_DECADE * log10(level)).coerceIn(gain.minimum, gain.maximum)
            gain.value = decibels
        }
    }

    private fun startTicker() {
        ticker?.cancel()
        ticker = scope.launch(io) {
            while (isActive) {
                delay(POSITION_TICK_MS)
                synchronized(lock) {
                    val line = clip
                    if (line != null && mutableState.value.status == MediaPlaybackStatus.Playing) {
                        mutableState.update { it.copy(positionMs = line.microsecondPosition / MICROS_PER_MILLI) }
                    }
                }
            }
        }
    }

    private fun closeClip() {
        ticker?.cancel()
        clip?.let { line ->
            clip = null
            line.stop()
            line.close()
        }
    }

    private val isReleased: Boolean get() = mutableState.value.status == MediaPlaybackStatus.Released

    private companion object {
        const val MICROS_PER_MILLI = 1_000L
        const val POSITION_TICK_MS = 200L
        const val DECIBELS_PER_DECADE = 20f
    }
}
