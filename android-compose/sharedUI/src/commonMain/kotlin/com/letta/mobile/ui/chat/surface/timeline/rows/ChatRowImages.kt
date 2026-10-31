package com.letta.mobile.ui.chat.surface.timeline.rows

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.constrainHeight
import com.letta.mobile.data.model.UiImageAttachment
import com.letta.mobile.sharedui.resources.Res
import com.letta.mobile.sharedui.resources.rows_attached_image
import com.letta.mobile.sharedui.resources.rows_image_not_loaded
import com.letta.mobile.sharedui.resources.rows_more_count
import com.letta.mobile.ui.chat.surface.rememberDecodedImage
import com.letta.mobile.ui.chat.surface.touchStyle
import com.letta.mobile.ui.theme.ChatRowDimens
import com.letta.mobile.ui.theme.LettaDimens
import kotlinx.collections.immutable.ImmutableList
import org.jetbrains.compose.resources.stringResource
import kotlin.math.roundToInt

/**
 * Decodes a base64 attachment once per payload. Null for an empty (stored-pointer) or
 * undecodable payload, so a bad image costs its cell, not the row.
 */
@Composable
internal fun rememberAttachmentBitmap(attachment: UiImageAttachment): ImageBitmap? =
    rememberDecodedImage(attachment.base64)

/** Tap target for an image inside a row: opens the page's viewer on [index] of [images]. */
@Stable
internal class ImageTap(
    val images: ImmutableList<UiImageAttachment>,
    val onTap: (images: List<UiImageAttachment>, index: Int) -> Unit,
) {
    fun open(index: Int) = onTap(images, index)
}

/**
 * Lifted from desktop's DesktopImageAttachmentsGrid, with Android's
 * tap-to-open-viewer (MessageAttachmentsGrid) routed through the page's single viewer.
 */
@Composable
internal fun ChatImageAttachmentsGrid(
    tap: ImageTap,
    modifier: Modifier = Modifier,
) {
    val images = tap.images
    if (images.isEmpty()) return
    // letta-mobile-bglj6.1.23: a phone draws an assistant's images as the legacy bubble did
    // (MessageAttachmentsGrid, the same grid a prompt uses): 120dp cells, a 4dp gap.
    if (touchStyle()) {
        ChatPromptImageGrid(tap, modifier)
        return
    }
    val cellHeight = if (images.size == 1) null else ChatRowDimens.imageGridHeight
    val openLabel = stringResource(Res.string.rows_attached_image)
    Row(
        modifier = modifier.testTag(ChatRowTestTags.IMAGE_GRID),
        horizontalArrangement = Arrangement.spacedBy(LettaDimens.Space.sm),
    ) {
        images.take(ChatRowDimens.gridMaxImages).forEachIndexed { index, attachment ->
            ChatAttachmentImage(
                attachment = attachment,
                cellHeight = cellHeight,
                modifier = Modifier
                    .weight(1f)
                    .clickable(onClickLabel = openLabel) { tap.open(index) },
            )
        }
    }
}

/**
 * A prompt's images on a phone, as the legacy Android bubble drew them
 * (feature-chat MessageAttachmentsGrid): one image across the bubble's width, two side by side,
 * three or more three up, each opening the page's viewer.
 */
@Composable
internal fun ChatPromptImageGrid(
    tap: ImageTap,
    modifier: Modifier = Modifier,
) {
    val images = tap.images
    if (images.isEmpty()) return
    val single = images.size == 1
    val openLabel = stringResource(Res.string.rows_attached_image)
    Row(
        modifier = modifier.testTag(ChatRowTestTags.IMAGE_GRID),
        horizontalArrangement = Arrangement.spacedBy(LettaDimens.Space.xs),
    ) {
        images.take(ChatRowDimens.promptGridMaxImages).forEachIndexed { index, attachment ->
            ChatAttachmentImage(
                attachment = attachment,
                cellHeight = if (single) null else ChatRowDimens.promptGridCellHeight,
                modifier = Modifier
                    .weight(1f)
                    .clickable(onClickLabel = openLabel) { tap.open(index) },
            )
        }
    }
}

/**
 * Compact thumbnails for the pinned prompt card, where a full grid would blow the fold out.
 */
@Composable
internal fun ChatImageThumbnailStrip(
    tap: ImageTap,
    modifier: Modifier = Modifier,
) {
    val images = tap.images
    if (images.isEmpty()) return
    val openLabel = stringResource(Res.string.rows_attached_image)
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(LettaDimens.Space.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        images.take(ChatRowDimens.stripMaxImages).forEachIndexed { index, attachment ->
            ChatAttachmentImage(
                attachment = attachment,
                cellHeight = ChatRowDimens.thumbnailHeight,
                modifier = Modifier
                    .size(width = ChatRowDimens.thumbnailWidth, height = ChatRowDimens.thumbnailHeight)
                    .clickable(onClickLabel = openLabel) { tap.open(index) },
            )
        }
        val hidden = images.size - ChatRowDimens.stripMaxImages
        if (hidden > 0) {
            Text(
                text = stringResource(Res.string.rows_more_count, hidden),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
internal fun ChatAttachmentImage(
    attachment: UiImageAttachment,
    modifier: Modifier = Modifier,
    cellHeight: Dp? = null,
) {
    val bitmap = rememberAttachmentBitmap(attachment)
    // Unknown aspect (loading, failure or stored pointer) keeps the old 220dp placeholder.
    // Fixed cells are reserved for multiple images and the compact thumbnail strip.
    val size = when {
        cellHeight != null -> Modifier.height(cellHeight)
        bitmap == null -> Modifier.height(ChatRowDimens.imageSingleHeight)
        else -> Modifier.attachmentAspectSize(bitmap)
    }
    // Touch: the legacy cell (Radius.sm, no border); the pointer host keeps its outlined card.
    val touch = touchStyle()
    Surface(
        modifier = modifier.then(size),
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = if (touch) RoundedCornerShape(LettaDimens.Radius.sm) else MaterialTheme.shapes.medium,
        border = if (touch) null else BorderStroke(LettaDimens.Stroke.hairline, MaterialTheme.colorScheme.outlineVariant),
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap,
                // The only cue that an image is attached: unnamed, it is invisible to a
                // screen reader rather than decorative.
                contentDescription = stringResource(Res.string.rows_attached_image),
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit,
            )
        } else if (attachment.storedByteSize != null) {
            Box(contentAlignment = Alignment.Center) {
                Text(
                    text = stringResource(Res.string.rows_image_not_loaded),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(LettaDimens.Space.xs),
                )
            }
        }
    }
}

/** A single image owns its column width; only portrait images need a letterboxed height cap. */
private fun Modifier.attachmentAspectSize(bitmap: ImageBitmap): Modifier = fillMaxWidth().layout { measurable, constraints ->
    val naturalHeight = (constraints.maxWidth.toFloat() * bitmap.height / bitmap.width).roundToInt()
    val cappedHeight = if (bitmap.height > bitmap.width) {
        minOf(naturalHeight, ChatRowDimens.imageSingleMaxHeight.roundToPx())
    } else {
        naturalHeight
    }
    val height = constraints.constrainHeight(cappedHeight)
    val placeable = measurable.measure(constraints.copy(minHeight = height, maxHeight = height))
    layout(placeable.width, placeable.height) { placeable.placeRelative(0, 0) }
}
