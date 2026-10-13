package com.letta.mobile.ui.host

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.Dp
import com.letta.mobile.ui.theme.LettaDimens

/**
 * letta-mobile-ckmub.3.3 (S3c): how wide the window a pane opens over is. It is the window's width,
 * not the page's: a container (sheet, dialog) covers the whole window, so a list pane in a wide
 * tablet window still opens its modal as a dialog. [Wide] starts at the shell's own breakpoint
 * ([LettaDimens.Pane.wideBreakpoint]), where pages dock a side panel.
 */
enum class PaneWindowClass {
    Compact,
    Wide,
    ;

    companion object {
        fun of(width: Dp): PaneWindowClass = if (width >= LettaDimens.Pane.wideBreakpoint) Wide else Compact
    }
}

/**
 * Pins the window class panes resolve against; null measures the window. A frame that draws a
 * device inside a larger window (a phone preview, a test) provides the device's class.
 */
val LocalPaneWindowClass = staticCompositionLocalOf<PaneWindowClass?> { null }

/** The window class in effect: [LocalPaneWindowClass] when pinned, else the window's measured width. */
@Composable
fun currentPaneWindowClass(): PaneWindowClass {
    LocalPaneWindowClass.current?.let { return it }
    val widthPx = LocalWindowInfo.current.containerSize.width
    if (widthPx <= 0) return PaneWindowClass.Compact
    return PaneWindowClass.of(with(LocalDensity.current) { widthPx.toDp() })
}
