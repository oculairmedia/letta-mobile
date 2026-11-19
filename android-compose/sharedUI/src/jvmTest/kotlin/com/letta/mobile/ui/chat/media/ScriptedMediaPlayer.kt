package com.letta.mobile.ui.chat.media

import com.letta.mobile.data.media.MediaPlaybackState
import com.letta.mobile.data.media.MediaPlaybackStatus
import com.letta.mobile.data.media.MediaPlayer
import com.letta.mobile.data.media.MediaSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/** A player for UI tests and render previews: loads at once to [durationMs]; [advanceTo] moves the clock. */
internal class ScriptedMediaPlayer(private val durationMs: Long = 200_000L) : MediaPlayer {
    override val state: StateFlow<MediaPlaybackState> get() = flow
    private val flow = MutableStateFlow(MediaPlaybackState())

    override fun load(source: MediaSource) = flow.update { it.copy(status = MediaPlaybackStatus.Ready, durationMs = durationMs) }

    override fun play() = flow.update { if (it.isPlayable) it.copy(status = MediaPlaybackStatus.Playing) else it }

    override fun pause() = flow.update { if (it.isPlaying) it.copy(status = MediaPlaybackStatus.Paused) else it }

    override fun seekTo(positionMs: Long) = flow.update { it.copy(positionMs = positionMs.coerceIn(0L, durationMs)) }

    override fun setVolume(volume: Float) = flow.update { it.copy(volume = volume) }

    override fun setMuted(muted: Boolean) = flow.update { it.copy(muted = muted) }

    override fun release() = flow.update { it.copy(status = MediaPlaybackStatus.Released) }

    fun advanceTo(positionMs: Long) = flow.update { it.copy(positionMs = positionMs) }
}
