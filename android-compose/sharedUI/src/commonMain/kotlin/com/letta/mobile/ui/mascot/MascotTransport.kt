package com.letta.mobile.ui.mascot

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.letta.mobile.avatar.core.MascotIdentity
import kotlin.math.roundToInt
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

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
) {
    val onClick: (() -> Unit)? get() = handlers.onClick
    val onEdit: (() -> Unit)? get() = handlers.onEdit
}

/** The latest click / edit handlers of one seat; updated in place, never compared. */
internal class SeatHandlers {
    var onClick: (() -> Unit)? = null

    /** Opens the agent's editor; every drawn mascot offers it as a pencil badge on hover. */
    var onEdit: (() -> Unit)? = null
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
     * Each agent's flight: how present the character is (1 = standing, 0 = mid-hop) and the stage it
     * was last seen standing on. Kept here, not in the drawing composable, so a recomposition or a
     * stage flicker during the sidebar's own animation cannot reset a hop halfway through.
     */
    internal class Flight(initial: MascotStage?) {
        val presence = Animatable(1f)
        /** 0..1 along the hop; 1 when standing. */
        val progress = Animatable(1f)
        /** Where the hop started; the endpoint is the destination seat as it is each frame. */
        var from: Rect = Rect.Zero
        var shownStage: MascotStage? = initial
    }

    private val flights = HashMap<String, Flight>()

    internal fun flight(agentId: String): Flight = flights.getOrPut(agentId) { Flight(activeStage(agentId)) }

    /** Sends [agentId]'s mascot to [stage]. It goes as soon as that stage has a seat and comes back to rest when the seat leaves. */
    fun transportTo(agentId: String, stage: MascotStage) {
        requested[agentId] = stage
    }

    /** Lets [agentId]'s mascot settle back to its rest seat. */
    fun rest(agentId: String) {
        requested.remove(agentId)
    }

    /** Where [agentId] stands right now, or null when it has no seat anywhere. */
    fun activeStage(agentId: String): MascotStage? =
        activeStage(seated = seats.keys.filter { it.agentId == agentId }.map { it.stage }.toSet(), requested = requested[agentId])

    internal fun agentsSeated(): List<String> = seats.keys.map { it.agentId }.distinct()

    internal fun seat(agentId: String, stage: MascotStage): MascotSeatInfo? = seats[SeatKey(agentId, stage)]

    internal data class SeatKey(val agentId: String, val stage: MascotStage)

    companion object {
        /** The rule, on its own so it can be tested: the requested stage while it is seated, else rest (the lowest rank present). */
        fun activeStage(seated: Set<MascotStage>, requested: MascotStage?): MascotStage? =
            requested?.takeIf { it in seated } ?: seated.minByOrNull { it.rank }
    }
}

/** The window's transport; the default is a private one so a seat outside any shell still draws. */
val LocalMascotTransport = compositionLocalOf { MascotTransport() }

/**
 * Reserves [size] for [agentId]'s mascot at [stage] and tells the transport where that is. While
 * the mascot stands here the layer draws it over this box (at [size] times [overscale], the way
 * [MascotAvatar] overscales a tile); while it stands elsewhere, or the agent has no mascot,
 * [empty] draws - the seat the character has left, or a plain orb for an agent without one.
 * [identity] overrides the registry's for this seat (the editor previews an unsaved pick this way
 * and the live mascot morphs into it).
 */
@Composable
fun MascotSeat(
    agentId: String?,
    stage: MascotStage,
    size: Dp,
    modifier: Modifier = Modifier,
    overscale: Float = 1f,
    identity: MascotIdentity? = null,
    onClick: (() -> Unit)? = null,
    onEdit: (() -> Unit)? = null,
    empty: @Composable () -> Unit,
) {
    val transport = LocalMascotTransport.current
    val registry = LocalMascotRegistry.current
    val shown = identity ?: agentId?.let { transport.previewOf(it) ?: registry.identities[it] }
    val available = agentId != null && shown != null && LocalMascotHost.current.available
    val key = agentId?.let { MascotTransport.SeatKey(it, stage) }
    val handlers = remember { SeatHandlers() }
    handlers.onClick = onClick
    handlers.onEdit = onEdit
    DisposableEffect(transport, key) {
        onDispose { key?.let { transport.seats.remove(it) } }
    }
    val standsHere = available && transport.layerMounted && transport.activeStage(agentId!!) == stage
    Box(
        modifier = modifier.requiredSize(size).onGloballyPositioned { coords ->
            if (key == null || !available) return@onGloballyPositioned
            val next = MascotSeatInfo(coords.boundsInWindow(), overscale, identity, handlers)
            if (transport.seats[key] != next) transport.seats[key] = next
        },
        contentAlignment = Alignment.Center,
    ) {
        when {
            !available -> empty()
            !transport.layerMounted -> MascotLive(agentId!!, shown!!, size = size * overscale, onClick = onClick)
            !standsHere -> empty()
        }
    }
}

