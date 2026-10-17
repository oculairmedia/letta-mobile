package com.letta.mobile.ui.mascot

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitTouchSlopOrCancellation
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import com.letta.mobile.avatar.core.MascotBody
import com.letta.mobile.avatar.core.MascotDirector
import com.letta.mobile.avatar.core.MascotEvent
import com.letta.mobile.avatar.core.MascotIdentity
import com.letta.mobile.avatar.core.MascotVector
import com.letta.mobile.data.model.AgentId
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import com.letta.mobile.ui.haptics.LocalHaptics
import com.letta.mobile.ui.theme.LettaDimens
import com.letta.mobile.ui.theme.LocalReducedMotion

/**
 * The places an agent's mascot can stand. One character per agent, in one place at a time: it
 * rests beside the composer and is *transported* - moved, not duplicated - to the agent pane's
 * hero seat when the pane shows. Editing the agent moves nothing: the editor is configuration,
 * and the character morphs where it stands as the user picks (see [MascotTransport.preview]).
 * Lower [rank] is nearer rest; a mascot whose requested seat goes away settles into its
 * lowest-ranked seat.
 */
enum class MascotStage(internal val rank: Int) {
    /** A fresh conversation's greeting: the agent at hero size, the page itself. Rest while it shows. */
    WELCOME_HERO(0),
    COMPOSER_COMPANION(1),
    AGENT_PANE_HERO(2),
}

/**
 * A declared seat: where it is in the window, how large the character draws there, and its
 * handlers. The handlers live in a [SeatHandlers] holder that is identity-equal on purpose: a
 * recomposition hands a seat fresh lambdas, and if they took part in equality every pass would
 * write the seat back into the transport's state and recompose the layer - a loop, every frame.
 */
internal data class MascotSeatInfo(
    val bounds: Rect,
    val overscale: Float,
    val identity: MascotIdentity?,
    val handlers: SeatHandlers,
    /**
     * The seat box's own width in px, before any layer scale. A seat that scales itself (the chat
     * page's companion shrinking into the docked panel's badge) reports scaled [bounds]; the layer
     * keeps drawing the character at this size and scales it, so the renderer is never resized
     * per frame. Zero means the same as [bounds].
     */
    val layoutWidth: Float = 0f,
) {
    val onClick: (() -> Unit)? get() = handlers.onClick
    val onEdit: (() -> Unit)? get() = handlers.onEdit

    /** The seat answers anything at all - a click, the pencil, a drag - so its character may be touched. */
    val takesInput: Boolean get() = handlers.onClick != null || handlers.editable || handlers.draggable

    /** The box the character is drawn in, before the seat's own scale. */
    val drawWidth: Float get() = seatDrawWidth()

    /** [layoutWidth] when the seat reports one, else the width of its [bounds]. */
    private fun seatDrawWidth(): Float {
        return if (layoutWidth > 0f) layoutWidth else bounds.width
    }
}

/**
 * The latest click / edit handlers of one seat; updated in place, never compared. Only whether a
 * seat has an edit or a drag handler at all is snapshot state: the layer installs its hover and
 * drag input for those alone, so a seat with neither leaves every touch to what is under it.
 */
internal class SeatHandlers {
    var onClick: (() -> Unit)? = null

    /** Opens the agent's editor; every drawn mascot offers it as a pencil badge on hover. */
    var onEdit: (() -> Unit)? = null
        set(value) {
            field = value
            syncEditable()
        }

    /**
     * A drag on the character, in dp; null leaves drags to whatever is under it. The layer draws
     * above every seat, so a seat that moves its surface (the chat dock) can only be grabbed here.
     */
    var onDrag: ((dxDp: Float, dyDp: Float) -> Unit)? = null
        set(value) {
            field = value
            syncDraggable()
        }

    /**
     * Whether a drag starting now moves anything (the dock's companion only while it sits on the
     * dock). Read as each gesture starts: a gesture it turns down is never consumed.
     */
    var dragEnabled: () -> Boolean = { true }

