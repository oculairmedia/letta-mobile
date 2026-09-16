package com.letta.mobile.ui.mascot

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateRectAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import com.letta.mobile.avatar.core.MascotIdentity
import kotlin.math.roundToInt

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
                TransportedMascot(agentId, identity, seat, origin, reducedMotion)
            }
        }
    }
}

@Composable
private fun TransportedMascot(
    agentId: String,
    identity: MascotIdentity,
    seat: MascotSeatInfo,
    origin: Offset,
    reducedMotion: Boolean,
) {
    // The seat's rect is the animated value: position and size together, so the character slides
    // and grows in one motion rather than jumping then scaling.
    val rect by animateRectAsState(
        targetValue = seat.bounds,
        animationSpec = if (reducedMotion) snap() else tween(TRANSPORT_MILLIS, easing = FastOutSlowInEasing),
        label = "mascotTransport",
    )
    val density = LocalDensity.current
    val boxSize = with(density) { rect.width.toDp() }
    Box(
        modifier = Modifier
            .offset { IntOffset((rect.left - origin.x).roundToInt(), (rect.top - origin.y).roundToInt()) }
            .requiredSize(boxSize),
        contentAlignment = Alignment.Center,
    ) {
        MascotLive(agentId, identity, size = boxSize * seat.overscale, onClick = seat.onClick)
    }
}

/** One transport takes this long: quick enough to feel like the agent moving, slow enough to be followed. */
private const val TRANSPORT_MILLIS = 360
