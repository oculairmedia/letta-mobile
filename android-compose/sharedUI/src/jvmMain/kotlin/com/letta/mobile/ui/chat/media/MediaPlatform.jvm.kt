package com.letta.mobile.ui.chat.media

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.letta.mobile.data.media.JavaSoundMediaPlayer
import com.letta.mobile.data.media.MediaKind
import com.letta.mobile.data.media.MediaPlayerFactory
import com.letta.mobile.data.media.UnsupportedMediaPlayer

/** Desktop: Java Sound for audio (WAV, AIFF, AU); video and other audio formats open elsewhere. */
@Composable
internal actual fun rememberPlatformMediaPlayerFactory(): MediaPlayerFactory = remember {
    MediaPlayerFactory { kind -> if (kind == MediaKind.Audio) JavaSoundMediaPlayer() else UnsupportedMediaPlayer() }
}
