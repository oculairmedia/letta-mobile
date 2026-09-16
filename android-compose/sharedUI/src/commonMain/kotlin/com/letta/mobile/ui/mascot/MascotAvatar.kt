package com.letta.mobile.ui.mascot

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import com.letta.mobile.avatar.core.GazePoint
import com.letta.mobile.avatar.core.GazeReach
import com.letta.mobile.avatar.core.GazeRect
import com.letta.mobile.avatar.core.GazeTargetRects
import com.letta.mobile.avatar.core.GazeWindow
import com.letta.mobile.avatar.core.GazeWorld
import com.letta.mobile.avatar.core.MascotIdentity
import com.letta.mobile.data.presence.AgentActivityKind
import com.letta.mobile.data.presence.AgentPresence

/**
 * What a platform contributes to draw a live mascot: the per-agent entry (renderer scene +
 * runtime + director) and the composable that paints an entry's scene. Everything else - which
 * agent, presence, time, gaze, sizing, clipping - is shared and lives in [MascotAvatar].
 */
interface MascotHost {
    /** The live entry for [agentId], or null when the renderer is unavailable (draw the fallback). */
    fun entry(agentId: String, identity: MascotIdentity): MascotEntry?

    /**
     * Paints [entry]'s scene, filling [modifier]'s bounds. With [playing] false the scene is drawn
     * once and never advanced: a still of the character for places that must not move (a header
     * chip beside a live companion), still in the entry's identity and current pose.
     */
    @Composable
    fun Surface(entry: MascotEntry, modifier: Modifier, playing: Boolean)
}

/** No renderer: every mascot draws its fallback. Platforms provide a real host at their root. */
object NoMascotHost : MascotHost {
    override fun entry(agentId: String, identity: MascotIdentity): MascotEntry? = null

    @Composable
    override fun Surface(entry: MascotEntry, modifier: Modifier, playing: Boolean) = Unit
}

val LocalMascotHost = compositionLocalOf<MascotHost> { NoMascotHost }

/**
 * The product rule for whether an agent's tile moves: it is live while the agent is at work
 * (thinking or speaking, as the shell's presence says) and a still otherwise. One rule for every
 * list, chip and header on every platform; a site passes `live` explicitly only when it must
 * stay still beside a live companion of the same agent.
 */
@Composable
fun mascotAtWork(agentId: String?): Boolean {
    if (agentId == null) return false
    val activity = LocalMascotRegistry.current.presence[agentId]?.activity ?: return false
    return activity != AgentActivityKind.IDLE
}

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
 * [fallback] when the agent has no identity or the host has no renderer. [live] defaults to the
 * product rule ([mascotAtWork]: moving while the agent works, a still otherwise); pass false for a
 * chip that sits next to a live mascot of the same agent, where two of them moving is one too many.
 */
@Composable
fun MascotAvatar(
    agentId: String?,
    size: Dp,
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 7.dp,
    onClick: (() -> Unit)? = null,
    overscale: Float = MASCOT_TILE_OVERSCALE,
    live: Boolean = mascotAtWork(agentId),
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
        if (live) {
            MascotLive(agentId, identity, size = size * overscale)
        } else {
            MascotStill(agentId, identity, size = size * overscale)
        }
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
    // This surface's slot in the registry, so the other agents' mascots can look at it.
    val slotKey = remember(agentId) { "$agentId#${slotCounter++}" }
    DisposableEffect(registry, slotKey) { onDispose { registry.mascotBounds.remove(slotKey) } }
    val peersPx = registry.mascotBounds.values
        .filter { it.agentId != agentId && !it.bounds.isEmpty }
        .map { GazePoint(it.bounds.centerX, it.bounds.centerY) }
    val minReachPx = with(LocalDensity.current) { GAZE_MIN_REACH.toPx() }
    LaunchedEffect(entry, cursor, bounds, minReachPx, inputBounds, timelineBounds, peersPx) {
        entry.setGazeWorld(
            GazeWorld.fromWindow(
                GazeWindow(
                    mascot = GazeRect(bounds.left, bounds.top, bounds.right, bounds.bottom),
                    reach = GazeReach(minReachPx),
                    pointerPx = cursor?.let { GazePoint(it.x, it.y) },
                    rects = GazeTargetRects(input = inputBounds, timeline = timelineBounds),
                    peersPx = peersPx,
                ),
            ),
        )
    }
    // requiredSize: an overscaled mascot must exceed its tile so the tile's clip crops it;
    // plain size() is coerced down to the parent's constraints and never overscales.
    host.Surface(
        entry,
        modifier.requiredSize(size).onGloballyPositioned {
            val r = it.boundsInWindow()
            bounds = r
            val slot = MascotSlot(agentId, GazeRect(r.left, r.top, r.right, r.bottom))
            if (registry.mascotBounds[slotKey] != slot) registry.mascotBounds[slotKey] = slot
        },
        playing = true,
    )
}

/**
 * The real mascot for an identity that belongs to no agent - a picker option, a preview - drawn
 * paused: the same Rive scene every live surface draws, with its clock stopped. It is not an
 * approximation of the character and not a separate still asset, so the shape, the body's pose and
 * the identity's rotation are exactly what the agent will look like (letta-mobile-0bvjw).
 *
 * Callers check [mascotCandidateAvailable] and draw their own fallback when the renderer is
 * unavailable.
 */
@Composable
fun MascotCandidate(
    identity: MascotIdentity,
    size: Dp,
    modifier: Modifier = Modifier,
) = MascotStill(candidateSceneKey(identity), identity, size, modifier)

/** True when the host can draw [identity] as a [MascotCandidate]. */
@Composable
fun mascotCandidateAvailable(identity: MascotIdentity): Boolean =
    LocalMascotHost.current.entry(candidateSceneKey(identity), identity) != null

/**
 * Which scene a candidate identity draws on. Scenes live in one table keyed by string and re-skin
 * themselves when asked for a different identity, so the split has to be the part of the identity a
 * caller shows several of at once - the body - while colour and turn are skinned onto the scene the
 * body already has. Deriving it here rather than taking a key means a caller cannot forge one that
 * collides with an agent's, or accidentally give two candidates the same scene.
 */
internal fun candidateSceneKey(identity: MascotIdentity): String = "mascot-candidate:${identity.shape.name}"

/**
 * One frame of the agent's mascot at [size], with no clock and no gaze. The agent's live surfaces
 * still drive the entry; a list of stills must not register one frame callback per row.
 */
@Composable
private fun MascotStill(
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
    host.Surface(entry, modifier.requiredSize(size), playing = false)
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

/** Distinguishes several surfaces of one agent in [MascotIdentityRegistry.mascotBounds]; composition-thread only. */
private var slotCounter = 0
private val GAZE_MIN_REACH = 360.dp
