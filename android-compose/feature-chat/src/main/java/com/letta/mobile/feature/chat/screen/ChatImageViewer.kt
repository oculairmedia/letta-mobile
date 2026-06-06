package com.letta.mobile.feature.chat.screen

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.MediaStore
import android.util.Base64
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.core.content.FileProvider
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.letta.mobile.data.model.UiImageAttachment
import com.letta.mobile.feature.chat.R
import com.letta.mobile.ui.icons.LettaIcons
import java.io.File
import kotlinx.collections.immutable.ImmutableList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs

private const val MinImageScale = 1f
private const val DoubleTapImageScale = 2.5f
private const val MaxImageScale = 5f
private const val SwipeDismissThresholdPx = 160f

@Composable
internal fun ChatImageViewer(
    attachments: ImmutableList<UiImageAttachment>,
    initialPage: Int,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (attachments.isEmpty()) return

    BackHandler(onBack = onDismiss)

    val pagerState = rememberPagerState(
        initialPage = initialPage.coerceIn(0, attachments.lastIndex),
        pageCount = { attachments.size },
    )
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var verticalDragDistance by remember { mutableFloatStateOf(0f) }
    val shareImageDescription = stringResource(R.string.action_share_image)
    val saveImageDescription = stringResource(R.string.action_save_image)
    val closeImageViewerDescription = stringResource(R.string.action_close)
    val fullscreenImageDescription = stringResource(R.string.screen_chat_fullscreen_image)
    val imageUnavailableText = stringResource(R.string.screen_chat_image_unavailable)

    Surface(
        modifier = modifier
            .background(Color.Black)
            .pointerInput(Unit) {
                detectVerticalDragGestures(
                    onDragEnd = {
                        if (abs(verticalDragDistance) > SwipeDismissThresholdPx) {
                            onDismiss()
                        }
                        verticalDragDistance = 0f
                    },
                    onDragCancel = { verticalDragDistance = 0f },
                    onVerticalDrag = { _, dragAmount ->
                        verticalDragDistance += dragAmount
                    },
                )
            },
        color = Color.Black,
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize(),
            ) { page ->
                ZoomableAttachmentImage(
                    attachment = attachments[page],
                    page = page,
                    contentDescription = fullscreenImageDescription,
                    unavailableText = imageUnavailableText,
                    modifier = Modifier.fillMaxSize(),
                )
            }

            Row(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "${pagerState.currentPage + 1} / ${attachments.size}",
                    style = MaterialTheme.typography.labelLarge,
                    color = Color.White,
                    modifier = Modifier
                        .background(Color.Black.copy(alpha = 0.45f), MaterialTheme.shapes.small)
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ViewerActionButton(
                        icon = LettaIcons.Share,
                        contentDescription = shareImageDescription,
                        onClick = {
                            scope.launch {
                                shareAttachment(context, attachments[pagerState.currentPage])
                            }
                        },
                    )
                    ViewerActionButton(
                        icon = LettaIcons.Save,
                        contentDescription = saveImageDescription,
                        onClick = {
                            scope.launch {
                                saveAttachment(context, attachments[pagerState.currentPage])
                            }
                        },
                    )
                    ViewerActionButton(
                        icon = LettaIcons.Close,
                        contentDescription = closeImageViewerDescription,
                        onClick = onDismiss,
                    )
                }
            }
        }
    }
}

@Composable
private fun ViewerActionButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
) {
    IconButton(
        onClick = onClick,
        modifier = Modifier
            .size(48.dp)
            .background(Color.Black.copy(alpha = 0.45f), MaterialTheme.shapes.small)
            .semantics { this.contentDescription = contentDescription },
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = Color.White,
        )
    }
}

