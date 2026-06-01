package com.letta.mobile.feature.chat

import android.graphics.BitmapFactory
import android.util.Base64
import androidx.activity.compose.BackHandler
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.letta.mobile.data.model.UiImageAttachment
import com.letta.mobile.ui.icons.LettaIcons
import kotlinx.collections.immutable.ImmutableList
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
    var verticalDragDistance by remember { mutableFloatStateOf(0f) }

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
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier
                        .size(48.dp)
                        .background(Color.Black.copy(alpha = 0.45f), MaterialTheme.shapes.small)
                        .semantics { contentDescription = "Close image viewer" },
                ) {
                    Icon(
                        imageVector = LettaIcons.Close,
                        contentDescription = null,
                        tint = Color.White,
                    )
                }
            }
        }
    }
}

@Composable
private fun ZoomableAttachmentImage(
    attachment: UiImageAttachment,
    page: Int,
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
                contentDescription = "Fullscreen chat image",
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
                    text = "Image unavailable",
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
