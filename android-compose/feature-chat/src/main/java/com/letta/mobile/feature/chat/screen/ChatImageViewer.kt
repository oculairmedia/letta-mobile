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
import androidx.activity.compose.LocalActivity
import androidx.core.content.FileProvider
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.SemanticsPropertyReceiver
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.letta.mobile.data.model.UiImageAttachment
import com.letta.mobile.feature.chat.R
import com.letta.mobile.ui.icons.LettaIcons
import java.io.File
import kotlinx.collections.immutable.ImmutableList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.max

internal const val MinImageScale = 1f
internal const val DoubleTapImageScale = 2.5f
internal const val MaxImageScale = 5f
internal const val SwipeDismissThresholdPx = 160f
internal val ChatImageViewerControlsPadding = 12.dp

internal val ChatImageViewerScaleKey = SemanticsPropertyKey<Float>("ChatImageViewerScale")
internal var SemanticsPropertyReceiver.chatImageViewerScale by ChatImageViewerScaleKey
internal val ChatImageViewerTranslationXKey = SemanticsPropertyKey<Float>("ChatImageViewerTranslationX")
internal var SemanticsPropertyReceiver.chatImageViewerTranslationX by ChatImageViewerTranslationXKey
internal val ChatImageViewerTranslationYKey = SemanticsPropertyKey<Float>("ChatImageViewerTranslationY")
internal var SemanticsPropertyReceiver.chatImageViewerTranslationY by ChatImageViewerTranslationYKey

internal data class ImageTransformState(
    val scale: Float = MinImageScale,
    val offset: Offset = Offset.Zero,
)

internal fun applyImageTransformGesture(
    state: ImageTransformState,
    zoom: Float,
    pan: Offset,
    centroid: Offset,
    containerSize: Size,
): ImageTransformState {
    val safeState = sanitizeImageTransform(state, containerSize)
    val safeZoom = zoom.takeIf { it.isFinite() && it > 0f } ?: 1f
    val safePan = pan.takeIfFinite() ?: Offset.Zero
    val containerCenter = containerSize.centerOrZero()
    val safeCentroid = centroid.takeIfFinite() ?: containerCenter
    val previousScale = safeState.scale
    val nextScale = (previousScale * safeZoom).coerceIn(MinImageScale, MaxImageScale)
    if (nextScale <= MinImageScale) return ImageTransformState()

    val centroidShift = safeCentroid - containerCenter
    val scaleRatio = if (previousScale == 0f) 1f else nextScale / previousScale
    val nextOffset = (safeState.offset + centroidShift) * scaleRatio - centroidShift + safePan
    return ImageTransformState(
        scale = nextScale,
        offset = clampImageOffset(nextOffset, nextScale, containerSize),
    )
}

internal fun clampImageOffset(offset: Offset, scale: Float, containerSize: Size): Offset {
    if (
        scale <= MinImageScale ||
        !scale.isFinite() ||
        !containerSize.width.isFinite() ||
        !containerSize.height.isFinite() ||
        containerSize.width <= 0f ||
        containerSize.height <= 0f
    ) return Offset.Zero
    val safeOffset = offset.withFiniteAxes()
    val maxX = ((containerSize.width * scale) - containerSize.width) / 2f
    val maxY = ((containerSize.height * scale) - containerSize.height) / 2f
    if (!maxX.isFinite() || !maxY.isFinite()) return Offset.Zero
    return Offset(
        x = safeOffset.x.coerceIn(-maxX, maxX),
        y = safeOffset.y.coerceIn(-maxY, maxY),
    )
}

internal fun sanitizeImageTransform(state: ImageTransformState, containerSize: Size): ImageTransformState {
    val safeScale = state.scale.takeIf { it.isFinite() }?.coerceIn(MinImageScale, MaxImageScale) ?: MinImageScale
    if (safeScale <= MinImageScale) return ImageTransformState()
    return ImageTransformState(
        scale = safeScale,
        offset = clampImageOffset(state.offset, safeScale, containerSize),
    )
}

private fun Offset.takeIfFinite(): Offset? = takeIf { x.isFinite() && y.isFinite() }

private fun Offset.withFiniteAxes(): Offset = Offset(
    x = x.takeIf { it.isFinite() } ?: 0f,
    y = y.takeIf { it.isFinite() } ?: 0f,
)

private fun Size.centerOrZero(): Offset = if (width.isFinite() && height.isFinite()) {
    Offset(width / 2f, height / 2f)
} else {
    Offset.Zero
}

internal fun doubleTapImageTransform(
    state: ImageTransformState,
    tap: Offset,
    containerSize: Size,
): ImageTransformState = if (state.scale > MinImageScale) {
    ImageTransformState()
} else {
    applyImageTransformGesture(
        state = state,
        zoom = DoubleTapImageScale,
        pan = Offset.Zero,
        centroid = tap,
        containerSize = containerSize,
    )
}

internal fun chatImageViewerTopControlsY(safeDrawingTopPx: Float, density: Float): Float =
    safeDrawingTopPx.coerceAtLeast(0f) +
        (ChatImageViewerControlsPadding.value * density.coerceAtLeast(0f))

