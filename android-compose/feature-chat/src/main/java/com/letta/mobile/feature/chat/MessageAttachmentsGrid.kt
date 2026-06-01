package com.letta.mobile.feature.chat

import android.graphics.BitmapFactory
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.letta.mobile.data.model.UiImageAttachment

/**
 * Renders attached images for a chat bubble. Up to 4 images per row in a wrap-
 * style flow; for 1 image we use full-width, 2-up for 2, 3-up for 3+. Each
 * thumbnail delegates base64 decoding to Coil so image work stays off the
 * Compose/UI thread and can be downsampled/cached by the image pipeline.
 */
@Composable
internal fun MessageAttachmentsGrid(
    attachments: kotlinx.collections.immutable.ImmutableList<UiImageAttachment>,
    onImageClick: ((Int) -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    if (attachments.isEmpty()) return

    when (attachments.size) {
        1 -> SingleImage(
            attachment = attachments.first(),
            onClick = onImageClick?.let { { it(0) } },
            modifier = modifier,
        )
        2 -> Row(
            modifier = modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            attachments.forEachIndexed { index, attachment ->
                ImageCell(
                    attachment = attachment,
                    onClick = onImageClick?.let { { it(index) } },
                    modifier = Modifier.weight(1f),
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
                    onClick = onImageClick?.let { { it(index) } },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun SingleImage(
    attachment: UiImageAttachment,
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    AttachmentImage(
        attachment = attachment,
        onClick = onClick,
        modifier = modifier.fillMaxWidth().height(220.dp),
    )
}

@Composable
private fun ImageCell(
    attachment: UiImageAttachment,
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    AttachmentImage(
        attachment = attachment,
        onClick = onClick,
        modifier = modifier.height(120.dp),
    )
}

@Composable
private fun AttachmentImage(
    attachment: UiImageAttachment,
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    // letta-mobile-v4f9: Coil 3.4's BitmapFetcher silently returns null
    // for `data(ByteArray)`, so AsyncImage paints an empty square. The
    // base64 payload is already in memory and bounded by the composer's
    // attachment caps — decode straight to a Bitmap and use Compose's
    // native Image.
    val imageBitmap = remember(attachment.base64) {
        runCatching {
            val bytes = android.util.Base64.decode(attachment.base64, android.util.Base64.DEFAULT)
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
        }.getOrNull()
    }

    Surface(
        modifier = if (onClick != null) modifier.clickable(onClick = onClick) else modifier,
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

@Composable
private fun Placeholder(modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(8.dp),
    ) {
        // empty placeholder body
        androidx.compose.foundation.layout.Box(modifier = Modifier.fillMaxSize()) {}
    }
}

internal fun chatAttachmentImageDataUrl(base64: String, mediaType: String): String =
    "data:$mediaType;base64,$base64"

internal fun chatAttachmentImageCacheKey(base64: String, mediaType: String): String =
    "chat-attachment-image:$mediaType:${base64.length}:${base64.hashCode()}"