    var editable: Boolean by mutableStateOf(false)
        private set
    var draggable: Boolean by mutableStateOf(false)
        private set

    /** Writes [editable] only when it changes, so an unchanged handler swap records no state write. */
    private fun syncEditable() {
        val hasHandler = onEdit != null
        if (editable != hasHandler) editable = hasHandler
    }

    /** Writes [draggable] only when it changes, so an unchanged handler swap records no state write. */
    private fun syncDraggable() {
        val hasHandler = onDrag != null
        if (draggable != hasHandler) draggable = hasHandler
    }
}

/**
 * The transport verb, and the seats it can send a mascot to. One per window (or activity): every
 * surface declares its seat with [MascotSeat], the shell calls [transportTo] / [rest], and
 * [MascotTransportLayer] draws each agent's live mascot at whichever seat is active - animating
 * between them - so the choreography lives here and nowhere else.
 */
class MascotTransport {
    internal val seats = mutableStateMapOf<SeatKey, MascotSeatInfo>()
    private val requested = mutableStateMapOf<String, MascotStage>()
    private val previews = mutableStateMapOf<String, MascotIdentity>()

    /**
     * An identity to draw for [agentId] instead of its own - the editor's unsaved pick - so the
     * character morphs live wherever it stands while the user chooses; null ends the preview.
     */
    fun preview(agentId: String, identity: MascotIdentity?) {
        if (identity == null) previews.remove(agentId) else previews[agentId] = identity
    }

    internal fun previewOf(agentId: String): MascotIdentity? = previews[agentId]

    /** True while a [MascotTransportLayer] draws; without one every seat draws its own mascot in place. */
    internal var layerMounted by mutableStateOf(false)

    /**
     * One slot's flight (letta-mobile-te3n1.3): the character's [body] - where it is along a hop and
     * how it squashes, stretches and leans - how far it has come in on its entrance, and the stage
     * it was last seen standing on. Kept here, not in the drawing composable, so a recomposition or
     * a stage flicker during the sidebar's own animation cannot reset a hop halfway through.
     */
    internal class Flight {
        val body = MascotBody()

        /** Bumped each frame the body moves; read only at placement and draw time, never in composition. */
        var frame: Int by mutableIntStateOf(0)

        /** Reads [frame] so the placement or layer block calling it runs again when the body moves. */
        fun observeFrame(): Int = frame

        /** Bumped when the body is set moving; the slot's driver steps it until it rests again. */
        var wakes: Int by mutableIntStateOf(0)

        /** The entrance, 0 (not yet in) .. 1 (standing); a slot's first appearance rises from the feet. */
        val presence = Animatable(0f)

        /** Where the hop started; the endpoint is the destination seat as it is each frame. */
        var from: Rect = Rect.Zero
        /** The stage last shown; null until the slot first stands somewhere (it enters there, no hop). */
        var shownStage: MascotStage? = null

        /** The agent last shown in this slot; a slot that is re-skinned to another agent hops from *its* seat. */
        var shownAgent: String? = null

        /** Where the character was last placed (centre and width, window px); a hop whose seat is gone starts here. */
        var drawnX = 0f
        var drawnY = 0f
        var drawnWidth = 0f
        var hasDrawn = false

        /** The last placement as a rect, or null before the first. */
        fun drawnRect(): Rect? {
            if (!hasDrawn) return null
            val half = drawnWidth / 2f
            return Rect(drawnX - half, drawnY - half, drawnX + half, drawnY + half)
        }
    }

    private val flights = HashMap<String, Flight>()

    internal fun flight(sceneKey: String): Flight {
        return flights.getOrPut(sceneKey) { Flight() }
    }

    /** Test probe: how many times the layer has composed a transported character. A hop must not add any. */
    internal var transportedCompositions = 0

    /**
     * Told when a character lands: at the end of each hop and of its entrance, with the impact
     * (0..1, how fast it came in). The hook the mascot director (letta-mobile-te3n1.1) calls its
     * landing reaction from; null tells no one.
     */
    var motionListener: MascotMotionListener? = null

