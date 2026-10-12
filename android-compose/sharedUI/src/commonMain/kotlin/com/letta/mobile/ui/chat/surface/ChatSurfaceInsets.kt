package com.letta.mobile.ui.chat.surface

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.constrainWidth
import androidx.compose.ui.unit.offset
import com.letta.mobile.ui.shell.scaffold.LettaPageScaffold

/**
 * letta-mobile-ckmub.1 (hard cut S4): the chat page's window insets, owned once by the page's
 * [LettaPageScaffold] (the P2 contract) instead of each layer padding for the keyboard itself.
 *
 * What the chat page takes from the window, and why only that:
 * - the keyboard: the page's layers rise above it differently (the composer and the docked panel
 *   are lifted, the canvas and the full-screen page's morph rect are not), so the scaffold hands the
 *   keyboard's height down as a value ([LocalChatKeyboardLift]) and each layer lifts itself by it;
 * - not the status bar: the host's floating header and the status bar above it are
 *   [ChatSurfacePlatform.topChromeInset], under which the timeline scrolls edge to edge;
 * - not the navigation bar: the Touch composer bar runs flush to the screen's bottom edge, its
 *   opaque surface under the navigation bar (letta-mobile-bglj6.1.9), and the canvas draws behind it.
 */
internal object ChatSurfaceInsets {
    /** The window insets the page's scaffold pads for besides the keyboard: none (see above). */
    val window: WindowInsets = WindowInsets(0.dp, 0.dp, 0.dp, 0.dp)
}

/**
 * The keyboard's height over the page, as its scaffold measured it: a [State] read at layout time,
 * so the keyboard's slide relayouts the layers that rise with it without recomposing the page.
 * Zero outside a [ChatSurface] (snapshots, previews).
 */
internal val LocalChatKeyboardLift = staticCompositionLocalOf<State<Dp>> { mutableStateOf(0.dp) }

/** Test seam: the keyboard insets the page reads in place of the window's. No host provides it. */
internal val LocalChatSurfaceImeInsets = staticCompositionLocalOf<WindowInsets?> { null }

/** The page frame: [LettaPageScaffold] owning the keyboard inset, handed to [content] as [LocalChatKeyboardLift]. */
@Composable
internal fun ChatSurfacePageFrame(modifier: Modifier, content: @Composable () -> Unit) {
    LettaPageScaffold(
        modifier = modifier,
        insets = ChatSurfaceInsets.window,
        imeInsets = LocalChatSurfaceImeInsets.current ?: WindowInsets.ime,
        imeAware = true,
    ) { padding ->
        // One State for the page's lifetime: providing it never invalidates the page, only its readers.
        val lift = rememberUpdatedState(padding.calculateBottomPadding())
        CompositionLocalProvider(LocalChatKeyboardLift provides lift, content = content)
    }
}

/** Lifts this layer above the keyboard: bottom padding of [LocalChatKeyboardLift], applied at layout. */
@Composable
internal fun Modifier.liftAboveKeyboard(): Modifier = liftedBy(LocalChatKeyboardLift.current)

internal fun Modifier.liftedBy(lift: State<Dp>): Modifier = layout { measurable, constraints ->
    val bottom = lift.value.roundToPx().coerceAtLeast(0)
    val placeable = measurable.measure(constraints.offset(vertical = -bottom))
    val width = constraints.constrainWidth(placeable.width)
    val height = constraints.constrainHeight(placeable.height + bottom)
    layout(width, height) { placeable.place(0, 0) }
}
