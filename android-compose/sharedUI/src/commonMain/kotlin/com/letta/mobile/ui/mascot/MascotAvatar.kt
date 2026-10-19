package com.letta.mobile.ui.mascot

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.layout.ContentScale
import androidx.compose.foundation.Image
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import com.letta.mobile.avatar.core.MascotGesture
import com.letta.mobile.avatar.core.MotionPreference
import com.letta.mobile.ui.haptics.LocalHaptics
import com.letta.mobile.avatar.core.AvatarLookTarget
import com.letta.mobile.avatar.core.GazeMath
import com.letta.mobile.avatar.core.GazeReach
import com.letta.mobile.avatar.core.GazeRect
import com.letta.mobile.avatar.core.GazeWorld
import com.letta.mobile.avatar.core.MascotIdentity
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.presence.AgentActivityKind
import com.letta.mobile.data.presence.AgentPresence
import com.letta.mobile.ui.theme.LettaDimens
import com.letta.mobile.ui.theme.LocalReducedMotion

/**
 * What a platform contributes to draw a live mascot: the per-agent entry (renderer scene +
 * runtime + director) and the composable that paints an entry's scene. Everything else - which
 * agent, presence, time, gaze, sizing, clipping - is shared and lives in [MascotAvatar].
 */
interface MascotHost {
    /**
     * Whether this host can draw mascots at all. A cheap flag: every "is there a mascot?" check
     * reads this and nothing else, so listing an agent never brings up a renderer scene for it.
     */
    val available: Boolean

    /**
     * The live entry for [agentId], or null when the renderer is unavailable. Creating an entry is
     * the expensive step (a scene per agent), so only a surface about to draw calls this - never a
     * check. Entries load lazily: [MascotEntry.ensureLoaded] does the renderer work off the UI
     * thread, and [Surface] draws nothing until it has.
     */
    fun entry(agentId: String, identity: MascotIdentity): MascotEntry?

    /**
     * Paints [entry]'s scene, filling [modifier]'s bounds. With [playing] false the scene is drawn
     * once and never advanced: a still of the character for places that must not move (a header
     * chip beside a live companion), still in the entry's identity and current pose.
     */
    @Composable
    fun Surface(entry: MascotEntry, modifier: Modifier, playing: Boolean)

    /**
     * Captured stills by identity (see [MascotStills]). With them, a mascot that is not moving is an
     * image and brings up no renderer scene at all; null keeps the paused-scene still.
     */
    val stills: MascotStills? get() = null
}

