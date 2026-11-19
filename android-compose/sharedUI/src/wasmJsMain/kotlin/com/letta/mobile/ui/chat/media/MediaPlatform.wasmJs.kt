package com.letta.mobile.ui.chat.media

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.letta.mobile.data.media.MediaPlayerFactory
import com.letta.mobile.data.media.UnsupportedMediaPlayer

/** The web has no player yet: every item offers to open elsewhere. */
@Composable
internal actual fun rememberPlatformMediaPlayerFactory(): MediaPlayerFactory = remember { MediaPlayerFactory { UnsupportedMediaPlayer() } }
