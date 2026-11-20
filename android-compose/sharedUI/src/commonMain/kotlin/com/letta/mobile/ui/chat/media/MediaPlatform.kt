package com.letta.mobile.ui.chat.media

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import com.letta.mobile.data.media.EngineMediaPlayer
import com.letta.mobile.data.media.MediaKind
import com.letta.mobile.data.media.MediaPlayer
import com.letta.mobile.data.media.MediaPlayerFactory
import com.letta.mobile.data.media.MediaSource
import io.github.kdroidfilter.composemediaplayer.VideoPlayerSurface
import io.github.kdroidfilter.composemediaplayer.createVideoPlayerState
import kotlinx.coroutines.CoroutineScope

/**
 * The players: ComposeMediaPlayer behind the port, unless the target supplies its own for a kind
 * ([platformMediaPlayer]). Players poll in the composition's scope, so loading and the clock stop
 * with the page.
 */
@Composable
internal fun rememberPlatformMediaPlayerFactory(): MediaPlayerFactory {
    val scope = rememberCoroutineScope()
    return remember(scope) {
        MediaPlayerFactory { kind ->
            platformMediaPlayer(kind, scope) ?: EngineMediaPlayer(ComposeMediaEngine(createVideoPlayerState()), scope)
        }
    }
}

/**
 * The video output for [player]: the engine's own surface. Draws nothing for a player without one
 * (a fake in tests, the unsupported player), leaving the poster.
 */
@Composable
internal fun MediaVideoSurface(player: MediaPlayer, modifier: Modifier = Modifier) {
    val engine = (player as? EngineMediaPlayer)?.engine as? ComposeMediaEngine ?: return
    VideoPlayerSurface(engine.videoState, modifier)
}

/** A URI the engine opens for inline bytes, and how to free anything written for it. */
internal class InlineMediaUri(val uri: String, val release: () -> Unit = {})

/** Inline bytes as a `data:` URI, for engines that read one (Media3, the browser). */
internal fun MediaSource.Inline.dataUri(): InlineMediaUri = InlineMediaUri("data:$mimeType;base64,$base64")

/**
 * A player this target supplies itself for [kind] instead of the shared engine, or null to use the
 * engine. Desktop needs it: the library's desktop engines open no audio-only source on Windows and
 * macOS, and none at all where the native bridge cannot load.
 */
internal expect fun platformMediaPlayer(kind: MediaKind, scope: CoroutineScope): MediaPlayer?

/** How this target's engine opens inline bytes; null when they could not be staged. */
internal expect fun inlineMediaUri(source: MediaSource.Inline): InlineMediaUri?
