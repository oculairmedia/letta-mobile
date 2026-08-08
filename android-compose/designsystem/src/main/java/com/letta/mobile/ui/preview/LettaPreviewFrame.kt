package com.letta.mobile.ui.preview

import androidx.compose.material3.Surface
import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.WindowSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import com.letta.mobile.ui.theme.LettaTheme
import com.letta.mobile.ui.theme.LocalWindowSizeClass

/**
 * Standard frame for Compose previews of Letta screens.
 *
 * Provides the composition locals that screens normally receive from
 * MainActivity so previews render without a running app:
 * - [LocalWindowSizeClass] sized like a phone by default (pass [size] to
 *   preview tablet/expanded layouts).
 * - [LettaTheme] with dynamic color disabled for stable, reproducible colors.
 * - A [Surface] so backgrounds match the theme.
 *
 * Entrance animations gated on reduced-motion are already skipped in previews
 * via `LocalInspectionMode` (see `StaggeredListItem`).
 */
@OptIn(ExperimentalMaterial3WindowSizeClassApi::class)
@Composable
fun LettaPreviewFrame(
    size: DpSize = DpSize(411.dp, 891.dp),
    content: @Composable () -> Unit,
) {
    val windowSizeClass = WindowSizeClass.calculateFromSize(size)
    CompositionLocalProvider(LocalWindowSizeClass provides windowSizeClass) {
        LettaTheme(dynamicColor = false) {
            Surface(content = content)
        }
    }
}
