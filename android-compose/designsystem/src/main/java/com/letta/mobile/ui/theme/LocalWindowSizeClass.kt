package com.letta.mobile.ui.theme

import androidx.compose.material3.windowsizeclass.WindowSizeClass
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.runtime.staticCompositionLocalOf

val LocalWindowSizeClass = staticCompositionLocalOf<WindowSizeClass> {
    error("No WindowSizeClass provided. Ensure it is set in MainActivity.")
}

val WindowSizeClass.isExpandedWidth: Boolean
    get() = widthSizeClass != WindowWidthSizeClass.Compact

val WindowSizeClass.isWideWidth: Boolean
    get() = widthSizeClass == WindowWidthSizeClass.Expanded
