package com.letta.mobile.ui.chat.surface.timeline.rows

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.SemanticsPropertyReceiver
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.letta.mobile.data.model.UiImageAttachment
import com.letta.mobile.sharedui.resources.Res
import com.letta.mobile.sharedui.resources.rows_close_viewer
import com.letta.mobile.sharedui.resources.rows_image_counter
import com.letta.mobile.sharedui.resources.rows_image_decode_failed
import com.letta.mobile.sharedui.resources.rows_image_fullscreen
import com.letta.mobile.sharedui.resources.rows_save_image
import com.letta.mobile.sharedui.resources.rows_share_image
import com.letta.mobile.ui.chat.surface.ChatImageActions
import com.letta.mobile.ui.chat.surface.LocalChatImageActions
import com.letta.mobile.ui.chat.surface.touchStyle
import com.letta.mobile.ui.icons.LettaIcons
import com.letta.mobile.ui.theme.ChatRowAlpha
import com.letta.mobile.ui.theme.LettaDimens
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/** The viewed page's zoom, for tests (the legacy viewer's ChatImageViewerScale). */
internal val ChatImageViewerScaleKey = SemanticsPropertyKey<Float>("ChatImageViewerScale")
private var SemanticsPropertyReceiver.chatImageViewerScale by ChatImageViewerScaleKey

/**
 * letta-mobile-bglj6.1: the page's one full-screen image viewer, lifted from desktop's
 * DesktopFullscreenImageOverlay and extended with Android's paging between a message's
 * images. Esc dismisses; arrow keys page.
 *
 * letta-mobile-bglj6.1.23: on a phone it is the legacy black ChatImageViewer: each page pinches
 * to zoom (1-5x), double-taps to 2.5x, pans while zoomed and swipes away vertically at rest, under
 * a top bar with the counter, Share, Save (where the host offers them, [ChatImageActions]) and
 * Close. On a pointer host a click on the scrim dismisses.
 */
@Composable
internal fun ChatImageViewerContent(
    images: List<UiImageAttachment>,
    initialIndex: Int,
    onDismiss: () -> Unit,
) {
    if (images.isEmpty()) return
    val pagerState = rememberPagerState(initialPage = initialIndex.coerceIn(0, images.lastIndex)) { images.size }
    val scope = rememberCoroutineScope()
    val focusRequester = remember { FocusRequester() }
    val touch = touchStyle()
    val imageActions = LocalChatImageActions.current
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        LaunchedEffect(Unit) { runCatching { focusRequester.requestFocus() } }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .testTag(ChatRowTestTags.IMAGE_VIEWER)
                .background(if (touch) Color.Black else MaterialTheme.colorScheme.scrim.copy(alpha = ChatRowAlpha.viewerScrim))
                .then(if (touch) Modifier else Modifier.clickable(onClick = onDismiss))
                .onPreviewKeyEvent { event -> handleViewerKey(event, ViewerKeyTarget(pagerState, scope, onDismiss)) }
                .focusRequester(focusRequester)
                .focusable(),
            contentAlignment = Alignment.Center,
        ) {
            HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize()) { page ->
                ViewerPage(images[page], page, inset = !touch, onDismiss = onDismiss)
            }
            if (touch) {
                TouchViewerChrome(ViewerChromeState(pagerState, images, imageActions), onDismiss)
            } else {
                ViewerChrome(pagerState = pagerState, count = images.size, onDismiss = onDismiss)
            }
        }
    }
}

private class ViewerKeyTarget(
    val pagerState: PagerState,
    val scope: CoroutineScope,
    val onDismiss: () -> Unit,
)

private fun handleViewerKey(
    event: androidx.compose.ui.input.key.KeyEvent,
    target: ViewerKeyTarget,
): Boolean {
    if (event.type != KeyEventType.KeyDown) return false
    val delta = when (event.key) {
        Key.Escape -> {
            target.onDismiss()
            return true
        }
        Key.DirectionLeft -> -1
        Key.DirectionRight -> 1
        else -> return false
    }
    val state = target.pagerState
    val next = (state.currentPage + delta).coerceIn(0, state.pageCount - 1)
    target.scope.launch { state.animateScrollToPage(next) }
    return true
}

