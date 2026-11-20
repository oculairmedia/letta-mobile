package com.letta.mobile.data.media

import kotlin.math.abs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * The [MediaPlayer] over any [MediaEngine]: it owns the port's state machine and polls the engine
 * while loading or playing, every [tickMs] in [scope] (the owning composition's, so polling stops with
 * the card even if [release] were missed). Commands update [state] at once; readings then reconcile it.
 */
class EngineMediaPlayer(
    /** The engine this player drives; the video surface draws from it. */
    val engine: MediaEngine,
    private val scope: CoroutineScope,
    private val tickMs: Long = DEFAULT_TICK_MS,
) : MediaPlayer {
    private val mutableState = MutableStateFlow(MediaPlaybackState())
    override val state: StateFlow<MediaPlaybackState> = mutableState.asStateFlow()
    private val progress = Progress()
    private var ticker: Job? = null

    override fun load(source: MediaSource) {
        if (status == MediaPlaybackStatus.Released) return
        progress.reset()
        mutableState.update { MediaPlaybackState(status = MediaPlaybackStatus.Loading, volume = it.volume, muted = it.muted) }
        engine.open(source)
        sync()
    }

    override fun play() {
        val status = this.status
        if (status !in PLAYABLE && status != MediaPlaybackStatus.Loading) return
        progress.playRequested = true
        if (status == MediaPlaybackStatus.Loading) return
        progress.played = true
        if (status == MediaPlaybackStatus.Ended) seekEngine(0L)
        progress.engineWasPlaying = false
        engine.play()
        mutableState.update { it.copy(status = MediaPlaybackStatus.Playing) }
        sync()
    }

    override fun pause() {
        if (status == MediaPlaybackStatus.Released) return
        progress.playRequested = false
        if (status != MediaPlaybackStatus.Playing) return
        engine.pause()
        mutableState.update { it.copy(status = MediaPlaybackStatus.Paused) }
        sync()
    }

    override fun seekTo(positionMs: Long) {
        val current = mutableState.value
        if (!current.isPlayable) return
        val target = positionMs.coerceIn(0L, current.durationMs ?: Long.MAX_VALUE)
        seekEngine(target)
        val next = if (current.status == MediaPlaybackStatus.Ended) MediaPlaybackStatus.Paused else current.status
        mutableState.update { it.copy(status = next, positionMs = target) }
        sync()
    }

    override fun setVolume(volume: Float) {
        if (status == MediaPlaybackStatus.Released) return
        mutableState.update { it.copy(volume = volume.coerceIn(0f, 1f)) }
        applyVolume()
    }

    override fun setMuted(muted: Boolean) {
        if (status == MediaPlaybackStatus.Released) return
        mutableState.update { it.copy(muted = muted) }
        applyVolume()
    }

    override fun release() {
        if (status == MediaPlaybackStatus.Released) return
        ticker?.cancel()
        ticker = null
        mutableState.update { it.copy(status = MediaPlaybackStatus.Released) }
        engine.dispose()
    }

    private val status: MediaPlaybackStatus get() = mutableState.value.status

    private fun seekEngine(target: Long) {
        progress.pendingSeekMs = target
        progress.pendingSeekTicks = 0
        progress.engineWasPlaying = false
        engine.seekTo(target)
    }

    private fun applyVolume() {
        val current = mutableState.value
        engine.setOutputVolume(if (current.muted) 0f else current.volume)
    }

    /** Reconciles [state] with a fresh engine reading, unless a failure or release already settled it. */
    private fun sync() {
        val current = mutableState.value
        if (current.status in SETTLED) return
        val reading = engine.read()
        val status = reading.failure?.let(::failedStatus) ?: nextStatus(current.status, reading)
        if (status in SETTLED) progress.playRequested = false
        val duration = reading.durationMs ?: current.durationMs
        val position = if (status == MediaPlaybackStatus.Ended) duration ?: reading.positionMs else settledPosition(reading)
        progress.engineWasPlaying = reading.isPlaying
        mutableState.update {
            it.copy(
                status = status,
                positionMs = position.coerceIn(0L, duration ?: Long.MAX_VALUE),
                durationMs = duration,
                videoWidth = reading.videoWidth ?: it.videoWidth,
                videoHeight = reading.videoHeight ?: it.videoHeight,
            )
        }
        if (status in TICKING) startTicker()
    }

    private fun nextStatus(current: MediaPlaybackStatus, reading: MediaEngineReading): MediaPlaybackStatus {
        if (current == MediaPlaybackStatus.Loading || current == MediaPlaybackStatus.Idle) return loadingStatus(reading)
        return when {
            reading.atEnd && !reading.isPlaying -> MediaPlaybackStatus.Ended.also { progress.playRequested = false }
            // Output stopped without a pause from us (audio focus taken, headphones unplugged).
            progress.playRequested && progress.engineWasPlaying && !reading.isPlaying && !reading.isLoading ->
                MediaPlaybackStatus.Paused.also { progress.playRequested = false }
            progress.playRequested -> MediaPlaybackStatus.Playing
            current == MediaPlaybackStatus.Ended -> MediaPlaybackStatus.Ended
            progress.played -> MediaPlaybackStatus.Paused
            else -> MediaPlaybackStatus.Ready
        }
    }

    private fun loadingStatus(reading: MediaEngineReading): MediaPlaybackStatus {
        if (!reading.hasMedia || reading.durationMs == null) return MediaPlaybackStatus.Loading
        if (!progress.playRequested) return MediaPlaybackStatus.Ready
        progress.played = true
        engine.play()
        return MediaPlaybackStatus.Playing
    }

    /** The reading's position, held at a seek's target until the engine catches up with it. */
    private fun settledPosition(reading: MediaEngineReading): Long {
        val pending = progress.pendingSeekMs ?: return reading.positionMs
        val caughtUp = abs(reading.positionMs - pending) <= SEEK_TOLERANCE_MS
        if (caughtUp || ++progress.pendingSeekTicks > SEEK_SETTLE_TICKS) progress.pendingSeekMs = null
        return if (caughtUp) reading.positionMs else pending
    }

    private fun startTicker() {
        if (ticker?.isActive == true) return
        ticker = scope.launch {
            while (isActive && status in TICKING) {
                delay(tickMs)
                sync()
            }
        }
    }

    /** What the player asked of the engine since the last load. */
    private class Progress {
        var playRequested = false
        var played = false
        var engineWasPlaying = false
        var pendingSeekMs: Long? = null
        var pendingSeekTicks = 0

        fun reset() {
            playRequested = false
            played = false
            engineWasPlaying = false
            pendingSeekMs = null
            pendingSeekTicks = 0
        }
    }

    private companion object {
        const val DEFAULT_TICK_MS = 250L
        const val SEEK_TOLERANCE_MS = 750L
        const val SEEK_SETTLE_TICKS = 8
        val PLAYABLE = setOf(MediaPlaybackStatus.Ready, MediaPlaybackStatus.Playing, MediaPlaybackStatus.Paused, MediaPlaybackStatus.Ended)
        val SETTLED = setOf(MediaPlaybackStatus.Failed, MediaPlaybackStatus.Unsupported, MediaPlaybackStatus.Released)
        val TICKING = setOf(MediaPlaybackStatus.Loading, MediaPlaybackStatus.Playing)

        fun failedStatus(failure: MediaEngineFailure) = when (failure) {
            MediaEngineFailure.Unsupported -> MediaPlaybackStatus.Unsupported
            MediaEngineFailure.Failed -> MediaPlaybackStatus.Failed
        }
    }
}
