package com.letta.mobile.ui.mascot.frame

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import com.letta.mobile.ui.mascot.LocalMascotHost
import com.letta.mobile.ui.mascot.LocalMascotRegistry
import com.letta.mobile.ui.mascot.LocalMascotTransport
import com.letta.mobile.ui.mascot.MascotCursorCapture
import com.letta.mobile.ui.mascot.MascotIdentityRegistry
import com.letta.mobile.ui.mascot.MascotTransport
import com.letta.mobile.util.runCatchingCancellable

/**
 * The mascot layer at a pointer host's root: the frame host over [renderer], [registry] (the
 * caller keeps its identities and presence current), one transport for every seat, and the pointer
 * captured for gaze. Until [RiveFrameRenderer.prepare] has the renderer up - or if it never comes
 * up - the host is unavailable and every mascot draws its fallback.
 */
@Composable
fun ProvideFrameMascots(
    renderer: RiveFrameRenderer,
    registry: MascotIdentityRegistry,
    content: @Composable () -> Unit,
) {
    val host = remember(renderer) { FrameMascotHost(renderer) }
    val transport = remember { MascotTransport() }
    LaunchedEffect(renderer) { bringUpSafely(renderer) }
    DisposableEffect(host) { onDispose { host.closeAll() } }
    CompositionLocalProvider(
        LocalMascotHost provides host,
        LocalMascotRegistry provides registry,
        LocalMascotTransport provides transport,
    ) {
        MascotCursorCapture(registry, content)
    }
}

/**
 * [renderer]'s bring-up, with every failure but cancellation absorbed: the renderer simply stays
 * unavailable and every mascot draws its fallback.
 *
 * One broken renderer must not cost the screen. An exception out of this composable's
 * [LaunchedEffect] fails the composition, and a browser's Rive interop throws exactly here - a JS
 * throw, a fired launch, a wasm trap, a mascot file that does not read - so this is where the
 * renderer ends. A cancelled bring-up rethrows: the effect's composition is gone, which is not a
 * failure.
 */
internal suspend fun bringUpSafely(renderer: RiveFrameRenderer) {
    runCatchingCancellable { renderer.prepare() }
        .onFailure { println("[mascot] Rive renderer unavailable, drawing fallbacks: $it") }
}
