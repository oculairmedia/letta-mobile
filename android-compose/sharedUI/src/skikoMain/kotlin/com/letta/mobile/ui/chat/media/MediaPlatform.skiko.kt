package com.letta.mobile.ui.chat.media

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.letta.mobile.data.media.MediaPlayer

/** Desktop and web play no video inline yet, so there is no surface: the card keeps its poster. */
@Composable
internal actual fun MediaVideoSurface(player: MediaPlayer, modifier: Modifier) = Unit
