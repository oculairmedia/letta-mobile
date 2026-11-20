package com.letta.mobile.ui.chat.media

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import com.letta.mobile.data.media.MediaKind
import com.letta.mobile.data.media.MediaLimits
import com.letta.mobile.data.media.MediaPlaybackCoordinator
import com.letta.mobile.data.media.MediaPlaybackState
import com.letta.mobile.data.media.MediaPlaybackStatus
import com.letta.mobile.data.media.MediaPlayer
import com.letta.mobile.data.media.MediaPlayerFactory
import com.letta.mobile.data.media.MediaSource

/** The page's players; null uses the platform's ([rememberPlatformMediaPlayerFactory]). Tests and previews provide fakes. */
internal val LocalMediaPlayerFactory = staticCompositionLocalOf<MediaPlayerFactory?> { null }

/** Keeps one item playing at a time. The chat page provides its own; outside a page one is shared. */
internal val LocalMediaPlaybackCoordinator = staticCompositionLocalOf { MediaPlaybackCoordinator() }

/**
 * One media item's playback: its player, created on first use and released with the item, started
 * through the page's coordinator so starting it pauses whatever played before.
 */
@Stable
internal class MediaItemPlayback(
    private val source: MediaSource?,
    private val kind: MediaKind,
    private val factory: MediaPlayerFactory,
    private val coordinator: MediaPlaybackCoordinator,
) {
    /** Whether there is anything to play: the bytes were kept (or there is a URL) and fit the limits. */
    val available: Boolean get() = source != null

    /** The player once [load] or [toggle] made one; null before. */
    var player: MediaPlayer? by mutableStateOf(null)
        private set

    /** Load without playing (a video's first frame as its poster). Returns the player, or null with no source. */
    fun load(): MediaPlayer? {
        player?.let { current ->
            // A failed load (offline, a server error) is retried on the next request, not kept.
            if (current.state.value.status != MediaPlaybackStatus.Failed) return current
            release()
        }
        val from = source ?: return null
        return factory.create(kind).also { created ->
            created.load(from)
            player = created
        }
    }

    fun toggle() {
        val target = load() ?: return
        if (target.state.value.isPlaying) target.pause() else coordinator.play(target)
    }

    fun pause() {
        player?.pause()
    }

    fun seekTo(positionMs: Long) {
        player?.seekTo(positionMs)
    }

    fun seekBy(deltaMs: Long) {
        val target = player ?: return
        target.seekTo(target.state.value.positionMs + deltaMs)
    }

    fun toggleMute() {
        val target = load() ?: return
        target.setMuted(!target.state.value.muted)
    }

    fun setVolume(volume: Float) {
        val target = load() ?: return
        target.setVolume(volume)
        if (volume > 0f && target.state.value.muted) target.setMuted(false)
    }

    fun release() {
        val held = player ?: return
        coordinator.forget(held)
        held.release()
        player = null
    }
}

/** Whether an attachment's bytes may be played inline: a URL, or an inline payload within [MediaLimits]. */
internal fun inlineTooLarge(source: MediaSource?): Boolean =
    source is MediaSource.Inline && !MediaLimits.inlinePlayable(source.base64.length)

/** The playback for [source], released when it leaves the composition or the source changes. */
@Composable
internal fun rememberMediaItemPlayback(source: MediaSource?, kind: MediaKind): MediaItemPlayback {
    val factory = LocalMediaPlayerFactory.current ?: rememberPlatformMediaPlayerFactory()
    val coordinator = LocalMediaPlaybackCoordinator.current
    val playable = source?.takeUnless { inlineTooLarge(it) }
    val playback = remember(playable, kind, factory, coordinator) { MediaItemPlayback(playable, kind, factory, coordinator) }
    DisposableEffect(playback) { onDispose { playback.release() } }
    return playback
}

/** [playback]'s player state, idle until it has a player. */
@Composable
internal fun MediaItemPlayback.collectState(): State<MediaPlaybackState> {
    val current = player ?: return rememberUpdatedState(MediaPlaybackState())
    return current.state.collectAsState()
}
