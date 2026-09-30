package com.letta.mobile.desktop.touch

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionOnScreen
import java.awt.Window

/** The AWT window the current composition is drawn in, when one exists. */
internal val LocalDesktopWindow = compositionLocalOf<Window?> { null }

/**
 * A finger press here is a click, and it is remembered as a finger so the
 * touch keyboard can open. Lists stay on the scroll path.
 */
@Composable
internal fun Modifier.fingerPressesAsClicks(id: String): Modifier {
    val window = LocalDesktopWindow.current ?: return this
    DisposableEffect(window, id) {
        onDispose { DesktopTouchInteractive.publishOverlay(window, id, null) }
    }
    return onGloballyPositioned { coordinates ->
        val origin = coordinates.positionOnScreen()
        val size = coordinates.size
        DesktopTouchInteractive.publishOverlay(
            window,
            id,
            screenExclusionRectOrNull(origin.x, origin.y, size.width, size.height),
        )
    }
}
