package com.letta.mobile.ui.chat.media

import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned

/**
 * Pauses [playback] once its card is wholly outside the window (scrolled away but still composed in
 * the list's prefetch range). A card leaving the composition is released instead.
 */
internal fun Modifier.pauseWhenScrolledAway(playback: MediaItemPlayback): Modifier = onGloballyPositioned { coordinates ->
    val player = playback.player ?: return@onGloballyPositioned
    if (player.state.value.isPlaying && coordinates.boundsInWindow().isEmpty) playback.pause()
}
