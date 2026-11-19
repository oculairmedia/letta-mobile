package com.letta.mobile.data.media

import kotlinx.coroutines.flow.StateFlow

/** Where a player reads a part from. Neither form is ever logged: URLs may carry tokens. */
sealed interface MediaSource {
    /** An http(s) resource the platform player streams or downloads. */
    data class Remote(val url: String) : MediaSource {
        override fun toString(): String = "Remote(url=<redacted>)"
    }

    /** Bytes carried in the message, base64 without a `data:` prefix. */
    data class Inline(val base64: String, val mimeType: String) : MediaSource {
        override fun toString(): String = "Inline(mimeType=$mimeType, chars=${base64.length})"
    }
}

/** Where a player is in its life. */
enum class MediaPlaybackStatus {
    /** Nothing loaded yet. */
    Idle,

    /** Reading or buffering the source. */
    Loading,

    /** Loaded and at the start or a seeked position, not playing. */
    Ready,

    /** Sounding (or buffering with playback requested). */
    Playing,

    /** Stopped mid-way by the person or the coordinator; play resumes here. */
    Paused,

    /** Played to the end; [MediaPlayer.play] starts again from the beginning. */
    Ended,

    /** The source could not be read or decoded. */
    Failed,

    /** This platform cannot play the format; offer to open it elsewhere. */
    Unsupported,

    /** [MediaPlayer.release] was called; the player accepts no further commands. */
    Released,
}

/** A player's observable state. Positions and durations are milliseconds; null duration is unknown. */
data class MediaPlaybackState(
    val status: MediaPlaybackStatus = MediaPlaybackStatus.Idle,
    val positionMs: Long = 0L,
    val durationMs: Long? = null,
    /** Output volume, 0..1, independent of [muted]. */
    val volume: Float = 1f,
    val muted: Boolean = false,
    /** Decoded video frame size in pixels once known; null for audio. */
    val videoWidth: Int? = null,
    val videoHeight: Int? = null,
) {
    val isPlaying: Boolean get() = status == MediaPlaybackStatus.Playing

    /** Whether the player can take play, pause and seek commands. */
    val isPlayable: Boolean get() = status in PLAYABLE

    private companion object {
        val PLAYABLE = setOf(MediaPlaybackStatus.Ready, MediaPlaybackStatus.Playing, MediaPlaybackStatus.Paused, MediaPlaybackStatus.Ended)
    }
}

/**
 * The one playback engine port. Audio and video share it; platform adapters (Media3 on Android,
 * Java Sound on desktop) implement it and must pass the shared contract suite. It only plays: it does
 * not choose which item plays (see [MediaPlaybackCoordinator]) and never logs its source.
 *
 * Contract:
 * - [load] moves Idle -> Loading -> Ready (or Failed/Unsupported); a second [load] replaces the source.
 * - [play] from Ready/Paused/Ended moves to Playing (Ended restarts at 0); during Loading it plays
 *   once loaded; ignored otherwise.
 * - [pause] from Playing moves to Paused; ignored otherwise.
 * - [seekTo] clamps to 0..duration and keeps the playing/paused status.
 * - [setVolume] clamps to 0..1; [setMuted] never changes [MediaPlaybackState.volume].
 * - [release] moves to Released and frees platform resources; it is idempotent, and every later
 *   command is ignored.
 */
interface MediaPlayer {
    /** Where the player is now; collected by the card's controls. */
    val state: StateFlow<MediaPlaybackState>

    /** Starts reading [source], replacing any earlier one. */
    fun load(source: MediaSource)

    /** Plays from the current position (from the start after the end); queued while loading. */
    fun play()

    /** Pauses, keeping the position. */
    fun pause()

    /** Moves to [positionMs], clamped to the duration. */
    fun seekTo(positionMs: Long)

    /** Sets the output volume, clamped to 0..1. */
    fun setVolume(volume: Float)

    /** Silences or restores output without changing the volume. */
    fun setMuted(muted: Boolean)

    /** Frees the platform engine; final. */
    fun release()
}

/** Builds a fresh player for a part of [kind]; each call returns a new, unloaded player. */
fun interface MediaPlayerFactory {
    /** A new, unloaded player for a part of [kind]. */
    fun create(kind: MediaKind): MediaPlayer
}