@Composable
private fun ZoomableAttachmentImage(
    attachment: UiImageAttachment,
    page: Int,
    contentDescription: String,
    unavailableText: String,
    modifier: Modifier = Modifier,
) {
    var scale by remember(page) { mutableFloatStateOf(MinImageScale) }
    var offset by remember(page) { mutableStateOf(Offset.Zero) }
    LaunchedEffect(page) {
        scale = MinImageScale
        offset = Offset.Zero
    }

    val imageBitmap = remember(attachment.base64) {
        runCatching {
            val bytes = Base64.decode(attachment.base64, Base64.DEFAULT)
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
        }.getOrNull()
    }

    Box(
        modifier = modifier
            .pointerInput(page) {
                detectTapGestures(
                    onDoubleTap = {
                        if (scale > MinImageScale) {
                            scale = MinImageScale
                            offset = Offset.Zero
                        } else {
                            scale = DoubleTapImageScale
                        }
                    },
                )
            }
            .pointerInput(page) {
                detectTransformGestures { _, pan, zoom, _ ->
                    val nextScale = (scale * zoom).coerceIn(MinImageScale, MaxImageScale)
                    scale = nextScale
                    offset = if (nextScale == MinImageScale) {
                        Offset.Zero
                    } else {
                        offset + pan
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        if (imageBitmap != null) {
            Image(
                bitmap = imageBitmap,
                contentDescription = contentDescription,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                        translationX = offset.x
                        translationY = offset.y
                    },
            )
        } else {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = unavailableText,
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.White,
                )
                Text(
                    text = attachment.mediaType,
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.72f),
                )
            }
        }
    }
}

private suspend fun saveAttachment(context: Context, attachment: UiImageAttachment) {
    val saved = withContext(Dispatchers.IO) { saveAttachmentToMediaStore(context, attachment) }
    context.toast(
        if (saved) {
            context.getString(R.string.screen_chat_image_saved)
        } else {
            context.getString(R.string.screen_chat_image_save_error)
        }
    )
}

private suspend fun shareAttachment(context: Context, attachment: UiImageAttachment) {
    val uri = withContext(Dispatchers.IO) { writeAttachmentForSharing(context, attachment) }
    if (uri == null) {
        context.toast(context.getString(R.string.screen_chat_image_share_error))
        return
    }
    context.startActivity(
        Intent.createChooser(
            attachment.shareIntent(uri),
            context.getString(R.string.screen_chat_image_share_chooser),
        )
    )
}

private fun saveAttachmentToMediaStore(context: Context, attachment: UiImageAttachment): Boolean {
    val bytes = attachment.decodeBytesOrNull() ?: return false
    val resolver = context.contentResolver
    var uri: Uri? = null
    return runCatching {
        val filename = attachment.imageFilename()
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, filename)
            put(MediaStore.Images.Media.MIME_TYPE, attachment.mediaType)
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Letta")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
        val imageUri = uri ?: return false
        resolver.openOutputStream(imageUri)?.use { output -> output.write(bytes) }
            ?: return false
        ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }.also { completeValues ->
            resolver.update(imageUri, completeValues, null, null)
        }
        true
    }.getOrElse {
        uri?.let { imageUri -> runCatching { resolver.delete(imageUri, null, null) } }
        false
    }
}

private fun writeAttachmentForSharing(context: Context, attachment: UiImageAttachment): Uri? = runCatching {
    val bytes = attachment.decodeBytesOrNull() ?: return null
    val imageDir = File(context.cacheDir, "shared-chat-images").apply { mkdirs() }
    val imageFile = File(imageDir, attachment.imageFilename())
    imageFile.writeBytes(bytes)
    FileProvider.getUriForFile(context, "${context.packageName}.chat-image-share", imageFile)
}.getOrNull()

private fun UiImageAttachment.shareIntent(uri: Uri): Intent = Intent(Intent.ACTION_SEND).apply {
    type = mediaType
    putExtra(Intent.EXTRA_STREAM, uri)
    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
}

private fun UiImageAttachment.decodeBytesOrNull(): ByteArray? = runCatching {
    Base64.decode(base64, Base64.DEFAULT)
}.getOrNull()

private fun UiImageAttachment.imageFilename(): String =
    "letta-image-${System.currentTimeMillis()}.${mediaType.fileExtension()}"

private fun String.fileExtension(): String = when (substringAfter('/').substringBefore(';').lowercase()) {
    "jpeg" -> "jpg"
    "png" -> "png"
    "webp" -> "webp"
    "gif" -> "gif"
    else -> "png"
}

private fun Context.toast(message: String) {
    Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
}
