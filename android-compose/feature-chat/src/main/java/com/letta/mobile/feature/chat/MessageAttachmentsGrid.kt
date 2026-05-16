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
    // letta-mobile-axb2: feed Coil3 a ByteArray rather than a data: URI.
    // Coil3 dropped the Coil2 data:-URI fetcher, so the URI path resolves
    // to a null Bitmap and the AsyncImage renders blank.
    val bytes = remember(attachment.base64) {
        runCatching {
            android.util.Base64.decode(attachment.base64, android.util.Base64.DEFAULT)
        }.getOrDefault(ByteArray(0))
    }
    val request = remember(context, bytes, cacheKey) {
        ImageRequest.Builder(context)
            .data(bytes)
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
