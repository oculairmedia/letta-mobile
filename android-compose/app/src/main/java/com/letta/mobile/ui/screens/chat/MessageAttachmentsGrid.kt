package com.letta.mobile.ui.screens.chat

import android.graphics.BitmapFactory
import android.util.Base64
import androidx.compose.foundation.Image
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.letta.mobile.data.model.UiImageAttachment

/**
 * Renders attached images for a chat bubble. Up to 4 images per row in a wrap-
 * style flow; for 1 image we use full-width, 2-up for 2, 3-up for 3+. Each
 * thumbnail decodes its base64 lazily and caches via remember.
 */
@Composable
fun MessageAttachmentsGrid(
    attachments: List<UiImageAttachment>,
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
    val bitmap = remember(attachment.base64) { decodeBase64Image(attachment.base64) }
    if (bitmap != null) {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = null,
            modifier = modifier
                .fillMaxWidth()
                .height(220.dp)
                .clip(RoundedCornerShape(8.dp)),
            contentScale = ContentScale.Crop,
        )
    } else {
        Placeholder(modifier = modifier.fillMaxWidth().height(220.dp))
    }
}

@Composable
private fun ImageCell(attachment: UiImageAttachment, modifier: Modifier = Modifier) {
    val bitmap = remember(attachment.base64) { decodeBase64Image(attachment.base64) }
    if (bitmap != null) {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = null,
            modifier = modifier
                .height(120.dp)
                .clip(RoundedCornerShape(8.dp)),
            contentScale = ContentScale.Crop,
        )
    } else {
        Placeholder(modifier = modifier.height(120.dp))
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

private fun decodeBase64Image(base64: String): android.graphics.Bitmap? = runCatching {
    val bytes = Base64.decode(base64, Base64.NO_WRAP)
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
}.getOrNull()
