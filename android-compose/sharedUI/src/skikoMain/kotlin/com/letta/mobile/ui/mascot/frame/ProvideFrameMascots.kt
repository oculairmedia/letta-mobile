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
    LaunchedEffect(renderer) { renderer.prepare() }
    DisposableEffect(host) { onDispose { host.closeAll() } }
    CompositionLocalProvider(
        LocalMascotHost provides host,
        LocalMascotRegistry provides registry,
        LocalMascotTransport provides transport,
    ) {
        MascotCursorCapture(registry, content)
    }
}
