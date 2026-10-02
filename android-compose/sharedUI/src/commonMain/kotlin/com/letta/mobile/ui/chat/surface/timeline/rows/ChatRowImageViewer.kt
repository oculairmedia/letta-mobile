package com.letta.mobile.ui.chat.surface.timeline.rows

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.letta.mobile.data.model.UiImageAttachment
import com.letta.mobile.sharedui.resources.Res
import com.letta.mobile.sharedui.resources.rows_close_viewer
import com.letta.mobile.sharedui.resources.rows_image_counter
import com.letta.mobile.sharedui.resources.rows_image_decode_failed
import com.letta.mobile.sharedui.resources.rows_image_fullscreen
import com.letta.mobile.ui.icons.LettaIcons
import com.letta.mobile.ui.theme.ChatRowAlpha
import com.letta.mobile.ui.theme.LettaDimens
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

/**
 * letta-mobile-bglj6.1: the page's one full-screen image viewer, lifted from desktop's
 * DesktopFullscreenImageOverlay and extended with Android's paging between a message's
 * images. Click the scrim, the close button or Esc to dismiss; arrow keys page.
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
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        LaunchedEffect(Unit) { runCatching { focusRequester.requestFocus() } }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .testTag(ChatRowTestTags.IMAGE_VIEWER)
                .background(MaterialTheme.colorScheme.scrim.copy(alpha = ChatRowAlpha.viewerScrim))
                .clickable(onClick = onDismiss)
                .onPreviewKeyEvent { event -> handleViewerKey(event, ViewerKeyTarget(pagerState, scope, onDismiss)) }
                .focusRequester(focusRequester)
                .focusable(),
            contentAlignment = Alignment.Center,
        ) {
            HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize()) { page ->
                ViewerPage(images[page])
            }
            ViewerChrome(pagerState = pagerState, count = images.size, onDismiss = onDismiss)
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

@Composable
private fun ViewerPage(attachment: UiImageAttachment) {
    val bitmap = rememberAttachmentBitmap(attachment)
    Box(modifier = Modifier.fillMaxSize().padding(LettaDimens.Orb.lg), contentAlignment = Alignment.Center) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap,
                contentDescription = stringResource(Res.string.rows_image_fullscreen),
                modifier = Modifier.fillMaxSize(),
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
