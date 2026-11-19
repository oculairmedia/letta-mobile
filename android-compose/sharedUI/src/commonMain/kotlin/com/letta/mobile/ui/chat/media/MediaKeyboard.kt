package com.letta.mobile.ui.chat.media

import androidx.compose.foundation.focusable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import com.letta.mobile.data.media.MediaLimits

/**
 * Keyboard control for a focused media card (desktop and hardware keyboards): Space or K plays and
 * pauses, Left and Right seek by [MediaLimits.SEEK_STEP_MS], M mutes, F toggles full screen where
 * [onFullscreen] is given. Other keys pass through.
 */
internal fun Modifier.mediaKeyboard(playback: MediaItemPlayback, onFullscreen: (() -> Unit)? = null): Modifier =
    onPreviewKeyEvent { event ->
        if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
        when (event.key) {
            Key.Spacebar, Key.K -> playback.toggle()
            Key.DirectionLeft -> playback.seekBy(-MediaLimits.SEEK_STEP_MS)
            Key.DirectionRight -> playback.seekBy(MediaLimits.SEEK_STEP_MS)
            Key.M -> playback.toggleMute()
            Key.F -> onFullscreen?.invoke() ?: return@onPreviewKeyEvent false
            else -> return@onPreviewKeyEvent false
        }
        true
    }.focusable()
