package com.letta.mobile.ui.theme.tokens.desktop

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Sizes of the desktop app's own OS windows and custom title bar, which no shared page draws. */
object DesktopWindowTokens {
    /** The main window's first width, before the user resizes it (also the canvas debug and crash-repro windows). */
    val mainWidth: Dp = 1280.dp

    /** The main window's first height (also the canvas debug and crash-repro windows). */
    val mainHeight: Dp = 820.dp

    /** The shader lookdev window's first width. */
    val lookdevWidth: Dp = 1280.dp

    /** The shader lookdev window's first height. */
    val lookdevHeight: Dp = 860.dp

    /** The undecorated quick-query popup's width. */
    val quickQueryWidth: Dp = 680.dp

    /** The undecorated quick-query popup's height. */
    val quickQueryHeight: Dp = 440.dp

    /** The custom title bar's height: room for the two-line agent identity block. */
    val titleBarHeight: Dp = 48.dp

    /** The title-bar lane always left free for native window dragging. */
    val titleBarMinDragWidth: Dp = 96.dp
}