    /**
     * True once the mascot director writes the body's motion to a rig that animates it itself
     * (letta-mobile-te3n1.5 inputs: [MascotBody.motionX] -> `moveX`, [MascotBody.motionY] ->
     * `moveY`, each landing's impact -> `impact` then `land`). The layer then still carries the
     * character along its hop, between seat sizes and in on its entrance, but leaves the squash,
     * stretch and lean to the file, so they are not applied twice. False (the default) draws them
     * on the layer, which is also what stills and orbs get.
     */
    var rigCarriesBody: Boolean by mutableStateOf(false)

    /**
     * [agentId]'s body as drawn now, or null when no slot shows it: host motion data for the
     * director - [MascotBody.motionX]/[MascotBody.motionY] (seat travel plus drag velocity, -1..1)
     * for the rig's moveX/moveY. Landings arrive through [motionListener].
     */
    fun bodyOf(agent: AgentId): MascotBody? = flights.values.firstOrNull { it.shownAgent == agent.value }?.body

    /** Sends [agentId]'s mascot to [stage]. It goes as soon as that stage has a seat and comes back to rest when the seat leaves. */
    fun transportTo(agentId: String, stage: MascotStage) {
        requested[agentId] = stage
    }

    /** Lets [agentId]'s mascot settle back to its rest seat. */
    fun rest(agentId: String) {
        requested.remove(agentId)
    }

    /** Where [agentId] stands right now, or null when it has no seat anywhere. */
    fun activeStage(agentId: String): MascotStage? {
        val seated = seats.keys.filter { it.agentId == agentId }.map { it.stage }.toSet()
        return activeStage(seated = seated, requested = requested[agentId])
    }

    internal fun agentsSeated(): List<String> {
        return seats.keys.map { it.agentId }.distinct()
    }

    internal fun seat(key: SeatKey): MascotSeatInfo? = seats[key]

    internal data class SeatKey(val agentId: String, val stage: MascotStage)

    companion object {
        /** The rule, on its own so it can be tested: the requested stage while it is seated, else rest (the lowest rank present). */
        fun activeStage(seated: Set<MascotStage>, requested: MascotStage?): MascotStage? {
            return requested?.takeIf { it in seated } ?: seated.minByOrNull { it.rank }
        }
    }
}

/** The window's transport; the default is a private one so a seat outside any shell still draws. */
val LocalMascotTransport = compositionLocalOf { MascotTransport() }

/** Publishes seat [key] at [bounds] as [describe] says, or nothing while either is unknown; unchanged seats are not rewritten. */
internal fun MascotTransport.publishSeat(key: MascotTransport.SeatKey?, bounds: Rect?, describe: (Rect) -> MascotSeatInfo) {
    if (key == null || bounds == null) return
    val next = describe(bounds)
    if (seats[key] != next) seats[key] = next
}

/** One agent's mascot as the layer draws it: who, in what look, at which seat. */
private class SeatedMascot(val agentId: String, val identity: MascotIdentity, val seat: MascotSeatInfo)