/**
 * Draws, over [content], one live mascot per seated agent at its active seat, and slides and
 * scales it between seats when the active seat changes - the whole transport animation, in one
 * place. Mount once at the window root, inside the mascot host and registry providers.
 */
@Composable
fun MascotTransportLayer(
    reducedMotion: Boolean = false,
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
        for (agentId in transport.agentsSeated()) {
            val stage = transport.activeStage(agentId) ?: continue
            val seat = transport.seat(agentId, stage) ?: continue
            val identity = seat.identity ?: transport.previewOf(agentId) ?: registry.identities[agentId] ?: continue
            key(agentId) {
                TransportedMascot(agentId, identity, stage, seat, origin, reducedMotion)
            }
        }
    }
}

@Composable
private fun TransportedMascot(
    agentId: String,
    identity: MascotIdentity,
    stage: MascotStage,
    seat: MascotSeatInfo,
    origin: Offset,
    reducedMotion: Boolean,
) {
    val transport = LocalMascotTransport.current
    val flight = transport.flight(agentId)
    // Every hop is the same function: progress runs the one 360 ms curve while the character
    // fades and shrinks as it leaves and fades and grows back as it arrives. The endpoint is the
    // destination seat *as it is each frame* - a pane that is still opening moves its seat, and
    // the character follows it in without the curve restarting - so every pair of seats gets the
    // identical motion and no hop waits. Hops run one after another off a conflated stream of
    // stage changes, so a flicker mid-flight cannot restart one halfway.
    LaunchedEffect(agentId, reducedMotion) {
        snapshotFlow { transport.activeStage(agentId) }
            .distinctUntilChanged()
            .conflate()
            .collect { next ->
                if (next == null || next == flight.shownStage) return@collect
                flight.from = shownRect(flight, transport.seat(agentId, flight.shownStage ?: next)?.bounds ?: flight.from)
                flight.shownStage = next
                if (reducedMotion) {
                    flight.progress.snapTo(1f)
                    flight.presence.snapTo(1f)
                    return@collect
                }
                flight.progress.snapTo(0f)
                coroutineScope {
                    launch { flight.progress.animateTo(1f, tween(TRANSPORT_MILLIS, easing = FastOutSlowInEasing)) }
                    flight.presence.animateTo(0f, tween(TRANSPORT_LEAVE_MILLIS))
                    flight.presence.animateTo(1f, tween(TRANSPORT_MILLIS - TRANSPORT_LEAVE_MILLIS, easing = FastOutSlowInEasing))
                }
            }
    }
    // The renderer's node keeps the DESTINATION size for the whole flight and the change of size
    // is a layer scale: a Rive surface re-allocates its readback buffer and restarts its frame
    // loop on every size change, so resizing it per frame is what made a hop stutter.
    val rect = shownRect(flight, seat.bounds)
    val density = LocalDensity.current
    val boxSize = with(density) { seat.bounds.width.toDp() }
    val flightScale = if (seat.bounds.width > 0f) rect.width / seat.bounds.width else 1f
    val hover = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    Box(
        modifier = Modifier
            .offset {
                IntOffset(
                    (rect.center.x - seat.bounds.width / 2f - origin.x).roundToInt(),
                    (rect.center.y - seat.bounds.height / 2f - origin.y).roundToInt(),
                )
            }
            .requiredSize(boxSize)
            .graphicsLayer {
                val presence = flight.presence.value
                alpha = presence
                val scale = flightScale * (TRANSPORT_MIN_SCALE + (1f - TRANSPORT_MIN_SCALE) * presence)
                scaleX = scale
                scaleY = scale
            }
            .hoverable(hover),
        contentAlignment = Alignment.Center,
    ) {
        MascotLive(agentId, identity, size = boxSize * seat.overscale, onClick = seat.onClick)
        // The pencil, in front of the character (the layer draws above every seat), on hover only:
        // one way to edit the agent from any mascot, so no seat needs to travel to the editor.
        seat.onEdit?.let { onEdit ->
            if (hovered) MascotEditBadge(onEdit, Modifier.align(Alignment.BottomEnd))
        }
    }
}

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

private val EDIT_BADGE_SIZE = 22.dp

/** Where the character is drawn: [flight.from] eased toward [to] by the hop's progress (already eased by its spec). */
private fun shownRect(flight: MascotTransport.Flight, to: Rect): Rect {
    val t = flight.progress.value
    if (t >= 1f) return to
    return androidx.compose.ui.geometry.lerp(flight.from, to, t)
}

/** One transport: the move takes [TRANSPORT_MILLIS]; the character is gone by [TRANSPORT_LEAVE_MILLIS] and back by the end. */
private const val TRANSPORT_MILLIS = 360
private const val TRANSPORT_LEAVE_MILLIS = 140
private const val TRANSPORT_MIN_SCALE = 0.6f
