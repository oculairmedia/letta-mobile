package com.letta.mobile.ui.chat.media

import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import com.letta.mobile.data.media.MediaPlaybackStatus

/**
 * Pauses [playback] once its card is wholly outside the window (scrolled away but still composed in
 * the list's prefetch range), including a play still queued behind loading. A card leaving the
 * composition is released instead.
 */
internal fun Modifier.pauseWhenScrolledAway(playback: MediaItemPlayback): Modifier = onGloballyPositioned { coordinates ->
    val status = playback.player?.state?.value?.status ?: return@onGloballyPositioned
    if (status in PAUSABLE && coordinates.boundsInWindow().isEmpty) playback.pause()
}

private val PAUSABLE = setOf(MediaPlaybackStatus.Playing, MediaPlaybackStatus.Loading)