@Composable
private fun TransportedMascot(
    seated: SeatedMascot,
    origin: Offset,
    reducedMotion: Boolean,
    sceneKey: String,
) {
    val agentId = seated.agentId
    val identity = seated.identity
    val seat = seated.seat
    val transport = LocalMascotTransport.current
    val flight = transport.flight(sceneKey)
    SideEffect { transport.transportedCompositions++ }
    // Every hop is the same function (letta-mobile-te3n1.3): the body crouches, springs along an
    // arc to the destination seat with one overshoot, stretching along its travel and leaning
    // into it, and lands with a squash. The endpoint is the destination seat *as it is each
    // frame* - a pane that is still opening moves its seat, and the character follows it in
    // without the spring restarting. A stage change mid-flight hops on from where the character
    // is drawn, keeping its squash and lean, so nothing ever jumps.
    // The slot outlives the agent it shows: the driver reads whichever agent stands here now.
    val currentAgent by rememberUpdatedState(agentId)
    val density = LocalDensity.current
    // The slot's scene (the one MascotLive below draws): its director hears the body's travel and
    // landings (te3n1.1 over te3n1.3), and a rig that animates them itself takes the deform over.
    val host = LocalMascotHost.current
    val director = remember(host, sceneKey, identity) { host.entry(sceneKey, identity) }?.director
    LaunchedEffect(director) { if (director?.carriesBody == true) transport.rigCarriesBody = true }
    LaunchedEffect(director, flight) {
        val d = director ?: return@LaunchedEffect
        // Keyed on the flight's frame counter, so a body at rest asks for no frames.
        snapshotFlow { flight.observeFrame() }.collect { d.setMotion(flight.body.motionX, flight.body.motionY) }
    }
    // The body answers the director's reactions (a poke, a trick, a send, a finished run) with a
    // squash, and a drag's pick-up and drop reach the director (te3n1.4).
    FlightReactionsEffect(flight, director, reducedMotion)
    val dragFeel = rememberDragFeel(flight, director, reducedMotion)
    val landed: (Float) -> Unit = { impact ->
        transport.landed(flight, impact)
        director?.onEvent(MascotEvent.Landed(impact))
    }
    LaunchedEffect(sceneKey, reducedMotion) {
        if (reducedMotion) flight.body.snap()
        launch { flight.enter(reducedMotion, landed) }
        launch { flight.drive(reducedMotion, landed) }
        val motion = HopMotion(reducedMotion, density.density)
        snapshotFlow { currentAgent.let { agent -> transport.activeStage(agent)?.let { MascotTransport.SeatKey(agent, it) } } }
            .distinctUntilChanged()
            .collect { next -> if (next != null) transport.beginHop(flight, next, motion) }
    }
    // The renderer's node keeps the DESTINATION size for the whole flight and the change of size
    // is a layer scale: a Rive surface re-allocates its readback buffer and restarts its frame
    // loop on every size change, so resizing it per frame is what made a hop stutter. Where the
    // character is and how it is shaped are read at placement and draw time only (the flight's
    // frame counter), so a hop moves the layer without recomposing it.
    val boxSize = with(density) { seat.drawWidth.toDp() }
    val hover = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    Box(
        modifier = Modifier
            .offset { flight.placement(seat, origin) }
            .requiredSize(boxSize)
            .graphicsLayer { flight.applyPose(this, seat, reducedMotion, deform = !transport.rigCarriesBody) }
            // Hover (for the pencil) and drag only where the seat has them: a seat with neither
            // takes no input at all, and what it stands over (the Touch chat head) keeps its own.
            .seatInput(seat.handlers, hover, SeatDrag(flight, dragFeel)),
        contentAlignment = Alignment.Center,
    ) {
        MascotLive(
            agentId,
            identity,
            size = boxSize * seat.overscale,
            onClick = seat.onClick,
            sceneKey = sceneKey,
            // A seat that takes input at all lets the character be poked and petted; one that
            // takes none (over the Touch chat head) leaves every touch to what is under it.
            interactive = seat.takesInput,
        )
        // The pencil, in front of the character (the layer draws above every seat), on hover only:
        // one way to edit the agent from any mascot, so no seat needs to travel to the editor.
        seat.onEdit?.let { onEdit ->
            if (hovered) MascotEditBadge(onEdit, Modifier.align(Alignment.BottomEnd))
        }
    }
}

/**
 * Starts [flight]'s hop to the seat [next] (the agent the slot shows now, at its active stage),
 * from wherever it is drawn this frame; nothing when that stage is the one already shown. The
 * slot's first stage is no hop: the character enters there ([MascotTransport.Flight.enter]).
 */
private fun MascotTransport.beginHop(flight: MascotTransport.Flight, next: MascotTransport.SeatKey, motion: HopMotion) {
    if (next.stage == flight.shownStage) return
    val ends = hopEnds(flight, next)
    flight.shownStage = next.stage
    flight.shownAgent = next.agentId
    if (ends == null || motion.reducedMotion) {
        flight.body.snap()
        flight.frame++
        return
    }
    val (from, destination) = ends
    flight.from = from
    val density = motion.density
    flight.body.hop(MascotVector((destination.center.x - from.center.x) / density, (destination.center.y - from.center.y) / density))
    flight.wakes++
}

