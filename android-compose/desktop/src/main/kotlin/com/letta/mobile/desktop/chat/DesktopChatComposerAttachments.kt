package com.letta.mobile.desktop.chat

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.foundation.background
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import com.letta.mobile.data.model.MessageContentPart
import com.letta.mobile.data.model.UiImageAttachment
import com.letta.mobile.desktop.DesktopControlText
import java.util.Base64
import org.jetbrains.jewel.ui.component.PopupMenu as JewelPopupMenu
import org.jetbrains.skia.Image as SkiaImage

/** Bundled label/options/select for [ComposerDropdownChip]. */
internal data class ComposerDropdownChipModel(
    val label: String,
    val options: List<String>,
    val onSelect: (String) -> Unit,
    val leadingIcon: ImageVector? = null,
    val contentColor: Color? = null,
    val emptyHint: String? = null,
)

/**
 * A functional pill selector in the composer control row (model / safety /
 * effort): click opens a popup of [options]; selecting one fires [onSelect].
 */
@Composable
internal fun ComposerDropdownChip(model: ComposerDropdownChipModel) {
    val contentColor = model.contentColor ?: MaterialTheme.colorScheme.onSurface
    var open by remember { mutableStateOf(false) }
    Box {
        Surface(
            onClick = { open = !open },
            shape = RoundedCornerShape(8.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            contentColor = contentColor,
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                horizontalArrangement = Arrangement.spacedBy(5.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (model.leadingIcon != null) {
                    Icon(
                        imageVector = model.leadingIcon,
                        contentDescription = null,
                        modifier = Modifier.size(13.dp),
                        tint = contentColor,
                    )
                }
                Text(
                    text = model.label,
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1,
                )
                Icon(
                    imageVector = Icons.Outlined.KeyboardArrowDown,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (open) {
            JewelPopupMenu(
                onDismissRequest = {
                    open = false
                    true
                },
                horizontalAlignment = Alignment.Start,
            ) {
                if (model.options.isEmpty() && model.emptyHint != null) {
                    selectableItem(selected = false, onClick = { open = false }) {
                        DesktopControlText(model.emptyHint)
                    }
                }
                model.options.forEach { option ->
                    selectableItem(
                        selected = option == model.label,
                        onClick = {
                            open = false
                            model.onSelect(option)
                        },
                    ) {
                        DesktopControlText(option)
                    }
                }
            }
        }
    }
}

@Composable
internal fun ComposerAttachmentChip(
    image: MessageContentPart.Image,
    onRemove: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Row(
            modifier = Modifier.padding(start = 6.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            DesktopAttachmentImage(
                attachment = UiImageAttachment(base64 = image.base64, mediaType = image.mediaType),
                modifier = Modifier.size(18.dp),
            )
            Text(
                text = "image",
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
            )
            Icon(
                imageVector = Icons.Outlined.Close,
                contentDescription = "Remove attachment",
                modifier = Modifier
                    .size(14.dp)
                    .clickable(onClick = onRemove),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
internal fun DesktopImageAttachmentsGrid(
    attachments: List<UiImageAttachment>,
    modifier: Modifier = Modifier,
) {
    if (attachments.isEmpty()) return
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        val cellHeight = if (attachments.size == 1) 220.dp else 128.dp
        attachments.take(4).forEach { attachment ->
            DesktopAttachmentImage(
                attachment = attachment,
                modifier = Modifier
                    .weight(1f)
                    .height(cellHeight),
            )
        }
    }
}

/**
 * Compact strip of image thumbnails for surfaces where a full grid would
 * dominate (the pinned prompt card): small fixed-height thumbs, click any of
 * them to view the image fullscreen (click or Esc dismisses).
 */
@Composable
internal fun DesktopImageAttachmentThumbnailStrip(
    attachments: List<UiImageAttachment>,
    modifier: Modifier = Modifier,
) {
    if (attachments.isEmpty()) return
    var fullscreen by remember { mutableStateOf<UiImageAttachment?>(null) }
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        attachments.take(6).forEach { attachment ->
            DesktopAttachmentImage(
                attachment = attachment,
                modifier = Modifier
                    .size(width = 56.dp, height = 40.dp)
                    .clickable { fullscreen = attachment },
            )
        }
        if (attachments.size > 6) {
            Text(
                text = "+${attachments.size - 6}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    fullscreen?.let { attachment ->
        DesktopFullscreenImageOverlay(attachment = attachment, onDismiss = { fullscreen = null })
    }
}

/** Window-covering lightbox: dark scrim, image fit within, click/Esc closes. */
@Composable
internal fun DesktopFullscreenImageOverlay(
    attachment: UiImageAttachment,
    onDismiss: () -> Unit,
) {
    val imageBitmap = remember(attachment.base64) {
        runCatching {
            SkiaImage.makeFromEncoded(Base64.getDecoder().decode(attachment.base64)).toComposeImageBitmap()
        }.getOrNull()
    }
    Popup(
        popupPositionProvider = object : PopupPositionProvider {
            override fun calculatePosition(
                anchorBounds: IntRect,
                windowSize: IntSize,
                layoutDirection: LayoutDirection,
                popupContentSize: IntSize,
            ): IntOffset = IntOffset.Zero
        },
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.88f))
                .clickable(onClick = onDismiss)
                .onPreviewKeyEvent { event ->
                    if (event.type == KeyEventType.KeyDown && event.key == Key.Escape) {
                        onDismiss()
                        true
                    } else {
                        false
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            if (imageBitmap != null) {
                Image(
                    bitmap = imageBitmap,
                    contentDescription = "Attachment fullscreen",
                    modifier = Modifier.fillMaxSize().padding(40.dp),
                    contentScale = ContentScale.Fit,
                )
            } else {
                Text(
                    text = "Image could not be decoded",
                    color = Color.White,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

@Composable
internal fun DesktopAttachmentImage(
    attachment: UiImageAttachment,
    modifier: Modifier = Modifier,
) {
    val imageBitmap = remember(attachment.base64) {
        runCatching {
            val bytes = Base64.getDecoder().decode(attachment.base64)
            SkiaImage.makeFromEncoded(bytes).toComposeImageBitmap()
        }.getOrNull()
    }
    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = MaterialTheme.shapes.medium,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
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
