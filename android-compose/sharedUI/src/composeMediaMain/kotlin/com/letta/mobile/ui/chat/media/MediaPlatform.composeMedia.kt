package com.letta.mobile.ui.chat.media

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import com.letta.mobile.data.media.EngineMediaPlayer
import com.letta.mobile.data.media.MediaPlayer
import com.letta.mobile.data.media.MediaPlayerFactory
import io.github.kdroidfilter.composemediaplayer.VideoPlayerSurface
import io.github.kdroidfilter.composemediaplayer.createVideoPlayerState

/**
 * Android and the web: every kind plays through ComposeMediaPlayer behind the port (Media3 with audio
 * focus and becoming-noisy handling on Android, an HTML video element on the web). Players poll in the
 * composition's scope, so loading and the clock stop with the page.
 */
@Composable
internal actual fun rememberPlatformMediaPlayerFactory(): MediaPlayerFactory {
    val scope = rememberCoroutineScope()
    return remember(scope) { MediaPlayerFactory { EngineMediaPlayer(ComposeMediaEngine(createVideoPlayerState()), scope) } }
}

/** The engine's own surface; nothing for a player without one (a fake in tests), leaving the poster. */
@Composable
internal actual fun MediaVideoSurface(player: MediaPlayer, modifier: Modifier) {
    val engine = (player as? EngineMediaPlayer)?.engine as? ComposeMediaEngine ?: return
    VideoPlayerSurface(engine.videoState, modifier)
}