/**
 * Where [flight]'s hop to [next] runs from (the character as drawn now) and to; null for the
 * slot's first stage, or when either end has no place yet - the character then simply stands.
 */
private fun MascotTransport.hopEnds(flight: MascotTransport.Flight, next: MascotTransport.SeatKey): Pair<Rect, Rect>? {
    val previous = flight.shownStage ?: return null
    val leaving = seat(MascotTransport.SeatKey(flight.shownAgent ?: next.agentId, previous))?.bounds
    val from = leaving?.let { flight.shownRect(it) } ?: flight.drawnRect() ?: return null
    val destination = seat(next)?.bounds ?: return null
    return from to destination
}

/** How the layer moves: whether motion is reduced, and the screen density that turns px into the body's dp. */
private class HopMotion(val reducedMotion: Boolean, val density: Float)

/** Reports [flight]'s landing to the transport's listener, for whichever agent stands in the slot. */
private fun MascotTransport.landed(flight: MascotTransport.Flight, impact: Float) {
    val agentId = flight.shownAgent ?: return
    motionListener?.onLanded(AgentId(agentId), impact)
}

/** Hooks [flight]'s body to [director]'s reactions ([FlightReactions]) while this slot shows it. */
@Composable
private fun FlightReactionsEffect(flight: MascotTransport.Flight, director: MascotDirector?, reducedMotion: Boolean) {
    val transport = LocalMascotTransport.current
    val reactions = remember(flight, transport) { FlightReactions(flight) { transport.rigCarriesBody } }
    reactions.reducedMotion = reducedMotion
    DisposableEffect(director, reactions) {
        director?.reactionListener = reactions
        onDispose { director?.takeIf { it.reactionListener === reactions }?.reactionListener = null }
    }
}

/** [flight]'s drag feel, kept across recompositions and updated to this pass's director. */
@Composable
private fun rememberDragFeel(flight: MascotTransport.Flight, director: MascotDirector?, reducedMotion: Boolean): MascotDragFeel {
    val feel = remember(flight) { MascotDragFeel(flight) }
    feel.director = director
    feel.haptics = LocalHaptics.current
    feel.reducedMotion = reducedMotion
    return feel
}

/** What a seat drag moves: the slot's body (its lean) and what it tells the character ([MascotDragFeel]). */
private class SeatDrag(val flight: MascotTransport.Flight, val feel: MascotDragFeel)

/** Hover for the pencil when the seat is [SeatHandlers.editable], drag when it is [SeatHandlers.draggable]. */
private fun Modifier.seatInput(handlers: SeatHandlers, hover: MutableInteractionSource, drag: SeatDrag): Modifier {
    val hoverInput = if (handlers.editable) Modifier.hoverable(hover) else Modifier
    val dragInput = if (handlers.draggable) Modifier.seatDrag(handlers, drag) else Modifier
    return then(hoverInput).then(dragInput)
}

/**
 * Drags the character for [handlers]. A gesture is taken (its slop consumed) only when the seat's
 * gate is open as it starts; otherwise it is left alone for whatever is under the layer. The
 * pointer's velocity goes to [flight]'s body as host data (letta-mobile-te3n1: the host reads
 * gestures, the file animates), so the character leans into the drag and the rig hears it; the
 * director holds DRAGGED from the slop to the release and lands it at the release speed (te3n1.4).
 */
private fun Modifier.seatDrag(handlers: SeatHandlers, drag: SeatDrag): Modifier {
    return pointerInput(handlers, drag.flight) { seatDragGestures(handlers, drag) }
}

