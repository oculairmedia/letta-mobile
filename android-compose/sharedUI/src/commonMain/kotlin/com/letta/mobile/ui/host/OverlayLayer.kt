package com.letta.mobile.ui.host

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Snackbar
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.letta.mobile.ui.icons.LettaIcons
import com.letta.mobile.ui.theme.LettaDimens
import com.letta.mobile.ui.theme.LettaMotionTokens
import com.letta.mobile.ui.theme.LocalReducedMotion
import kotlinx.coroutines.delay

/** The window's overlay queue; a platform's window root provides it once ([OverlayHostRoot]). */
val LocalOverlayHost = staticCompositionLocalOf<OverlayHost?> { null }

/** A window's overlay queue, kept for as long as the window's composition. */
@Composable
fun rememberOverlayHost(): OverlayHost = remember { OverlayHost() }

/**
 * A window root: makes [host] the queue for [content] and draws its banners (top) and snackbar
 * (foot) over it, clear of the system bars and the keyboard. Android's activity, the desktop
 * window and the web page each call this once.
 */
@Composable
fun OverlayHostRoot(host: OverlayHost, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalOverlayHost provides host) {
        Box(modifier) {
            content()
            OverlayHostLayer(host, Modifier.matchParentSize())
        }
    }
}

/**
 * The provided host, or (a surface composed without a window root: a test, a preview) one of its
 * own, provided to [content]. A surface that may run alone draws an [OverlaySnackbarSlot].
 */
@Composable
fun ProvideOverlayHost(content: @Composable (OverlayHost) -> Unit) {
    val provided = LocalOverlayHost.current
    if (provided != null) {
        content(provided)
        return
    }
    val own = rememberOverlayHost()
    CompositionLocalProvider(LocalOverlayHost provides own) { content(own) }
}

/** The host's overlays over the window; the snackbar only while no [OverlaySnackbarSlot] claims it. */
@Composable
fun OverlayHostLayer(host: OverlayHost, modifier: Modifier = Modifier) {
    OverlayTimer(host)
    // A container, not a page: the window's own overlay lane keeps clear of the bars and keyboard.
    Box(modifier.windowInsetsPadding(WindowInsets.safeDrawing)) {
        OverlayBanners(host, Modifier.align(Alignment.TopCenter))
        if (host.slots.active == null) {
            OverlaySnackbar(host, Modifier.align(Alignment.BottomCenter).padding(LettaDimens.Space.lg))
        }
    }
}

/**
 * Where a surface wants the window's snackbar drawn (the chat page: above its composer). While
 * composed, the newest slot draws it instead of the window layer; the queue is still the window's.
 */
@Composable
fun OverlaySnackbarSlot(modifier: Modifier = Modifier) {
    val host = LocalOverlayHost.current ?: return
    val id = remember(host) { host.slots.newId() }
    DisposableEffect(host, id) {
        host.slots.register(id)
        onDispose { host.slots.unregister(id) }
    }
    OverlayTimer(host)
    if (host.slots.active == id) OverlaySnackbar(host, modifier)
}

/**
 * Shows [overlay] while it is non-null and composed, and takes it back silently when it becomes
 * null, changes [key], or leaves composition (a banner that mirrors state, an undo offer).
 */
@Composable
fun OverlayWhile(overlay: Overlay?, key: Any? = overlay?.dedupeKey) {
    val host = LocalOverlayHost.current ?: return
    DisposableEffect(host, key, overlay != null) {
        val handle = overlay?.let(host::show)
        onDispose { handle?.withdraw() }
    }
}

/** Dismisses timed overlays when their time is up. Idempotent: two timers on one host are harmless. */
@Composable
private fun OverlayTimer(host: OverlayHost) {
    LaunchedEffect(host, host.snackbar, host.banners) {
        while (true) {
            val wait = host.nextDeadlineIn() ?: break
            delay(wait)
            host.tick()
        }
    }
}

