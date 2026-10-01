package com.letta.mobile.ui.chat.surface.composer

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.letta.mobile.data.model.MessageContentPart
import com.letta.mobile.sharedui.resources.Res
import com.letta.mobile.sharedui.resources.composer_close
import com.letta.mobile.sharedui.resources.composer_preview_attachment
import com.letta.mobile.sharedui.resources.composer_remove_attachment
import com.letta.mobile.ui.icons.LettaIcons
import com.letta.mobile.ui.image.decodeImageBitmap
import com.letta.mobile.ui.theme.ChatComposerDimens
import com.letta.mobile.ui.theme.LettaDimens
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import org.jetbrains.compose.resources.stringResource

/**
 * The images staged on the draft: thumbnails with a remove badge; tapping one previews it.
 * Lifted from Android's AttachmentStrip / AttachmentThumbnail / AttachmentPreviewDialog (the
 * desktop chip showed a glyph-sized image, which this replaces on both platforms).
 */
@Composable
internal fun ComposerAttachmentStrip(
    attachments: List<MessageContentPart.Image>,
    onRemove: (Int) -> Unit,
) {
    if (attachments.isEmpty()) return
    var preview by remember { mutableStateOf<MessageContentPart.Image?>(null) }
    LazyRow(
        modifier = Modifier.fillMaxWidth().testTag(ComposerTestTags.ATTACHMENTS),
        horizontalArrangement = Arrangement.spacedBy(LettaDimens.Space.sm),
    ) {
        itemsIndexed(attachments, key = { index, image -> "$index-${image.base64.hashCode()}" }) { index, image ->
            AttachmentThumbnail(image = image, onPreview = { preview = image }, onRemove = { onRemove(index) })
        }
    }
    preview?.let { image -> AttachmentPreviewDialog(image = image, onDismiss = { preview = null }) }
}

@Composable
private fun AttachmentThumbnail(
    image: MessageContentPart.Image,
    onPreview: () -> Unit,
    onRemove: () -> Unit,
) {
    val bitmap = rememberAttachmentBitmap(image.base64)
    Box(modifier = Modifier.size(ChatComposerDimens.attachmentThumbnail)) {
        Surface(
            onClick = onPreview,
            modifier = Modifier.size(ChatComposerDimens.attachmentThumbnail),
            shape = RoundedCornerShape(LettaDimens.Radius.sm),
            color = MaterialTheme.colorScheme.surfaceVariant,
        ) {
            AttachmentImage(bitmap = bitmap, contentScale = ContentScale.Crop)
        }
        Surface(
            modifier = Modifier.size(LettaDimens.Control.iconButtonSm).align(Alignment.TopEnd).clip(CircleShape),
            color = MaterialTheme.colorScheme.errorContainer,
            shape = CircleShape,
        ) {
            IconButton(onClick = onRemove, modifier = Modifier.size(LettaDimens.Control.iconButtonSm)) {
                Icon(
                    imageVector = LettaIcons.Close,
                    contentDescription = stringResource(Res.string.composer_remove_attachment),
                    modifier = Modifier.size(LettaDimens.Control.iconSm),
                )
            }
        }
    }
}

@Composable
private fun AttachmentImage(bitmap: ImageBitmap?, contentScale: ContentScale) {
    if (bitmap == null) {
        Box(modifier = Modifier.fillMaxSize())
        return
    }
    Image(
        bitmap = bitmap,
        contentDescription = stringResource(Res.string.composer_preview_attachment),
        modifier = Modifier.fillMaxSize(),
        contentScale = contentScale,
    )
}

@Composable
private fun AttachmentPreviewDialog(image: MessageContentPart.Image, onDismiss: () -> Unit) {
    val bitmap = rememberAttachmentBitmap(image.base64)
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.scrim.copy(alpha = ChatComposerDimens.previewScrimAlpha))
                .padding(LettaDimens.Space.xl),
            contentAlignment = Alignment.Center,
        ) {
            Surface(
                modifier = Modifier.fillMaxSize(),
                shape = RoundedCornerShape(LettaDimens.Radius.md),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
            ) {
                AttachmentImage(bitmap = bitmap, contentScale = ContentScale.Fit)
            }
            IconButton(onClick = onDismiss, modifier = Modifier.align(Alignment.TopEnd).padding(LettaDimens.Space.sm)) {
                Icon(
                    imageVector = LettaIcons.Close,
                    contentDescription = stringResource(Res.string.composer_close),
                    tint = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}

@OptIn(ExperimentalEncodingApi::class)
@Composable
private fun rememberAttachmentBitmap(base64: String): ImageBitmap? = remember(base64) {
    runCatching { decodeImageBitmap(Base64.Default.decode(base64.filterNot(Char::isWhitespace))) }.getOrNull()
}
