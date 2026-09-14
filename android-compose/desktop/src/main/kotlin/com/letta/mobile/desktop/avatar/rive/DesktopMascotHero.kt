package com.letta.mobile.desktop.avatar.rive

import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import com.letta.mobile.avatar.core.MascotIdentity
import com.letta.mobile.data.presence.AgentPresence
import com.letta.mobile.desktop.chat.AgentSphere
import com.letta.mobile.desktop.chat.MascotIdentityRegistry

/**
 * The live mascot for one agent on desktop, drawn from the process-wide entry for that agent
 * ([DesktopMascotScenes]) so it never restarts when the view changes. The agent's presence
 * (from [MascotIdentityRegistry]) is fed to the entry's director, which owns the state; this
 * composable only renders and keeps the director's clock running. When the native bridge is not
 * available (no DLL, not Windows) or the scene fails, the gradient [AgentSphere] stands in.
 */
@Composable
fun DesktopMascotHero(
    agentId: String,
    identity: MascotIdentity,
    size: Dp,
    modifier: Modifier = Modifier,
) {
    val entry = remember(agentId, identity) { DesktopMascotScenes.get(agentId, identity) }
    if (entry == null) {
        AgentSphere(size = size, modifier = modifier)
        return
    }
    val presence = MascotIdentityRegistry.presence[agentId] ?: AgentPresence.IDLE
    LaunchedEffect(entry, presence) { entry.apply(presence) }
    // The director's timers (listening release, success hold, blink schedule) need a clock;
    // tickTo is idempotent per frame so several surfaces of one agent tick it once.
    LaunchedEffect(entry) {
        while (true) withFrameNanos { entry.tickTo(it) }
    }
    // requiredSize: an overscaled mascot must exceed its tile so the tile's clip crops it;
    // plain size() is coerced down to the parent's constraints and never overscales.
    RiveDesktopSurface(entry.scene, modifier.requiredSize(size))
}
