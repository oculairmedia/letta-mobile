package com.letta.mobile.ui.chat.surface.timeline.rows

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import com.letta.mobile.data.media.MediaKind
import com.letta.mobile.data.media.UiMediaAttachment
import com.letta.mobile.ui.chat.media.ChatMediaCard
import com.letta.mobile.ui.theme.LettaDimens
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList

/**
 * The one entry point a row draws its attachments through. Images go to [images] (the grid, prompt
 * grid or thumbnail strip, with the page's viewer over only the images); audio, video and other
 * files follow as media cards in the image cells' shape. A row with only images draws exactly
 * what [images] draws.
 */
@Composable
internal fun ChatMediaAttachments(
    attachments: ImmutableList<UiMediaAttachment>,
    onImageTap: (images: List<UiMediaAttachment>, index: Int) -> Unit,
    modifier: Modifier = Modifier,
    images: @Composable (tap: ImageTap, modifier: Modifier) -> Unit = { tap, cell -> ChatImageAttachmentsGrid(tap, cell) },
) {
    if (attachments.isEmpty()) return
    val (pictures, media) = remember(attachments) {
        attachments.partition { it.kind == MediaKind.Image }.let { (p, m) -> p.toImmutableList() to m.toImmutableList() }
    }
    val tap = remember(pictures, onImageTap) { ImageTap(pictures, onImageTap) }
    if (media.isEmpty()) {
        images(tap, modifier)
        return
    }
    val shape = attachmentShape()
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(LettaDimens.Space.sm)) {
        if (pictures.isNotEmpty()) images(tap, Modifier.fillMaxWidth())
        media.forEach { attachment -> ChatMediaCard(attachment, shape, Modifier.fillMaxWidth()) }
    }
}
