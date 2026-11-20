package com.letta.mobile.ui.chat.media

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.testTag
import com.letta.mobile.data.media.MediaKind
import com.letta.mobile.data.media.UiMediaAttachment
import com.letta.mobile.sharedui.resources.Res
import com.letta.mobile.sharedui.resources.media_audio
import com.letta.mobile.ui.components.foundation.LettaIcon
import com.letta.mobile.ui.icons.LettaIcons
import org.jetbrains.compose.resources.stringResource

/**
 * An audio attachment: its name, then the shared transport row. Nothing loads until Play; the
 * player is released when the card leaves the timeline, and paused while scrolled out of view.
 */
@Composable
internal fun AudioMediaCard(attachment: UiMediaAttachment, shape: Shape, modifier: Modifier = Modifier) {
    val source = attachment.playbackSource()
    val playback = rememberMediaItemPlayback(source, MediaKind.Audio)
    val state by playback.collectState()
    val unavailable = unavailableReason(source)
    MediaFrame(
        shape = shape,
        modifier = modifier
            .testTag(MediaTestTags.AUDIO_CARD)
            .pauseWhenScrolledAway(playback)
            .mediaKeyboard(playback),
    ) {
        MediaTitleLine(
            title = attachment.displayName ?: stringResource(Res.string.media_audio),
            source = source,
            leading = { LettaIcon(LettaIcons.AudioLines, contentDescription = null) },
        )
        MediaTransportBar(playback, state, Modifier.fillMaxWidth())
        MediaStatusLine(state, unavailable)
    }
}
