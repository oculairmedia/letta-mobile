package com.letta.mobile.ui.chat.media

import android.content.Context
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.exoplayer.ExoPlayer
import com.letta.mobile.data.media.MediaPlaybackState
import com.letta.mobile.data.media.MediaPlaybackStatus
import com.letta.mobile.data.media.MediaPlayer
import com.letta.mobile.data.media.MediaSource
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
 * The Android [MediaPlayer]: Media3 ExoPlayer, created and driven on the main thread. Media usage
 * with audio-focus handling, so voice mode's transient focus ducks or pauses it, and unplugging
 * headphones pauses it. Sources are never logged. The position clock runs in [scope], the owning
 * composition's, so it stops with the card even if [release] were missed.
 */
internal class ExoMediaPlayer(context: Context, private val scope: CoroutineScope) : MediaPlayer {
    val exoPlayer: ExoPlayer = ExoPlayer.Builder(context).build().apply {
        setAudioAttributes(
            AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(),
            /* handleAudioFocus = */ true,
        )
        setHandleAudioBecomingNoisy(true)
    }
    private val mutableState = MutableStateFlow(MediaPlaybackState())
    override val state: StateFlow<MediaPlaybackState> = mutableState.asStateFlow()
    private var ticker: Job? = null
    private var released = false

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) = publish()

        override fun onPlayerError(error: PlaybackException) {
            val status = if (error.errorCode in UNSUPPORTED_ERRORS) MediaPlaybackStatus.Unsupported else MediaPlaybackStatus.Failed
            mutableState.update { it.copy(status = status) }
        }

        override fun onVideoSizeChanged(videoSize: VideoSize) {
            if (videoSize.width <= 0 || videoSize.height <= 0) return
            mutableState.update { it.copy(videoWidth = videoSize.width, videoHeight = videoSize.height) }
        }
    }

    init {
        exoPlayer.addListener(listener)
    }

    override fun load(source: MediaSource) {
        if (released) return
        val uri = when (source) {
            is MediaSource.Remote -> source.url
            is MediaSource.Inline -> "data:${source.mimeType};base64,${source.base64}"
        }
        mutableState.update { MediaPlaybackState(status = MediaPlaybackStatus.Loading, volume = it.volume, muted = it.muted) }
        exoPlayer.setMediaItem(MediaItem.fromUri(uri))
        exoPlayer.prepare()
    }

    override fun play() {
        if (released || mutableState.value.status in TERMINAL) return
        if (exoPlayer.playbackState == Player.STATE_ENDED) exoPlayer.seekTo(0L)
        exoPlayer.play()
    }

    override fun pause() {
        if (released) return
        exoPlayer.pause()
    }

    override fun seekTo(positionMs: Long) {
        if (released || !mutableState.value.isPlayable) return
        val duration = mutableState.value.durationMs ?: Long.MAX_VALUE
        exoPlayer.seekTo(positionMs.coerceIn(0L, duration))
        publish()
    }

    override fun setVolume(volume: Float) {
        if (released) return
        mutableState.update { it.copy(volume = volume.coerceIn(0f, 1f)) }
        applyVolume()
    }

    override fun setMuted(muted: Boolean) {
        if (released) return
        mutableState.update { it.copy(muted = muted) }
        applyVolume()
    }

    override fun release() {
        if (released) return
        released = true
        ticker?.cancel()
        exoPlayer.removeListener(listener)
        exoPlayer.release()
        mutableState.update { it.copy(status = MediaPlaybackStatus.Released) }
    }

    private fun applyVolume() {
        val current = mutableState.value
        exoPlayer.volume = if (current.muted) 0f else current.volume
    }

    /** Mirrors ExoPlayer's state into [state], unless an error or release already settled it. */
    private fun publish() {
        if (released || mutableState.value.status in TERMINAL) return
        val duration = exoPlayer.duration.takeIf { it != C.TIME_UNSET && it >= 0L }
        val status = exoStatus()
        val position = exoPlayer.currentPosition.coerceAtLeast(0L)
        mutableState.update { it.copy(status = status, positionMs = position, durationMs = duration ?: it.durationMs) }
        if (exoPlayer.isPlaying) startTicker() else ticker?.cancel()
    }

    private fun exoStatus(): MediaPlaybackStatus = when {
        exoPlayer.isPlaying -> MediaPlaybackStatus.Playing
        exoPlayer.playbackState == Player.STATE_ENDED -> MediaPlaybackStatus.Ended
        exoPlayer.playbackState == Player.STATE_READY && exoPlayer.currentPosition > 0L -> MediaPlaybackStatus.Paused
        exoPlayer.playbackState == Player.STATE_READY -> MediaPlaybackStatus.Ready
        exoPlayer.playbackState == Player.STATE_BUFFERING && exoPlayer.playWhenReady -> MediaPlaybackStatus.Playing
        exoPlayer.playbackState == Player.STATE_BUFFERING -> MediaPlaybackStatus.Loading
        else -> mutableState.value.status
    }

    private fun startTicker() {
        if (ticker?.isActive == true) return
        ticker = scope.launch {
            while (isActive) {
                mutableState.update { it.copy(positionMs = exoPlayer.currentPosition.coerceAtLeast(0L)) }
                delay(POSITION_TICK_MS)
            }
        }
    }

    private companion object {
        const val POSITION_TICK_MS = 250L
        val TERMINAL = setOf(MediaPlaybackStatus.Failed, MediaPlaybackStatus.Unsupported, MediaPlaybackStatus.Released)
        val UNSUPPORTED_ERRORS = setOf(
            PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
            PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED,
        )
    }
}
