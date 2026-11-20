package com.letta.mobile.data.media

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The player for a platform or kind with no engine: every load reports
 * [MediaPlaybackStatus.Unsupported], so renderers show the poster and the open-externally action
 * instead of controls that cannot work.
 */
class UnsupportedMediaPlayer : MediaPlayer {
    private val mutableState = MutableStateFlow(MediaPlaybackState())
    override val state: StateFlow<MediaPlaybackState> = mutableState.asStateFlow()

    override fun load(source: MediaSource) {
        if (released()) return
        mutableState.value = MediaPlaybackState(status = MediaPlaybackStatus.Unsupported)
    }

    override fun play() = Unit

    override fun pause() = Unit

    override fun seekTo(positionMs: Long) = Unit

    override fun setVolume(volume: Float) {
        if (released()) return
        mutableState.value = mutableState.value.copy(volume = volume.coerceIn(0f, 1f))
    }

    override fun setMuted(muted: Boolean) {
        if (released()) return
        mutableState.value = mutableState.value.copy(muted = muted)
    }

    override fun release() {
        mutableState.value = mutableState.value.copy(status = MediaPlaybackStatus.Released)
    }

    private fun released() = mutableState.value.status == MediaPlaybackStatus.Released
}
