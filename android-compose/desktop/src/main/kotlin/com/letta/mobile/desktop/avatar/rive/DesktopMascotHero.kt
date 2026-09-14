package com.letta.mobile.desktop.avatar.rive

import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
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
    // Gaze: this surface's window bounds vs the cursor (captured once at the window root). The
    // eyes follow within `reach` mascot widths; the director owns the write, so timing rules
    // (attention, habituation) land there for both platforms.
    var bounds by remember { mutableStateOf(androidx.compose.ui.geometry.Rect.Zero) }
    val cursor = MascotIdentityRegistry.cursor.value
    LaunchedEffect(entry, cursor, bounds) {
        if (bounds.isEmpty) return@LaunchedEffect
        if (cursor == null) { entry.director.setLookTarget(null); return@LaunchedEffect }
        val reach = bounds.width * 2.5f / 2f
        val nx = ((cursor.x - bounds.center.x) / reach).coerceIn(-1f, 1f)
        val ny = ((cursor.y - bounds.center.y) / reach).coerceIn(-1f, 1f)
        entry.director.setLookTarget(com.letta.mobile.avatar.core.AvatarLookTarget.Screen((nx + 1f) / 2f, (ny + 1f) / 2f))
    }
    RiveDesktopSurface(entry.scene, modifier.requiredSize(size).onGloballyPositioned { bounds = it.boundsInWindow() })
}
