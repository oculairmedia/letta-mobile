package com.letta.mobile.ui.chat.media

import com.letta.mobile.data.media.MediaEngine
import com.letta.mobile.data.media.MediaEngineFailure
import com.letta.mobile.data.media.MediaEngineReading
import com.letta.mobile.data.media.MediaSource
import io.github.kdroidfilter.composemediaplayer.InitialPlayerState
import io.github.kdroidfilter.composemediaplayer.VideoPlayerError
import io.github.kdroidfilter.composemediaplayer.VideoPlayerState
import kotlin.concurrent.Volatile

/**
 * ComposeMediaPlayer as the [MediaEngine] behind the inline audio and video cards on Android (Media3)
 * and the web (an HTML video element). Only translation lives here;
 * [com.letta.mobile.data.media.EngineMediaPlayer] owns the port's states. The library's logging stays
 * off (its default), so sources are never printed.
 */
internal class ComposeMediaEngine(
    /** The library's player; the card's video surface draws it. */
    val videoState: VideoPlayerState,
) : MediaEngine {
    // The library reports the end from its own thread; the player reads this on its next poll.
    @Volatile
    private var atEnd = false

    init {
        videoState.loop = false
        videoState.onPlaybackEnded = { atEnd = true }
    }

    override fun open(source: MediaSource) {
        atEnd = false
        // Both engines read `data:` URIs, so inline bytes are never written anywhere.
        val uri = when (source) {
            is MediaSource.Remote -> source.url
            is MediaSource.Inline -> "data:${source.mimeType};base64,${source.base64}"
        }
        videoState.openUri(uri, InitialPlayerState.PAUSE)
    }

    override fun play() {
        atEnd = false
        videoState.play()
    }

    override fun pause() = videoState.pause()

    override fun seekTo(positionMs: Long) {
        atEnd = false
        val durationMs = videoState.duration * MILLIS_PER_SECOND
        if (durationMs <= 0.0) return
        videoState.seekTo((positionMs / durationMs * SLIDER_RANGE).toFloat())
    }

    override fun setOutputVolume(volume: Float) {
        videoState.volume = volume
    }

    override fun read(): MediaEngineReading = MediaEngineReading(
        hasMedia = videoState.hasMedia,
        isPlaying = videoState.isPlaying,
        isLoading = videoState.isLoading,
        positionMs = (videoState.currentTime * MILLIS_PER_SECOND).toLong(),
        durationMs = (videoState.duration * MILLIS_PER_SECOND).toLong().takeIf { it > 0L },
        failure = videoState.error?.let(::failureOf),
        atEnd = atEnd,
        videoWidth = videoState.metadata.width?.takeIf { it > 0 },
        videoHeight = videoState.metadata.height?.takeIf { it > 0 },
    )

    override fun dispose() {
        videoState.onPlaybackEnded = null
        videoState.dispose()
    }

    private companion object {
        const val MILLIS_PER_SECOND = 1_000.0

        /** The library's seek scale: 0 is the start, this the end. */
        const val SLIDER_RANGE = 1_000.0

        /** A codec error is a format this engine cannot decode; anything else is a read failure. */
        fun failureOf(error: VideoPlayerError): MediaEngineFailure =
            if (error is VideoPlayerError.CodecError) MediaEngineFailure.Unsupported else MediaEngineFailure.Failed
    }
}