internal fun shouldDismissImageViewer(
    scale: Float,
    verticalDragDistance: Float,
): Boolean = scale <= 1.02f && abs(verticalDragDistance) > SwipeDismissThresholdPx

@Composable
internal fun ChatImageViewer(
    attachments: ImmutableList<UiImageAttachment>,
    initialPage: Int,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (attachments.isEmpty()) return

    BackHandler(onBack = onDismiss)
    ChatImageViewerSystemBarsEffect()

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            decorFitsSystemWindows = false,
            usePlatformDefaultWidth = false,
        ),
    ) {
        ChatImageViewerContent(
            attachments = attachments,
            initialPage = initialPage,
            onDismiss = onDismiss,
            modifier = modifier,
        )
    }
}

@Composable
private fun ChatImageViewerContent(
    attachments: ImmutableList<UiImageAttachment>,
    initialPage: Int,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val pagerState = rememberPagerState(
        initialPage = initialPage.coerceIn(0, attachments.lastIndex),
        pageCount = { attachments.size },
    )
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val shareImageDescription = stringResource(R.string.action_share_image)
    val saveImageDescription = stringResource(R.string.action_save_image)
    val closeImageViewerDescription = stringResource(R.string.action_close)
    val fullscreenImageDescription = stringResource(R.string.screen_chat_fullscreen_image)
    val imageUnavailableText = stringResource(R.string.screen_chat_image_unavailable)

    Surface(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black),
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
                    onDismiss = onDismiss,
                    modifier = Modifier.fillMaxSize(),
                )
            }

            Row(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .windowInsetsPadding(
                        WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal),
                    )
                    .padding(ChatImageViewerControlsPadding),
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
private fun ChatImageViewerSystemBarsEffect() {
    val activity = LocalActivity.current ?: return
    DisposableEffect(activity) {
        val window = activity.window
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        val previousBehavior = controller.systemBarsBehavior
        WindowCompat.setDecorFitsSystemWindows(window, false)
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(WindowInsetsCompat.Type.systemBars())
        onDispose {
            controller.show(WindowInsetsCompat.Type.systemBars())
            controller.systemBarsBehavior = previousBehavior
            WindowCompat.setDecorFitsSystemWindows(window, true)
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
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var transform by remember(page) { mutableStateOf(ImageTransformState()) }
    var containerSize by remember(page) { mutableStateOf(IntSize.Zero) }
    LaunchedEffect(page) {
        transform = ImageTransformState()
    }

    val imageBitmap = remember(attachment.base64) {
        runCatching {
            val bytes = Base64.decode(attachment.base64, Base64.DEFAULT)
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
        }.getOrNull()
    }

    Box(
        modifier = modifier
            .onSizeChanged { size ->
                containerSize = size
                transform = sanitizeImageTransform(
                    state = transform,
                    containerSize = Size(size.width.toFloat(), size.height.toFloat()),
                )
            }
            .semantics {
                this.contentDescription = contentDescription
                chatImageViewerScale = transform.scale
                chatImageViewerTranslationX = transform.offset.x
                chatImageViewerTranslationY = transform.offset.y
            }
            .pointerInput(page) {
                detectTapGestures(
                    onDoubleTap = { tap ->
                        transform = doubleTapImageTransform(
                            state = transform,
                            tap = tap,
                            containerSize = Size(containerSize.width.toFloat(), containerSize.height.toFloat()),
                        )
                    },
                )
            }
            .pointerInput(page) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    var verticalDragDistance = 0f
                    var transformed = false
                    var zoomedAtGestureStart = transform.scale > 1.02f
                    var pointersPressed: Boolean
                    do {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        val pressed = event.changes.filter { it.pressed }
                        val zoom = event.calculateZoom()
                        val pan = event.calculatePan()
                        val isTransformGesture = pressed.isNotEmpty() &&
                            (pressed.size >= 2 || zoom != 1f || transform.scale > 1.02f)
                        if (isTransformGesture) {
                            transformed = true
                            val centroid = event.calculateCentroid(useCurrent = true)
                            transform = applyImageTransformGesture(
                                state = transform,
                                zoom = zoom,
                                pan = pan,
                                centroid = centroid,
                                containerSize = Size(containerSize.width.toFloat(), containerSize.height.toFloat()),
                            )
                            event.changes.forEach { it.consume() }
                        } else if (!zoomedAtGestureStart && pressed.size == 1) {
                            verticalDragDistance += pan.y
                            if (abs(pan.y) > abs(pan.x)) {
                                event.changes.forEach { it.consume() }
                            }
                        }
                        zoomedAtGestureStart = zoomedAtGestureStart || transform.scale > 1.02f
                        pointersPressed = event.changes.any { it.pressed }
                    } while (pointersPressed)
                    transform = sanitizeImageTransform(
                        state = transform,
                        containerSize = Size(containerSize.width.toFloat(), containerSize.height.toFloat()),
                    )
                    if (!transformed && shouldDismissImageViewer(transform.scale, verticalDragDistance)) {
                        onDismiss()
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        if (imageBitmap != null) {
            Image(
                bitmap = imageBitmap,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        val appliedScale = max(transform.scale, MinImageScale)
                        scaleX = appliedScale
                        scaleY = appliedScale
                        translationX = transform.offset.x
                        translationY = transform.offset.y
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
