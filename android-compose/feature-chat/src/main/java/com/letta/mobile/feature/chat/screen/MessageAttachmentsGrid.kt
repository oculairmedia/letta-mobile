package com.letta.mobile.feature.chat.screen

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.letta.mobile.data.model.UiImageAttachment
import com.letta.mobile.feature.chat.R
import com.letta.mobile.ui.image.decodeImageBitmap

/**
 * Renders attached images for a chat bubble. Up to 4 images per row in a wrap-
 * style flow; for 1 image we use full-width, 2-up for 2, 3-up for 3+. Each
 * thumbnail delegates base64 decoding to Coil so image work stays off the
 * Compose/UI thread and can be downsampled/cached by the image pipeline.
 */
@Composable
internal fun MessageAttachmentsGrid(
    attachments: kotlinx.collections.immutable.ImmutableList<UiImageAttachment>,
    modifier: Modifier = Modifier,
    // letta-mobile-1k3ge restore: tap an attachment to open the fullscreen
    // viewer. The Int is the tapped attachment's index in [attachments] so the
    // viewer can open the pager on that image. Null = not tappable.
    onImageClick: ((Int) -> Unit)? = null,
) {
    if (attachments.isEmpty()) return

    when (attachments.size) {
        1 -> SingleImage(
            attachment = attachments.first(),
            modifier = modifier,
            onClick = onImageClick?.let { cb -> { cb(0) } },
        )
        2 -> Row(
            modifier = modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            attachments.forEachIndexed { index, attachment ->
                ImageCell(
                    attachment = attachment,
                    modifier = Modifier.weight(1f),
                    onClick = onImageClick?.let { cb -> { cb(index) } },
                )
            }
        }
        else -> Row(
            modifier = modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            attachments.take(3).forEachIndexed { index, attachment ->
                ImageCell(
                    attachment = attachment,
                    modifier = Modifier.weight(1f),
                    onClick = onImageClick?.let { cb -> { cb(index) } },
                )
            }
        }
    }
}

@Composable
private fun SingleImage(
    attachment: UiImageAttachment,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
) {
    AttachmentImage(
        attachment = attachment,
        modifier = modifier.fillMaxWidth().height(220.dp),
        onClick = onClick,
    )
}

@Composable
private fun ImageCell(
    attachment: UiImageAttachment,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
) {
    AttachmentImage(
        attachment = attachment,
        modifier = modifier.height(120.dp),
        onClick = onClick,
    )
}

@Composable
private fun AttachmentImage(
    attachment: UiImageAttachment,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
) {
    // letta-mobile-v4f9: Coil 3.4's BitmapFetcher silently returns null
    // for `data(ByteArray)`, so AsyncImage paints an empty square. The
    // base64 payload is already in memory and bounded by the composer's
    // attachment caps — decode straight to a Bitmap and use Compose's
    // native Image.
    val imageBitmap = remember(attachment.base64) {
        runCatching {
            val bytes = android.util.Base64.decode(attachment.base64, android.util.Base64.DEFAULT)
            decodeImageBitmap(bytes)
        }.getOrNull()
    }

    val openImageDescription = stringResource(R.string.action_open_image)
    val interactiveModifier = if (onClick != null) {
        modifier
            .semantics { contentDescription = openImageDescription }
            .clickable(onClick = onClick)
    } else {
        modifier
    }
    Surface(
        modifier = interactiveModifier,
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(8.dp),
    ) {
        if (imageBitmap != null) {
            Image(
                bitmap = imageBitmap,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        }
    }
}

internal fun chatAttachmentImageDataUrl(base64: String, mediaType: String): String =
    "data:$mediaType;base64,$base64"

internal fun chatAttachmentImageCacheKey(base64: String, mediaType: String): String =
    "chat-attachment-image:$mediaType:${base64.length}:${base64.hashCode()}"
