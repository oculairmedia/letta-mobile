package com.letta.mobile.ui.chat.media

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import com.letta.mobile.data.media.MediaKind
import com.letta.mobile.data.media.UiMediaAttachment

/**
 * The card for one non-image attachment, chosen by its kind: audio and video get the shared player,
 * anything else a file card. Images are drawn by the timeline's image grid, not here; [shape] is the
 * image cell's, so a card matches the images beside it.
 */
@Composable
internal fun ChatMediaCard(attachment: UiMediaAttachment, shape: Shape, modifier: Modifier = Modifier) {
    when (attachment.kind) {
        MediaKind.Audio -> AudioMediaCard(attachment, shape, modifier)
        MediaKind.Video -> VideoMediaCard(attachment, shape, modifier)
        MediaKind.Image, MediaKind.File -> MediaFileCard(attachment, shape, modifier)
    }
}
