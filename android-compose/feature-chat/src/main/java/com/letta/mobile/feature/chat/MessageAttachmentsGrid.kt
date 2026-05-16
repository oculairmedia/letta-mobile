package com.letta.mobile.feature.chat

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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
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
    modifier: Modifier = Modifier,
) {
    if (attachments.isEmpty()) return

    when (attachments.size) {
        1 -> SingleImage(attachment = attachments.first(), modifier = modifier)
        2 -> Row(
            modifier = modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            attachments.forEach { ImageCell(attachment = it, modifier = Modifier.weight(1f)) }
        }
        else -> Row(
            modifier = modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            attachments.take(3).forEach {
                ImageCell(attachment = it, modifier = Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun SingleImage(attachment: UiImageAttachment, modifier: Modifier = Modifier) {
    AttachmentImage(
        attachment = attachment,
        modifier = modifier.fillMaxWidth().height(220.dp),
    )
}

@Composable
private fun ImageCell(attachment: UiImageAttachment, modifier: Modifier = Modifier) {
    AttachmentImage(
        attachment = attachment,
        modifier = modifier.height(120.dp),
    )
}

@Composable
private fun AttachmentImage(
    attachment: UiImageAttachment,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val cacheKey = remember(attachment.base64, attachment.mediaType) {
        chatAttachmentImageCacheKey(
            base64 = attachment.base64,
            mediaType = attachment.mediaType,
        )
    }
    val dataUrl = remember(attachment.base64, attachment.mediaType) {
        chatAttachmentImageDataUrl(
            base64 = attachment.base64,
            mediaType = attachment.mediaType,
        )
    }
    val request = remember(context, dataUrl, cacheKey) {
        ImageRequest.Builder(context)
            .data(dataUrl)
            .memoryCacheKey(cacheKey)
            .diskCacheKey(cacheKey)
            .build()
    }

    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(8.dp),
    ) {
        AsyncImage(
            model = request,
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop,
        )
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
