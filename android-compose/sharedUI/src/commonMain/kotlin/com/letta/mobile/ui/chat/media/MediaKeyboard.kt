package com.letta.mobile.ui.chat.media

import androidx.compose.foundation.focusable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import com.letta.mobile.data.media.MediaLimits

/**
 * Keyboard control for a focused media card (desktop and hardware keyboards): Space or K plays and
 * pauses, Left and Right seek by [MediaLimits.SEEK_STEP_MS], M mutes, F toggles full screen where
 * [onFullscreen] is given. Other keys pass through. Handled after the focused control: a focused
 * button keeps Space and the scrubber keeps the arrows; the card takes them when it holds focus itself.
 */
internal fun Modifier.mediaKeyboard(playback: MediaItemPlayback, onFullscreen: (() -> Unit)? = null): Modifier =
    onKeyEvent { event ->
        if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
        when (event.key) {
            Key.Spacebar, Key.K -> playback.toggle()
            Key.DirectionLeft -> playback.seekBy(-MediaLimits.SEEK_STEP_MS)
            Key.DirectionRight -> playback.seekBy(MediaLimits.SEEK_STEP_MS)
            Key.M -> playback.toggleMute()
            Key.F -> onFullscreen?.invoke() ?: return@onKeyEvent false
            else -> return@onKeyEvent false
        }
        true
    }.focusable()