/** No renderer: every mascot draws its fallback. Platforms provide a real host at their root. */
object NoMascotHost : MascotHost {
    override val available: Boolean = false

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
    if (LocalMascotRegistry.current.identities[agentId] == null) return false
    return LocalMascotHost.current.available
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
    /** The mascot is a control (it opens its agent): pointer becomes a hand and the tile is clickable. */
    onClick: (() -> Unit)? = null,
    /**
     * Which scene draws: the agent's own by default. A surface that shows one agent after another
     * in the same place (the transport layer's focused character) passes a key of its own, so the
     * scene it already has is re-skinned - one character morphing into the next - rather than
     * a new scene brought up for each agent.
     */
    sceneKey: String = agentId,
    /**
     * The character answers touch and pointer (hover, poke, pet, double-click tricks). On
     * by default wherever it is a control; a seat that takes input without a click (a hero with an
     * edit pencil) turns it on too, and one that must leave every touch to what is under it, off.
     */
    touch: MascotTouch = MascotTouch.answering(onClick),
) {
    val host = LocalMascotHost.current
    val registry = LocalMascotRegistry.current
    val entry = remember(host, sceneKey, identity) { host.entry(sceneKey, identity) } ?: return
    val presence = registry.presence[agentId] ?: AgentPresence.IDLE
    LaunchedEffect(entry, presence) { entry.ensureLoaded(); entry.apply(presence) }
    val cursor = registry.cursor.value
    val pointerPresent = cursor != null
    // Reduced motion holds one look per state (the content at work, the input at rest): no
    // wandering, and every reaction shrinks to a blink.
    val reducedMotion = LocalReducedMotion.current
    LaunchedEffect(entry, reducedMotion, pointerPresent) {
        entry.setPlatformFacts(MascotPlatformFacts(reduceMotion = reducedMotion, pointerPresent = pointerPresent))
    }
    // At rest (idle, no pointer, its last beat played out) the scene holds its pose and no frame
    // is asked for: a seat that stays composed must not redraw the window every vsync (MascotRest).
    val gestures = rememberMascotGestureEffects(entry, onClick, MotionPreference(reducedMotion))
    val engaged = mascotEngaged(presence, pointerPresent = pointerPresent) || gestures.petting
    val moving = rememberMascotMoving(engaged, identity, gestures.touches)
    // At rest the clock leaves composition and asks for no frames.
    if (moving) MascotClock(entry, registry)
    // Gaze: this tile vs the pointer, the composer / timeline rects, the other mascots and every
    // attention point the registry holds. Null input/timeline skip those plan rows; OWN/USER
    // (and CURSOR when the pointer is present) still run so the eyes are never dead. All of it is
    // read in a snapshot (te3n1.2), not in composition: a card moving or a caret stepping rebuilds
    // the world without recomposing the tile.
    val tile = remember { mutableStateOf(Rect.Zero) }
    // This surface's slot in the registry, so the other agents' mascots can look at it.
    val slotKey = remember(agentId) { "$agentId#${slotCounter++}" }
    DisposableEffect(registry, slotKey) { onDispose { registry.mascotBounds.remove(slotKey) } }
    val minReachPx = with(LocalDensity.current) { GAZE_MIN_REACH.toPx() }
    LaunchedEffect(entry, registry, agentId, minReachPx, reducedMotion) {
        snapshotFlow { registry.gazeWindow(AgentId(agentId), tile.value, GazeReach(minReachPx)) }
            .collect { entry.setGazeWorld(GazeWorld.fromWindow(it, reducedMotion = reducedMotion)) }
    }
    // requiredSize: an overscaled mascot must exceed its tile so the tile's clip crops it;
    // plain size() is coerced down to the parent's constraints and never overscales.
    Box(
        modifier = modifier.mascotSemantics(identity, live = true).requiredSize(size).onGloballyPositioned {
            val r = it.boundsInWindow()
            tile.value = r
            val slot = MascotSlot(agentId, GazeRect(r.left, r.top, r.right, r.bottom))
            if (registry.mascotBounds[slotKey] != slot) registry.mascotBounds[slotKey] = slot
        },
        contentAlignment = Alignment.Center,
    ) {
        host.Surface(entry, Modifier.matchParentSize(), playing = moving)
        if (touch == MascotTouch.ANSWERS) MascotGestureArea(size, gestures)
    }
}

/**
 * The director's timers (listening release, success hold, blink schedule) need a clock; tickTo is
 * idempotent per frame so several surfaces of one agent tick it once. The keystroke count is read
 * here, not in composition, so typing never recomposes the tile. Composed only while the scene moves.
 */
@Composable
private fun MascotClock(entry: MascotEntry, registry: MascotIdentityRegistry) {
    LaunchedEffect(entry) {
        while (true) {
            withFrameNanos {
                entry.setKeystrokes(registry.composerKeystrokes)
                entry.tickTo(it)
            }
        }
    }
}

/** [entry]'s gesture effects, kept across recompositions and updated to this pass's handlers. */
@Composable
private fun rememberMascotGestureEffects(entry: MascotEntry, onClick: (() -> Unit)?, motion: MotionPreference): MascotGestureEffects {
    val effects = remember(entry) { MascotGestureEffects() }
    effects.director = entry.director
    effects.onClick = onClick
    effects.haptics = LocalHaptics.current
    effects.reducedMotion = motion.reduced
    return effects
}

/**
 * The control over a still mascot: an invisible hit area the size of the body (the rig draws it
 * across ~60 % of the tile, a little below centre) and a hand cursor. No highlight of its own.
 */
@Composable
internal fun MascotHitArea(size: Dp, onClick: () -> Unit) {
    val clicks = remember { MutableInteractionSource() }
    Box(
        Modifier
            .mascotBodyArea(size)
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable(interactionSource = clicks, indication = null, onClick = onClick),
    )
}

/**
 * The live mascot's body as a control: the same hit area, reading every gesture for [gestures]
 *. The character answers a hover, a poke, a stroke with motion, and that is the
 * affordance; a tap still runs the seat's click at once and is still a click to accessibility.
 */
