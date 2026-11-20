package com.letta.mobile.ui.chat.media

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import com.letta.mobile.data.media.MediaPlaybackState
import com.letta.mobile.data.media.formatMediaClock
import com.letta.mobile.sharedui.resources.Res
import com.letta.mobile.sharedui.resources.media_elapsed_total
import com.letta.mobile.sharedui.resources.media_mute
import com.letta.mobile.sharedui.resources.media_pause
import com.letta.mobile.sharedui.resources.media_play
import com.letta.mobile.sharedui.resources.media_position_of_total
import com.letta.mobile.sharedui.resources.media_seek
import com.letta.mobile.sharedui.resources.media_unmute
import com.letta.mobile.ui.components.foundation.LettaButtonIcon
import com.letta.mobile.ui.components.foundation.LettaIconButton
import com.letta.mobile.ui.icons.LettaIcons
import com.letta.mobile.ui.theme.tokens.LettaSpaceTokens
import com.letta.mobile.ui.theme.tokens.LettaControlTokens
import org.jetbrains.compose.resources.stringResource

/**
 * The one transport row audio and video share: play/pause, a scrubber, elapsed and total time, and
 * mute. Seeking applies when the thumb is released, so dragging does not stutter playback.
 */
@Composable
internal fun MediaTransportBar(
    playback: MediaItemPlayback,
    state: MediaPlaybackState,
    modifier: Modifier = Modifier,
    trailing: @Composable () -> Unit = {},
) {
    val enabled = playback.available
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(LettaSpaceTokens.xs),
    ) {
        MediaPlayPauseButton(playback, state, enabled)
        MediaScrubber(playback, state, Modifier.weight(1f), enabled)
        Text(
            text = stringResource(Res.string.media_elapsed_total, formatMediaClock(state.positionMs), totalClock(state.durationMs)),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.testTag(MediaTestTags.TIME),
        )
        MediaMuteButton(playback, state, enabled)
        trailing()
    }
}

@Composable
internal fun MediaPlayPauseButton(playback: MediaItemPlayback, state: MediaPlaybackState, enabled: Boolean, modifier: Modifier = Modifier) {
    val playing = state.isPlaying
    LettaIconButton(
        icon = LettaButtonIcon(
            imageVector = if (playing) LettaIcons.Pause else LettaIcons.Play,
            contentDescription = stringResource(if (playing) Res.string.media_pause else Res.string.media_play),
            size = LettaControlTokens.iconButtonLg,
        ),
        onClick = playback::toggle,
        enabled = enabled,
        modifier = modifier.testTag(MediaTestTags.PLAY_PAUSE),
    )
}

@Composable
private fun MediaScrubber(playback: MediaItemPlayback, state: MediaPlaybackState, modifier: Modifier, enabled: Boolean) {
    val duration = state.durationMs?.takeIf { it > 0L }
    var dragging by remember { mutableStateOf<Float?>(null) }
    val fraction = dragging ?: duration?.let { (state.positionMs.toFloat() / it).coerceIn(0f, 1f) } ?: 0f
    val label = stringResource(Res.string.media_seek)
    val position = stringResource(Res.string.media_position_of_total, formatMediaClock(state.positionMs), totalClock(duration))
    Slider(
        value = fraction,
        onValueChange = { dragging = it },
        onValueChangeFinished = {
            val target = dragging
            dragging = null
            if (target != null && duration != null) playback.seekTo((target * duration).toLong())
        },
        enabled = enabled && duration != null && state.isPlayable,
        modifier = modifier
            .testTag(MediaTestTags.SCRUBBER)
            .semantics {
                contentDescription = label
                stateDescription = position
            },
    )
}

@Composable
private fun MediaMuteButton(playback: MediaItemPlayback, state: MediaPlaybackState, enabled: Boolean) {
    val muted = state.muted || state.volume == 0f
    LettaIconButton(
        icon = LettaButtonIcon(
            imageVector = if (muted) LettaIcons.VolumeOff else LettaIcons.Volume,
            contentDescription = stringResource(if (muted) Res.string.media_unmute else Res.string.media_mute),
        ),
        onClick = playback::toggleMute,
        enabled = enabled,
        modifier = Modifier.testTag(MediaTestTags.MUTE),
    )
}

/** The total time, or a placeholder until the player knows it. */
private fun totalClock(durationMs: Long?): String = durationMs?.let(::formatMediaClock) ?: UNKNOWN_CLOCK

private const val UNKNOWN_CLOCK = "--:--"

/** Test tags for the media cards' parts. */
internal object MediaTestTags {
    const val AUDIO_CARD = "chat_media_audio"
    const val VIDEO_CARD = "chat_media_video"
    const val FILE_CARD = "chat_media_file"
    const val PLAY_PAUSE = "chat_media_play_pause"
    const val SCRUBBER = "chat_media_scrubber"
    const val TIME = "chat_media_time"
    const val MUTE = "chat_media_mute"
    const val FULLSCREEN = "chat_media_fullscreen"
    const val VIDEO_SURFACE = "chat_media_video_surface"
}
