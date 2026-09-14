package com.letta.mobile.ui.mascot

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.letta.mobile.avatar.core.AvatarLookTarget
import com.letta.mobile.avatar.core.GazeMath
import com.letta.mobile.avatar.core.GazeRect
import com.letta.mobile.avatar.core.GazeWorld
import com.letta.mobile.avatar.core.MascotIdentity
import com.letta.mobile.data.presence.AgentPresence

/**
 * What a platform contributes to draw a live mascot: the per-agent entry (renderer scene +
 * runtime + director) and the composable that paints an entry's scene. Everything else - which
 * agent, presence, time, gaze, sizing, clipping - is shared and lives in [MascotAvatar].
 */
interface MascotHost {
    /** The live entry for [agentId], or null when the renderer is unavailable (draw the fallback). */
    fun entry(agentId: String, identity: MascotIdentity): MascotEntry?

    /** Paints [entry]'s scene, filling [modifier]'s bounds. */
    @Composable
    fun Surface(entry: MascotEntry, modifier: Modifier)
}

/** No renderer: every mascot draws its fallback. Platforms provide a real host at their root. */
object NoMascotHost : MascotHost {
    override fun entry(agentId: String, identity: MascotIdentity): MascotEntry? = null

    @Composable
    override fun Surface(entry: MascotEntry, modifier: Modifier) = Unit
}

val LocalMascotHost = compositionLocalOf<MascotHost> { NoMascotHost }

/** True when [agentId] has an identity and the host can draw it live. */
@Composable
fun mascotAvailable(agentId: String?): Boolean {
    if (agentId == null) return false
    val identity = LocalMascotRegistry.current.identities[agentId] ?: return false
    return LocalMascotHost.current.entry(agentId, identity) != null
}

/**
 * The agent's avatar: a tile of [size] the live mascot fills edge to edge, overscaled by
 * [overscale] and cropped by the tile's clip - an avatar photo, not a figure in a frame. Draws
 * [fallback] when the agent has no identity or the host has no renderer.
 */
@Composable
fun MascotAvatar(
    agentId: String?,
    size: Dp,
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 7.dp,
    onClick: (() -> Unit)? = null,
    overscale: Float = MASCOT_TILE_OVERSCALE,
    fallback: @Composable () -> Unit,
) {
    val identity = agentId?.let { LocalMascotRegistry.current.identities[it] }
    if (agentId == null || identity == null || LocalMascotHost.current.entry(agentId, identity) == null) {
        fallback()
        return
    }
    Box(
        modifier = modifier
            .size(size)
            .clip(RoundedCornerShape(cornerRadius))
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        MascotLive(agentId, identity, size = size * overscale)
    }
}

/**
 * The live mascot for one agent at [size], from the process-wide entry so it never restarts
 * when the view changes. Feeds the entry's director the agent's presence and the frame clock,
 * and feeds [com.letta.mobile.avatar.core.GazeDirector] the pointer, this tile, and any
 * composer / timeline rects the host published on [MascotIdentityRegistry] so justified
 * attention (not only cursor tracking) runs on every host. Callers that need a fallback
 * check [mascotAvailable] first (or use [MascotAvatar]).
 */
@Composable
fun MascotLive(
    agentId: String,
    identity: MascotIdentity,
    size: Dp,
    modifier: Modifier = Modifier,
) {
    val host = LocalMascotHost.current
    val registry = LocalMascotRegistry.current
    val entry = remember(host, agentId, identity) { host.entry(agentId, identity) } ?: return
    val presence = registry.presence[agentId] ?: AgentPresence.IDLE
    LaunchedEffect(entry, presence) { entry.ensureLoaded(); entry.apply(presence) }
    // The director's timers (listening release, success hold, blink schedule) need a clock;
    // tickTo is idempotent per frame so several surfaces of one agent tick it once.
    LaunchedEffect(entry) {
        while (true) withFrameNanos { entry.tickTo(it) }
    }
    // Gaze: this tile vs the pointer plus optional composer / timeline rects
    // from the registry. Null input/timeline skip those plan rows; OWN/USER
    // (and CURSOR when the pointer is present) still run so the eyes are never dead.
    var bounds by remember { mutableStateOf(Rect.Zero) }
    val cursor = registry.cursor.value
    val inputBounds = registry.inputBounds.value
    val timelineBounds = registry.timelineBounds.value
    val minReachPx = with(LocalDensity.current) { GAZE_MIN_REACH.toPx() }
    LaunchedEffect(entry, cursor, bounds, minReachPx, inputBounds, timelineBounds) {
        entry.setGazeWorld(
            GazeWorld.fromWindow(
                mascot = GazeRect(bounds.left, bounds.top, bounds.right, bounds.bottom),
                minReachPx = minReachPx,
                pointerX = cursor?.x,
                pointerY = cursor?.y,
                inputBounds = inputBounds,
                timelineBounds = timelineBounds,
            ),
        )
    }
    // requiredSize: an overscaled mascot must exceed its tile so the tile's clip crops it;
    // plain size() is coerced down to the parent's constraints and never overscales.
    host.Surface(entry, modifier.requiredSize(size).onGloballyPositioned { bounds = it.boundsInWindow() })
}

/**
 * Where the eyes go for a pointer at ([x], [y]) in window space, given the surface's [bounds]:
 * reach is a window-scale distance (at least [minReachPx]), never the tile's own width, so a
 * 32 dp tile does not saturate for a cursor a few px away; saturation is smooth and stops short
 * of the rim so the eyes never sit pinned.
 */
fun pointerLook(x: Float, y: Float, bounds: Rect, minReachPx: Float): AvatarLookTarget.Screen =
    GazeMath.toScreen(
        GazeMath.pointerToGaze(
            x,
            y,
            GazeRect(bounds.left, bounds.top, bounds.right, bounds.bottom),
            minReachPx,
        ),
    )

/** The body spans ~60 % of the artboard; this fills a tile edge to edge. */
const val MASCOT_TILE_OVERSCALE = 1.6f
private val GAZE_MIN_REACH = 360.dp
