package com.letta.mobile.ui.chat.media

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import com.letta.mobile.data.media.JavaSoundMediaPlayer
import com.letta.mobile.data.media.MediaKind
import com.letta.mobile.data.media.MediaPlayer
import com.letta.mobile.data.media.MediaPlayerFactory
import com.letta.mobile.data.media.UnsupportedMediaPlayer

/** Desktop: Java Sound for audio (WAV, AIFF, AU); video and other audio formats open elsewhere. */
@Composable
internal actual fun rememberPlatformMediaPlayerFactory(): MediaPlayerFactory {
    // Players run in the composition's scope, so loading and the clock stop with the page.
    val scope = rememberCoroutineScope()
    return remember(scope) {
        MediaPlayerFactory { kind -> if (kind == MediaKind.Audio) JavaSoundMediaPlayer(scope) else UnsupportedMediaPlayer() }
    }
}

/** Desktop plays no video inline yet, so there is no surface: the card keeps its poster. */
@Composable
internal actual fun MediaVideoSurface(player: MediaPlayer, modifier: Modifier) = Unit
