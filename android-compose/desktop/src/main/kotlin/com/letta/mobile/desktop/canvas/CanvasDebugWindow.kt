package com.letta.mobile.desktop.canvas

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.rememberWindowState
import com.letta.mobile.ui.canvas.CanvasSamples
import com.letta.mobile.ui.canvas.CanvasWorkspace

/**
 * Dedicated desktop window for Canvas Workspace (P0 debug entry).
 * Launches when MERIDIAN_CANVAS_DEBUG=1.
 */
@Composable
internal fun CanvasDebugWindow(
    onClose: (() -> Unit)? = null,
) {
    var open by remember { mutableStateOf(true) }
    if (!open) return

    Window(
        onCloseRequest = {
            open = false
            onClose?.invoke()
        },
        title = "Meridian Canvas Workspace (Debug)",
        state = rememberWindowState(width = 1280.dp, height = 820.dp),
    ) {
        MaterialTheme(colorScheme = darkColorScheme()) {
            Surface(modifier = Modifier.fillMaxSize()) {
                CanvasWorkspace(
                    initialJson = CanvasSamples.buildCycleJson,
                    onNavigateBack = {
                        open = false
                        onClose?.invoke()
                    },
                )
            }
        }
    }
}
