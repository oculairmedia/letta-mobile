package com.letta.mobile.ui.canvas

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import com.letta.mobile.data.canvas.CanvasSessionRegistry
import com.letta.mobile.ui.canvas.plugin.CanvasDocumentViewerRegistry
import com.letta.mobile.ui.canvas.plugin.LocalCanvasDocumentViewerRegistry

/**
 * The host's live boards, which its canvas_* tools edit in place. A host that serves those tools
 * provides its registry once at its root, so every [CanvasWorkspace] it mounts registers there;
 * null (the default) is a host whose canvas_* are answered elsewhere, as web's and Android's are.
 */
val LocalCanvasSessionRegistry = staticCompositionLocalOf<CanvasSessionRegistry?> { null }

/**
 * A host's canvas services for the shell inside [content]: the live boards its canvas_* tools edit,
 * and the views it offers (the shell keeps its own registry when [viewers] is null).
 */
@Composable
fun ProvideCanvasHost(
    sessions: CanvasSessionRegistry?,
    viewers: CanvasDocumentViewerRegistry?,
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(
        LocalCanvasSessionRegistry provides sessions,
        LocalCanvasDocumentViewerRegistry provides viewers,
        content = content,
    )
}
