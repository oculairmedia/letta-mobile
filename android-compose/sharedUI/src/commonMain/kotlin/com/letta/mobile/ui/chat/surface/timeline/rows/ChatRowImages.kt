package com.letta.mobile.ui.chat.surface.timeline.rows

import com.letta.mobile.ui.theme.ChatRowInsetShape
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import coil3.compose.AsyncImage
import com.letta.mobile.data.attachment.ImageUnavailableReason
import androidx.compose.runtime.Stable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
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
import com.letta.mobile.sharedui.resources.rows_image_missing
import com.letta.mobile.sharedui.resources.rows_image_invalid
import com.letta.mobile.sharedui.resources.rows_image_too_large
import com.letta.mobile.sharedui.resources.rows_image_unsupported
import com.letta.mobile.sharedui.resources.rows_more_count
import com.letta.mobile.ui.chat.surface.rememberDecodedImageResult
import com.letta.mobile.ui.chat.surface.rememberDecodedImage
import com.letta.mobile.ui.chat.surface.touchStyle
import com.letta.mobile.ui.theme.ChatRowDimens
import com.letta.mobile.ui.theme.LettaDimens
import kotlinx.collections.immutable.ImmutableList
import org.jetbrains.compose.resources.stringResource
import kotlin.math.roundToInt
import com.letta.mobile.ui.components.foundation.lettaInlineTarget
import com.letta.mobile.ui.components.foundation.lettaClickable

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
                    .lettaClickable(
                        role = Role.Image,
                        onClickLabel = openLabel,
                        target = lettaInlineTarget(attachmentShape()),
                        onClick = { tap.open(index) },
                    ),
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
                    .lettaClickable(
                        role = Role.Image,
                        onClickLabel = openLabel,
                        target = lettaInlineTarget(attachmentShape()),
                        onClick = { tap.open(index) },
                    ),
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
                    .lettaClickable(
                        role = Role.Image,
                        onClickLabel = openLabel,
                        target = lettaInlineTarget(attachmentShape()),
                        onClick = { tap.open(index) },
                    ),
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

/** An attachment cell's shape (Touch: the legacy inset cell; pointer: the outlined card), its press clipped to it too. */
@Composable
private fun attachmentShape(): Shape = if (touchStyle()) ChatRowInsetShape else MaterialTheme.shapes.medium

@Composable
internal fun ChatAttachmentImage(
    attachment: UiImageAttachment,
    modifier: Modifier = Modifier,
    cellHeight: Dp? = null,
) {
    val decoded = rememberDecodedImageResult(attachment.base64)
    val bitmap = decoded?.getOrNull()
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
        shape = attachmentShape(),
        border = if (touch) null else BorderStroke(LettaDimens.Stroke.hairline, MaterialTheme.colorScheme.outlineVariant),
    ) {
        if (attachment.url != null) {
            ChatRemoteImage(attachment, Modifier.fillMaxSize())
        } else if (bitmap != null) {
            Image(
                bitmap = bitmap,
                // The only cue that an image is attached: unnamed, it is invisible to a
                // screen reader rather than decorative.
                contentDescription = stringResource(Res.string.rows_attached_image),
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit,
            )
        } else {
            val unavailable = attachment.unavailableReason
            val reason = when {
                unavailable != null -> imageFailureResource(unavailable)
                attachment.storedByteSize != null -> Res.string.rows_image_not_loaded
                attachment.base64.isBlank() -> imageFailureResource(ImageUnavailableReason.Missing)
                decoded?.isFailure == true -> imageFailureResource(ImageUnavailableReason.Invalid)
                else -> null
            }
            if (reason != null) Box(contentAlignment = Alignment.Center) {
                Text(
                    text = stringResource(reason),
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
    // A very wide image still keeps a visible, tappable strip (it fits whole, letterboxed).
    val height = constraints.constrainHeight(maxOf(cappedHeight, LettaDimens.Control.minTouchTarget.roundToPx()))
    val placeable = measurable.measure(constraints.copy(minHeight = height, maxHeight = height))
    layout(placeable.width, placeable.height) { placeable.placeRelative(0, 0) }
}

/** Shared thumbnail/viewer path for HTTP sources supported by the host's Coil loader. */
@Composable
internal fun ChatRemoteImage(attachment: UiImageAttachment, modifier: Modifier = Modifier) {
    var failed by remember(attachment.url) { mutableStateOf(false) }
    Box(modifier, contentAlignment = Alignment.Center) {
        AsyncImage(
            model = attachment.url,
            contentDescription = stringResource(Res.string.rows_attached_image),
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize(),
            onError = { failed = true },
            onSuccess = { failed = false },
        )
        if (failed) Text(stringResource(imageFailureResource(ImageUnavailableReason.Invalid)))
    }
}

private fun imageFailureResource(reason: ImageUnavailableReason) = when (reason) {
    ImageUnavailableReason.Missing -> Res.string.rows_image_missing
    ImageUnavailableReason.Invalid -> Res.string.rows_image_invalid
    ImageUnavailableReason.TooLarge -> Res.string.rows_image_too_large
    ImageUnavailableReason.UnsupportedSource -> Res.string.rows_image_unsupported
}
