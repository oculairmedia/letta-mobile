package com.letta.mobile.desktop.avatar.rive

import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import com.letta.mobile.avatar.core.AvatarState
import com.letta.mobile.avatar.core.MascotIdentity
import com.letta.mobile.desktop.chat.AgentSphere

/**
 * The live mascot for one agent on desktop, drawn from the process-wide scene for that agent
 * ([DesktopMascotScenes]) so it never restarts when the view changes. Identity is applied before
 * the first frame; state goes through the shared [com.letta.mobile.avatar.rive.RiveAvatarRuntime].
 * When the native bridge is not available on this machine (no DLL, not Windows) or the scene
 * fails to load, the gradient [AgentSphere] stands in, so callers never care which they got.
 *
 * P1 of the rollout (MASCOT.md section 2): the caller passes the director's state; until the
 * director is wired (P2) that is IDLE and the rig's own life carries it.
 */
@Composable
fun DesktopMascotHero(
    agentId: String,
    identity: MascotIdentity,
    size: Dp,
    modifier: Modifier = Modifier,
    state: AvatarState = AvatarState.IDLE,
) {
    val entry = remember(agentId, identity) { DesktopMascotScenes.get(agentId, identity) }
    if (entry == null) {
        AgentSphere(size = size, modifier = modifier)
        return
    }
    LaunchedEffect(entry, state) { entry.runtime.applyState(state) }
    RiveDesktopSurface(entry.scene, modifier.size(size))
}