@Composable
private fun MascotGestureArea(size: Dp, gestures: MascotGestureEffects) {
    val clickable = gestures.onClick != null
    Box(
        Modifier
            .mascotBodyArea(size)
            .then(if (clickable) Modifier.pointerHoverIcon(PointerIcon.Hand).mascotTapSemantics(gestures) else Modifier)
            .mascotGestures(gestures),
    )
}

/** The body's share of a tile, a little below its centre, as a circle tests can find. */
private fun Modifier.mascotBodyArea(size: Dp): Modifier = this
    .offset(y = size * BODY_DROP_FRACTION)
    .size(size * BODY_FRACTION)
    .clip(CircleShape)
    .testTag(MASCOT_HIT_AREA_TAG)

/** One button to accessibility; its click is a tap (the agent opens, the character is poked). */
private fun Modifier.mascotTapSemantics(gestures: MascotGestureEffects): Modifier = semantics {
    role = Role.Button
    onClick {
        gestures.apply(MascotGesture.TAP, trick = 0)
        true
    }
}
/** The body's hit area; tests find the character's touchable part by it. */
internal const val MASCOT_HIT_AREA_TAG = "mascot-hit-area"

/** The body's share of a tile and how far below the tile's centre it sits. */
private const val BODY_FRACTION = 0.62f
private const val BODY_DROP_FRACTION = 0.06f

/**
 * The real mascot for an identity that belongs to no agent - a picker option, a preview - drawn
 * paused: the same Rive scene every live surface draws, with its clock stopped. It is not an
 * approximation of the character and not a separate still asset, so the shape, the body's pose and
 * the identity's rotation are exactly what the agent will look like.
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
fun mascotCandidateAvailable(identity: MascotIdentity): Boolean = LocalMascotHost.current.available

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
internal fun MascotStill(
    agentId: String,
    identity: MascotIdentity,
    size: Dp,
    modifier: Modifier = Modifier,
) {
    val host = LocalMascotHost.current
    val stills = host.stills
    val drawn = modifier.mascotSemantics(identity, live = false)
    if (stills != null) {
        // The identity's captured still: an image, no scene. Empty for the moment the one capture
        // of a new identity takes; after that, and on every later launch, it is simply there.
        val image = stills.get(identity)
        LaunchedEffect(stills, identity) { stills.ensure(identity) }
        if (image != null) {
            Image(image, contentDescription = null, modifier = drawn.requiredSize(size), contentScale = ContentScale.Fit)
        } else {
            Box(drawn.requiredSize(size))
        }
        return
    }
    val registry = LocalMascotRegistry.current
    val entry = remember(host, agentId, identity) { host.entry(agentId, identity) } ?: return
    val presence = registry.presence[agentId] ?: AgentPresence.IDLE
    LaunchedEffect(entry, presence) { entry.ensureLoaded(); entry.apply(presence) }
    host.Surface(entry, drawn.requiredSize(size), playing = false)
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
internal val GAZE_MIN_REACH = 360.dp

/** The identity to draw for [agentId], or null when it has none or the host cannot draw it. */
@Composable
private fun drawableIdentity(agentId: String?): MascotIdentity? {
    val identity = agentId?.let { LocalMascotRegistry.current.identities[it] } ?: return null
    val host = LocalMascotHost.current
    return identity.takeIf { host.available && host.entry(agentId, identity) != null }
}

private fun Modifier.clickableIfSet(onClick: (() -> Unit)?): Modifier {
    return if (onClick != null) clickable(onClick = onClick) else this
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
    cornerRadius: Dp = LettaDimens.Radius.sm,
    onClick: (() -> Unit)? = null,
    overscale: Float = MASCOT_TILE_OVERSCALE,
    live: Boolean = mascotAtWork(agentId),
    fallback: @Composable () -> Unit,
) {
    val identity = drawableIdentity(agentId)
    if (agentId == null || identity == null) {
        fallback()
        return
    }
    Box(
        modifier = modifier.size(size).clip(RoundedCornerShape(cornerRadius)).clickableIfSet(onClick),
        contentAlignment = Alignment.Center,
    ) {
        MascotInPlace(agentId, identity, size = size * overscale, live = live)
    }
}
