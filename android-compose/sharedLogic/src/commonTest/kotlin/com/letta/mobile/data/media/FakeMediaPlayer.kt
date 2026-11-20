package com.letta.mobile.data.media

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * A scripted [MediaPlayer] for contract and coordinator tests: [load] stays Loading until
 * [finishLoading] (or completes at once with [loadsImmediately]), and [advanceTo] moves the clock.
 */
class FakeMediaPlayer(
    private val durationMs: Long = 60_000L,
    private val loadsImmediately: Boolean = true,
) : MediaPlayer {
    private val mutableState = MutableStateFlow(MediaPlaybackState())
    override val state: StateFlow<MediaPlaybackState> = mutableState.asStateFlow()
    private var playWhenLoaded = false
    val commands = mutableListOf<String>()

    override fun load(source: MediaSource) {
        if (released()) return
        commands += "load"
        playWhenLoaded = false
        mutableState.value = MediaPlaybackState(status = MediaPlaybackStatus.Loading, volume = current().volume, muted = current().muted)
        if (loadsImmediately) finishLoading()
    }

    fun finishLoading(status: MediaPlaybackStatus = MediaPlaybackStatus.Ready) {
        if (released()) return
        mutableState.value = current().copy(status = status, durationMs = durationMs.takeIf { status == MediaPlaybackStatus.Ready })
        if (status == MediaPlaybackStatus.Ready && playWhenLoaded) play()
    }

    override fun play() {
        commands += "play"
        when (current().status) {
            MediaPlaybackStatus.Loading -> playWhenLoaded = true
            MediaPlaybackStatus.Ended -> mutableState.value = current().copy(status = MediaPlaybackStatus.Playing, positionMs = 0L)
            MediaPlaybackStatus.Ready, MediaPlaybackStatus.Paused ->
                mutableState.value = current().copy(status = MediaPlaybackStatus.Playing)
            else -> Unit
        }
    }

    override fun pause() {
        commands += "pause"
        if (current().status == MediaPlaybackStatus.Loading) playWhenLoaded = false
        if (current().status == MediaPlaybackStatus.Playing) mutableState.value = current().copy(status = MediaPlaybackStatus.Paused)
    }

    override fun seekTo(positionMs: Long) {
        val now = current()
        if (!now.isPlayable) return
        val status = if (now.status == MediaPlaybackStatus.Ended) MediaPlaybackStatus.Paused else now.status
        mutableState.value = now.copy(positionMs = positionMs.coerceIn(0L, durationMs), status = status)
    }

    override fun setVolume(volume: Float) {
        if (!released()) mutableState.value = current().copy(volume = volume.coerceIn(0f, 1f))
    }

    override fun setMuted(muted: Boolean) {
        if (!released()) mutableState.value = current().copy(muted = muted)
    }

    override fun release() {
        commands += "release"
        mutableState.value = current().copy(status = MediaPlaybackStatus.Released)
    }

    /** Plays [positionMs] forward; reaching the duration ends playback. */
    fun advanceTo(positionMs: Long) {
        if (current().status != MediaPlaybackStatus.Playing) return
        val ended = positionMs >= durationMs
        val status = if (ended) MediaPlaybackStatus.Ended else MediaPlaybackStatus.Playing
        mutableState.value = current().copy(positionMs = positionMs.coerceAtMost(durationMs), status = status)
    }

    private fun current() = mutableState.value

    private fun released() = current().status == MediaPlaybackStatus.Released
}
