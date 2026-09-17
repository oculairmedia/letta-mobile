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

/** Whether a seat's agent has a mascot to draw, in which look, and whether it stands at this seat right now. */
private class SeatOccupancy(val shown: MascotIdentity?, val available: Boolean, val standsHere: Boolean)

@Composable
private fun seatOccupancy(agentId: String?, stage: MascotStage, identity: MascotIdentity?): SeatOccupancy {
    val transport = LocalMascotTransport.current
    val registry = LocalMascotRegistry.current
    val host = LocalMascotHost.current
    val shown = identity ?: agentId?.let { transport.previewOf(it) ?: registry.identities[it] }
    val available = agentId != null && shown != null && host.available && host.entry(agentId, shown) != null
    val standsHere = available && transport.layerMounted && transport.activeStage(agentId!!) == stage
    return SeatOccupancy(shown, available, standsHere)
}

/** Why a seat draws [MascotSeat]'s `empty` content instead of the character. */
enum class MascotSeatVacancy {
    /** The agent has no mascot the host can draw here: show a stand-in (an orb, a sphere). */
    NO_MASCOT,
    /** The character exists but stands at another seat right now: leave the seat bare. */
    SEATED_ELSEWHERE,
}

/**
 * Reserves [size] for [agentId]'s mascot at [stage] and tells the transport where that is. While
 * the mascot stands here the layer draws it over this box (at [size] times [overscale], the way
 * [MascotAvatar] overscales a tile); while it stands elsewhere, or the agent has no mascot,
 * [empty] draws with the [MascotSeatVacancy] that says which - the seat the character has left,
 * or a plain orb for an agent without one. [identity] overrides the registry's for this seat (the
 * editor previews an unsaved pick this way and the live mascot morphs into it).
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
    empty: @Composable (MascotSeatVacancy) -> Unit,
) {
    val transport = LocalMascotTransport.current
    val occupancy = seatOccupancy(agentId, stage, identity)
    val key = agentId?.let { MascotTransport.SeatKey(it, stage) }
    val handlers = remember { SeatHandlers() }
    handlers.onClick = onClick
    handlers.onEdit = onEdit
    DisposableEffect(transport, key) {
        onDispose { key?.let { transport.seats.remove(it) } }
    }
    // The seat is published from composition, not only from layout: a new identity or overscale
    // with the same bounds must reach the layer too, and layout alone would never report it.
    var bounds by remember { mutableStateOf<Rect?>(null) }
    val seatKey = key?.takeIf { occupancy.available }
    LaunchedEffect(transport, seatKey, bounds, overscale, identity) {
        transport.publishSeat(seatKey, bounds) { MascotSeatInfo(it, overscale, identity, handlers) }
    }
    Box(
        modifier = modifier.requiredSize(size).onGloballyPositioned { bounds = it.boundsInWindow() },
        contentAlignment = Alignment.Center,
    ) {
        when {
            !occupancy.available -> empty(MascotSeatVacancy.NO_MASCOT)
            !transport.layerMounted -> MascotLive(agentId!!, occupancy.shown!!, size = size * overscale, onClick = onClick)
            !occupancy.standsHere -> empty(MascotSeatVacancy.SEATED_ELSEWHERE)
        }
    }
}