/** One image, zoomable: [inset] keeps the pointer host's margin; the phone runs edge to edge. */
@Composable
private fun ViewerPage(attachment: UiImageAttachment, page: Int, inset: Boolean, onDismiss: () -> Unit) {
    val bitmap = rememberAttachmentBitmap(attachment)
    val transform = remember(page) { mutableStateOf(ImageTransformState()) }
    val containerSize = remember(page) { mutableStateOf(Size.Zero) }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .then(if (inset) Modifier.padding(LettaDimens.Orb.lg) else Modifier)
            .onSizeChanged { size ->
                containerSize.value = Size(size.width.toFloat(), size.height.toFloat())
                transform.value = sanitizeImageTransform(transform.value, containerSize.value)
            }
            .semantics { chatImageViewerScale = transform.value.scale }
            // The phone's gestures; the pointer host keeps its click-the-scrim-to-close.
            .then(if (inset) Modifier else Modifier.zoomableImageGestures(page, transform, { containerSize.value }, onDismiss)),
        contentAlignment = Alignment.Center,
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap,
                contentDescription = stringResource(Res.string.rows_image_fullscreen),
                modifier = Modifier.fillMaxSize().graphicsLayer {
                    val state = transform.value
                    scaleX = state.scale
                    scaleY = state.scale
                    translationX = state.offset.x
                    translationY = state.offset.y
                },
                contentScale = ContentScale.Fit,
            )
        } else {
            Text(
                text = stringResource(Res.string.rows_image_decode_failed),
                color = MaterialTheme.colorScheme.inverseOnSurface,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Composable
private fun ViewerChrome(pagerState: PagerState, count: Int, onDismiss: () -> Unit) {
    Box(modifier = Modifier.fillMaxSize().padding(LettaDimens.Space.lg)) {
        IconButton(onClick = onDismiss, modifier = Modifier.align(Alignment.TopEnd)) {
            Icon(
                imageVector = LettaIcons.Close,
                contentDescription = stringResource(Res.string.rows_close_viewer),
                tint = MaterialTheme.colorScheme.inverseOnSurface,
            )
        }
        if (count > 1) {
            Text(
                text = stringResource(Res.string.rows_image_counter, pagerState.currentPage + 1, count),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.inverseOnSurface,
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
    }
}

/** What the phone's top bar reads: the page, the images, and what the host lets it do with them. */
private class ViewerChromeState(
    val pagerState: PagerState,
    val images: List<UiImageAttachment>,
    val actions: ChatImageActions?,
) {
    val current: UiImageAttachment get() = images[pagerState.currentPage.coerceIn(0, images.lastIndex)]
}

/** The legacy phone viewer's top bar: "2 / 3" on the left; Share, Save and Close on the right. */
@Composable
private fun TouchViewerChrome(state: ViewerChromeState, onDismiss: () -> Unit) {
    Box(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
                .padding(LettaDimens.Space.md),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(Res.string.rows_image_counter, state.pagerState.currentPage + 1, state.images.size),
                style = MaterialTheme.typography.labelLarge,
                color = Color.White,
                modifier = Modifier
                    .background(Color.Black.copy(alpha = ViewerControlAlpha), MaterialTheme.shapes.small)
                    .padding(horizontal = LettaDimens.Space.md, vertical = LettaDimens.Space.sm),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(LettaDimens.Space.sm)) {
                state.actions?.let { actions ->
                    ViewerActionButton(LettaIcons.Share, Res.string.rows_share_image) { actions.share(state.current) }
                    ViewerActionButton(LettaIcons.Save, Res.string.rows_save_image) { actions.save(state.current) }
                }
                ViewerActionButton(LettaIcons.Close, Res.string.rows_close_viewer, onDismiss)
            }
        }
    }
}

@Composable
private fun ViewerActionButton(icon: ImageVector, label: StringResource, onClick: () -> Unit) {
    IconButton(
        onClick = onClick,
        modifier = Modifier
            .size(LettaDimens.Orb.railSlotWidth)
            .background(Color.Black.copy(alpha = ViewerControlAlpha), MaterialTheme.shapes.small),
    ) {
        Icon(imageVector = icon, contentDescription = stringResource(label), tint = Color.White)
    }
}

/** The legacy viewer's control backing: black at 45%. */
private const val ViewerControlAlpha = 0.45f
