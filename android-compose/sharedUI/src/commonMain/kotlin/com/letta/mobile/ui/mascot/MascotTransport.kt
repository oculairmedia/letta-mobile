package com.letta.mobile.ui.mascot

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.requiredSize
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
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import com.letta.mobile.avatar.core.MascotIdentity
import kotlin.math.roundToInt
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * The places an agent's mascot can stand. One character per agent, in one place at a time: it
 * rests beside the composer, and is *transported* - moved, not duplicated - to the agent pane's
 * hero seat when the user opens the agent, and to the editor's when they edit it. Lower [rank] is
 * nearer rest; a mascot whose requested seat goes away settles into its lowest-ranked seat.
 */
enum class MascotStage(internal val rank: Int) {
    /** A fresh conversation's greeting: the agent at hero size, the page itself. Rest while it shows. */
    WELCOME_HERO(0),
    COMPOSER_COMPANION(1),
    AGENT_PANE_HERO(2),
    EDIT_AGENT_HERO(3),
}

/** A declared seat: where it is in the window, how large the character draws there, and what a click does. */
internal data class MascotSeatInfo(
    val bounds: Rect,
    val overscale: Float,
    val identity: MascotIdentity?,
    val onClick: (() -> Unit)?,
)

/**
 * The transport verb, and the seats it can send a mascot to. One per window (or activity): every
 * surface declares its seat with [MascotSeat], the shell calls [transportTo] / [rest], and
 * [MascotTransportLayer] draws each agent's live mascot at whichever seat is active - animating
 * between them - so the choreography lives here and nowhere else.
 */
class MascotTransport {
    internal val seats = mutableStateMapOf<SeatKey, MascotSeatInfo>()
    private val requested = mutableStateMapOf<String, MascotStage>()

    /** True while a [MascotTransportLayer] draws; without one every seat draws its own mascot in place. */
    internal var layerMounted by mutableStateOf(false)

    /**
     * Each agent's flight: how present the character is (1 = standing, 0 = mid-hop) and the stage it
     * was last seen standing on. Kept here, not in the drawing composable, so a recomposition or a
     * stage flicker during the sidebar's own animation cannot reset a hop halfway through.
     */
    internal class Flight(initial: MascotStage?) {
        val presence = Animatable(1f)
        val rect = Animatable(Rect.Zero, Rect.VectorConverter)
        var shownStage: MascotStage? = initial
        var flying: Boolean = false
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
    empty: @Composable () -> Unit,
) {
    val transport = LocalMascotTransport.current
    val registry = LocalMascotRegistry.current
    val shown = identity ?: agentId?.let { registry.identities[it] }
    val available = agentId != null && shown != null && LocalMascotHost.current.entry(agentId, shown) != null
    val key = agentId?.let { MascotTransport.SeatKey(it, stage) }
    DisposableEffect(transport, key) {
        onDispose { key?.let { transport.seats.remove(it) } }
    }
    val standsHere = available && transport.layerMounted && transport.activeStage(agentId!!) == stage
    Box(
        modifier = modifier.requiredSize(size).onGloballyPositioned { coords ->
            if (key == null || !available) return@onGloballyPositioned
            val next = MascotSeatInfo(coords.boundsInWindow(), overscale, identity, onClick)
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
            val identity = seat.identity ?: registry.identities[agentId] ?: continue
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
    // Every hop is the same function of (from, to): the character fades and shrinks as it leaves,
    // moves along one eased path, and fades and grows back as it arrives. A hop only starts once
    // the destination seat has stopped moving (a pane opening animates its own layout for a few
    // frames), so the path is one curve rather than a chase after a target that is still sliding -
    // that chase was why the same hop felt different from seat to seat. Hops run one after another
    // off a conflated stream of stage changes, so a flicker mid-flight cannot restart one halfway.
    LaunchedEffect(agentId, reducedMotion) {
        snapshotFlow { transport.activeStage(agentId) }
            .distinctUntilChanged()
            .conflate()
            .collect { next ->
                if (next == null || next == flight.shownStage) return@collect
                val to = settledBounds(transport, agentId, next) ?: return@collect
                flight.shownStage = next
                if (reducedMotion) {
                    flight.rect.snapTo(to)
                    flight.presence.snapTo(1f)
                    return@collect
                }
                flight.flying = true
                try {
                    coroutineScope {
                        launch { flight.rect.animateTo(to, tween(TRANSPORT_MILLIS, easing = FastOutSlowInEasing)) }
                        flight.presence.animateTo(0f, tween(TRANSPORT_LEAVE_MILLIS))
                        flight.presence.animateTo(1f, tween(TRANSPORT_MILLIS - TRANSPORT_LEAVE_MILLIS, easing = FastOutSlowInEasing))
                    }
                } finally {
                    flight.flying = false
                }
            }
    }
    // Between hops the character simply follows its seat (window resize, sidebar width).
    LaunchedEffect(flight, seat.bounds, stage) {
        if (!flight.flying && stage == flight.shownStage) flight.rect.snapTo(seat.bounds)
    }
    // The renderer's node keeps the DESTINATION size for the whole flight and the change of size
    // is a layer scale: a Rive surface re-allocates its readback buffer and restarts its frame
    // loop on every size change, so resizing it per frame is what made a hop stutter.
    val rect = flight.rect.value
    val density = LocalDensity.current
    val boxSize = with(density) { seat.bounds.width.toDp() }
    val flightScale = if (seat.bounds.width > 0f) rect.width / seat.bounds.width else 1f
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
            },
        contentAlignment = Alignment.Center,
    ) {
        MascotLive(agentId, identity, size = boxSize * seat.overscale, onClick = seat.onClick)
    }
}

/**
 * The seat's bounds once they have held still for two frames, or null when the seat left before it
 * settled (the stage stream will name the next one).
 */
private suspend fun settledBounds(transport: MascotTransport, agentId: String, stage: MascotStage): Rect? {
    var last: Rect? = null
    var stillFrames = 0
    while (stillFrames < SETTLE_FRAMES) {
        val now = transport.seat(agentId, stage)?.bounds ?: return null
        stillFrames = if (now == last) stillFrames + 1 else 0
        last = now
        withFrameNanos { }
    }
    return last
}

/** One transport: the move takes [TRANSPORT_MILLIS]; the character is gone by [TRANSPORT_LEAVE_MILLIS] and back by the end. */
private const val TRANSPORT_MILLIS = 360
private const val TRANSPORT_LEAVE_MILLIS = 140
private const val TRANSPORT_MIN_SCALE = 0.6f
private const val SETTLE_FRAMES = 2