/** The gesture loop behind [seatDrag]: gate, slop, then every drag delta to the seat's handler in dp. */
private suspend fun PointerInputScope.seatDragGestures(handlers: SeatHandlers, seatDrag: SeatDrag) {
    val flight = seatDrag.flight
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        if (!handlers.dragEnabled()) return@awaitEachGesture
        val start = awaitTouchSlopOrCancellation(down.id) { change, overSlop ->
            change.consume()
            handlers.onDrag?.invoke(overSlop.x.toDp().value, overSlop.y.toDp().value)
        } ?: return@awaitEachGesture
        seatDrag.feel.pickedUp()
        var vx = 0f
        var vy = 0f
        try {
            drag(start.id) { change ->
                // Read per event: the seat's handler changes in place.
                val amount = change.positionChange()
                change.consume()
                val dxDp = amount.x.toDp().value
                val dyDp = amount.y.toDp().value
                handlers.onDrag?.invoke(dxDp, dyDp)
                val seconds = (change.uptimeMillis - change.previousUptimeMillis) / MILLIS_PER_SECOND
                if (seconds > 0f) {
                    // Smoothed, so one jittery event cannot fling the lean.
                    vx += (dxDp / seconds - vx) * DRAG_SMOOTHING
                    vy += (dyDp / seconds - vy) * DRAG_SMOOTHING
                    flight.body.drag(MascotVector(vx, vy))
                    flight.wakes++
                }
            }
        } finally {
            flight.body.drag(null)
            flight.wakes++
            seatDrag.feel.putDown(vx, vy)
        }
    }
}

private const val MILLIS_PER_SECOND = 1_000f
private const val DRAG_SMOOTHING = 0.5f

@Composable
private fun MascotEditBadge(onEdit: () -> Unit, modifier: Modifier) {
    Box(
        modifier
            .size(EDIT_BADGE_SIZE)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable(onClick = onEdit),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "\u270E",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private val EDIT_BADGE_SIZE = LettaDimens.Space.xl

/** Hears the transport's motion events; the mascot director's hook (letta-mobile-te3n1.1). */
fun interface MascotMotionListener {
    /** [agent]'s character landed at a seat, with [impact] 0..1 (how fast it came in). */
    fun onLanded(agent: AgentId, impact: Float)
}

/** Every agent with an active seat, in what look it draws there; an agent with no seat or no identity draws nothing. */
private fun MascotTransport.seatedMascots(registry: MascotIdentityRegistry): List<SeatedMascot> {
    return agentsSeated().mapNotNull { agentId ->
        val stage = activeStage(agentId) ?: return@mapNotNull null
        val seat = seat(MascotTransport.SeatKey(agentId, stage)) ?: return@mapNotNull null
        val identity = seat.identity ?: previewOf(agentId) ?: registry.identities[agentId] ?: return@mapNotNull null
        SeatedMascot(agentId, identity, seat)
    }
}

/**
 * Draws, over [content], one live mascot per seated agent at its active seat, and springs it
 * between seats when the active seat changes - the whole transport animation, in one place. Mount
 * once at the window root, inside the mascot host and registry providers. [reducedMotion] (the
 * shell's [LocalReducedMotion] by default) snaps every hop and keeps only a short entrance fade.
 */
@Composable
fun MascotTransportLayer(
    reducedMotion: Boolean = LocalReducedMotion.current,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val transport = LocalMascotTransport.current
    val registry = LocalMascotRegistry.current
    var origin by remember { mutableStateOf(Offset.Zero) }
    DisposableEffect(transport) {
        transport.layerMounted = true
        onDispose { transport.layerMounted = false }
    }
    Box(modifier.onGloballyPositioned { origin = it.boundsInWindow().topLeft }) {
        content()
        // Keyed by slot, not by agent: the focused character is one scene that is re-skinned when
        // the focused agent changes, so agent A morphs into agent B where it stands instead of a
        // fresh scene loading in with a flash.
        transport.seatedMascots(registry).forEachIndexed { slot, seated ->
            key(slot) {
                TransportedMascot(seated, origin, reducedMotion, sceneKey = "mascot-focus-$slot")
            }
        }
    }
}