@Composable
private fun OverlaySnackbar(host: OverlayHost, modifier: Modifier) {
    val reduced = LocalReducedMotion.current
    AnimatedContent(
        targetState = host.snackbar,
        contentKey = { it?.id },
        transitionSpec = { overlayTransition(reduced) },
        modifier = modifier,
        contentAlignment = Alignment.Center,
        label = "overlay-snackbar",
    ) { entry ->
        if (entry != null) OverlaySnackbarCard(host, entry)
    }
}

@Composable
private fun OverlaySnackbarCard(host: OverlayHost, entry: OverlayEntry) {
    val overlay = entry.overlay
    Snackbar(
        modifier = Modifier.padding(LettaDimens.Space.md).widthIn(max = SnackbarMaxWidth).testTag(OverlayTags.SNACKBAR),
        action = overlay.action?.let { action ->
            { TextButton(onClick = { host.performAction(entry.id) }, Modifier.testTag(OverlayTags.ACTION)) { Text(action.label) } }
        },
        dismissAction = if (overlay.dismissible) {
            { DismissButton { host.dismiss(entry.id) } }
        } else {
            null
        },
    ) {
        Text(overlay.message)
    }
}

@Composable
private fun OverlayBanners(host: OverlayHost, modifier: Modifier) {
    val banners = host.banners
    if (banners.isEmpty()) return
    Column(
        modifier = modifier.fillMaxWidth().padding(LettaDimens.Space.lg),
        verticalArrangement = Arrangement.spacedBy(LettaDimens.Space.sm),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        banners.forEach { entry -> key(entry.id) { OverlayBannerCard(host, entry) } }
    }
}

@Composable
private fun OverlayBannerCard(host: OverlayHost, entry: OverlayEntry) {
    val overlay = entry.overlay
    val error = (overlay as? Overlay.Banner)?.tone == OverlayTone.Error
    val reduced = LocalReducedMotion.current
    val visible = remember(entry.id) { MutableTransitionState(reduced).apply { targetState = true } }
    val enter = if (reduced) EnterTransition.None else fadeIn(tween(LettaMotionTokens.ENTER_MILLIS)) + slideInVertically { -it / 2 }
    AnimatedVisibility(visibleState = visible, enter = enter, exit = ExitTransition.None) {
        Surface(
            color = if (error) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer,
            contentColor = if (error) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSecondaryContainer,
            shape = MaterialTheme.shapes.medium,
            shadowElevation = LettaDimens.Space.xs,
            modifier = Modifier.widthIn(max = BannerMaxWidth).fillMaxWidth().testTag(OverlayTags.BANNER),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = LettaDimens.Space.lg, vertical = LettaDimens.Space.sm),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(LettaDimens.Space.md),
            ) {
                Text(overlay.message, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                overlay.action?.let { action ->
                    TextButton(onClick = { host.performAction(entry.id) }, Modifier.testTag(OverlayTags.ACTION)) { Text(action.label) }
                }
                if (overlay.dismissible) DismissButton { host.dismiss(entry.id) }
            }
        }
    }
}

@Composable
private fun DismissButton(onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.testTag(OverlayTags.DISMISS)) {
        Icon(LettaIcons.Close, contentDescription = "Dismiss")
    }
}

private fun overlayTransition(reduced: Boolean): ContentTransform = if (reduced) {
    EnterTransition.None togetherWith ExitTransition.None
} else {
    (fadeIn(tween(LettaMotionTokens.ENTER_MILLIS)) + slideInVertically { it / 2 }) togetherWith fadeOut(tween(LettaMotionTokens.ENTER_MILLIS))
}

private val SnackbarMaxWidth = 600.dp
private val BannerMaxWidth = 720.dp

/** Test tags the overlay views carry. */
object OverlayTags {
    const val SNACKBAR = "overlay-snackbar"
    const val BANNER = "overlay-banner"
    const val ACTION = "overlay-action"
    const val DISMISS = "overlay-dismiss"
}
