package com.letta.mobile.ui.canvas

import androidx.compose.runtime.staticCompositionLocalOf
import com.letta.mobile.data.canvas.CanvasSessionRegistry

/**
 * The host's live boards, which its canvas_* tools edit in place. A host that serves those tools
 * provides its registry once at its root, so every [CanvasWorkspace] it mounts registers there;
 * null (the default) is a host whose canvas_* are answered elsewhere, as web's and Android's are.
 */
val LocalCanvasSessionRegistry = staticCompositionLocalOf<CanvasSessionRegistry?> { null }
