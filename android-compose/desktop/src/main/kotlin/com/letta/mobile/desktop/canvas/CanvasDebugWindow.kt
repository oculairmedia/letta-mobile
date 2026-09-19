package com.letta.mobile.desktop.canvas

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.rememberWindowState
import androidx.compose.ui.window.singleWindowApplication
import com.letta.mobile.desktop.initializeDesktopLifecycleMainThread
import com.letta.mobile.desktop.input.InstallTabletPen
import com.letta.mobile.ui.canvas.CanvasSamples
import com.letta.mobile.ui.canvas.LocalCanvasPenTarget
import com.letta.mobile.ui.canvas.CanvasWorkspace

/**
 * Standalone entry point for quick canvas lookdev and testing:
 * ./gradlew :desktop:runCanvas
 */
fun main() {
    initializeDesktopLifecycleMainThread()
    singleWindowApplication(
        title = "Meridian Canvas Workspace (Debug)",
        state = androidx.compose.ui.window.WindowState(width = 1280.dp, height = 820.dp),
    ) {
        // Without this the canvas sees the pen as a mouse: flat pressure, no eraser end.
        InstallTabletPen(window)
        CompositionLocalProvider(LocalCanvasPenTarget provides com.letta.mobile.desktop.input.WindowPenTarget(window)) {
        MaterialTheme(colorScheme = darkColorScheme()) {
            Surface(modifier = Modifier.fillMaxSize()) {
                CanvasWorkspace(
                    initialJson = CanvasSamples.buildCycleJson,
                )
            }
        }
        }
    }
}

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
        // Without this the canvas sees the pen as a mouse: flat pressure, no eraser end.
        InstallTabletPen(window)
        CompositionLocalProvider(LocalCanvasPenTarget provides com.letta.mobile.desktop.input.WindowPenTarget(window)) {
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
}
