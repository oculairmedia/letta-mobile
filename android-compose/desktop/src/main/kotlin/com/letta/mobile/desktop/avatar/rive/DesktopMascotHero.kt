package com.letta.mobile.desktop.avatar.rive

import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import com.letta.mobile.avatar.core.AvatarState
import com.letta.mobile.avatar.core.MascotIdentity
import com.letta.mobile.avatar.rive.MASCOT_MODEL
import com.letta.mobile.avatar.rive.RiveAvatarContract
import com.letta.mobile.avatar.rive.RiveAvatarRuntime
import com.letta.mobile.desktop.chat.AgentSphere

/**
 * The live mascot at hero size on desktop: identity applied before the first frame, state through
 * the shared [RiveAvatarRuntime]. When the native bridge is not available on this machine (no DLL,
 * not Windows) or the scene fails to load, the gradient [AgentSphere] stands in, so every surface
 * can call this without caring which it got.
 *
 * P1 of the rollout (MASCOT.md section 2): the caller passes the director's state; until the
 * director is wired (P2) that is IDLE and the rig's own life carries it.
 */
@Composable
fun DesktopMascotHero(
    identity: MascotIdentity,
    size: Dp,
    modifier: Modifier = Modifier,
    state: AvatarState = AvatarState.IDLE,
) {
    if (!RiveBridgeNative.AVAILABLE) {
        AgentSphere(size = size, modifier = modifier)
        return
    }
    val scene = remember {
        runCatching {
            val bytes = mascotBytes() ?: error("mascot.riv not on the classpath")
            RiveDesktopScene.create().also {
                it.load(bytes)
                // Host rule: identity before the first advance, or the first frame is a black body.
                RiveAvatarContract.applyIdentity(it.inputSink, identity)
            }
        }.getOrNull()
    }
    if (scene == null) {
        AgentSphere(size = size, modifier = modifier)
        return
    }
    DisposableEffect(scene) { onDispose { scene.close() } }
    val runtime = remember(scene) { RiveAvatarRuntime(scene.inputSink) }
    LaunchedEffect(runtime) { runtime.load(MASCOT_MODEL) }
    LaunchedEffect(scene, identity) { RiveAvatarContract.applyIdentity(scene.inputSink, identity) }
    LaunchedEffect(runtime, state) { runtime.applyState(state) }
    RiveDesktopSurface(scene, modifier.size(size))
}

/** The shipped mascot, packaged as a JVM resource by :avatar:renderer-rive (the same bytes Android bundles as res/raw). */
private fun mascotBytes(): ByteArray? =
    RiveAvatarContract::class.java.getResourceAsStream("/mascot/mascot.riv")?.use { it.readBytes() }
