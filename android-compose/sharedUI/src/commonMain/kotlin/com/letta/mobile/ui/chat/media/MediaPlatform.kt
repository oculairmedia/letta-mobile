package com.letta.mobile.ui.chat.media

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.letta.mobile.data.media.MediaPlayer
import com.letta.mobile.data.media.MediaPlayerFactory

/** This platform's player factory: ComposeMediaPlayer on Android and the web, Java Sound for desktop audio. */
@Composable
internal expect fun rememberPlatformMediaPlayerFactory(): MediaPlayerFactory

/**
 * Platform video output for [player]: a surface its engine draws frames into. Draws nothing for a
 * player this platform did not create (a fake in tests, an unsupported player), leaving the poster.
 */
@Composable
internal expect fun MediaVideoSurface(player: MediaPlayer, modifier: Modifier = Modifier)
