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
import androidx.compose.runtime.mutableFloatStateOf
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

/** An agent's character as the host can draw it: who, and in which look. */
private class DrawableMascot(val agentId: String, val identity: MascotIdentity)

/**
 * The one rule for whether [agentId]'s character can be drawn and in which look: [identity] when a
 * seat overrides it, else the editor's preview, else the registry's; null when the agent has none or
 * the host cannot bring up its scene.
 */
@Composable
private fun drawableMascot(agentId: String?, identity: MascotIdentity? = null): DrawableMascot? {
    agentId ?: return null
    val shown = identity ?: LocalMascotTransport.current.previewOf(agentId) ?: LocalMascotRegistry.current.identities[agentId]
    val host = LocalMascotHost.current
    return shown?.takeIf { host.available && host.entry(agentId, it) != null }?.let { DrawableMascot(agentId, it) }
}

/** Where an agent's character is relative to one seat; resolved once, then dispatched on. */
private sealed interface SeatPresence {
    /** Drawn at the seat or on its way there. */
    val drawn: Boolean

    /** No character the host can draw: the seat shows a stand-in. */
    data object NoMascot : SeatPresence {
        override val drawn = false
    }

    /** No transport layer: the seat draws the character itself. */
    class InPlace(val mascot: DrawableMascot) : SeatPresence {
        override val drawn = true
    }

    /** The layer draws it standing over this seat. */
    data object Here : SeatPresence {
        override val drawn = true
    }

    /** The layer has it at no seat yet: it is about to stand here. */
    data object Arriving : SeatPresence {
        override val drawn = true
    }

    /** Another seat holds it (the agent pane's hero). */
    data object Elsewhere : SeatPresence {
        override val drawn = false
    }
}

@Composable
private fun seatPresence(agentId: String?, stage: MascotStage, identity: MascotIdentity? = null): SeatPresence {
    val mascot = drawableMascot(agentId, identity) ?: return SeatPresence.NoMascot
    val transport = LocalMascotTransport.current
    if (!transport.layerMounted) return SeatPresence.InPlace(mascot)
    return when (transport.activeStage(mascot.agentId)) {
        stage -> SeatPresence.Here
        null -> SeatPresence.Arriving
        else -> SeatPresence.Elsewhere
    }
}

/**
 * Whether [agentId]'s character is drawn at [stage]'s seat (or is on its way there): the host can
 * draw it, and either there is no transport layer (each seat draws its own) or the layer has it
 * standing at [stage], or at no seat yet. False while another seat holds it (the agent pane's
 * hero) or the host cannot bring up its scene: a place that must never stand empty draws a
 * stand-in then (letta-mobile-8kpk7).
 */
@Composable
fun mascotDrawnAt(agentId: String?, stage: MascotStage): Boolean = seatPresence(agentId, stage).drawn

/**
 * [agentId]'s character, live, at a place that must show it while the transported one stands at
 * another seat (the minimised dock while the desktop sidebar's hero holds it; letta-mobile-ckmub.15).
 * It is the same character the seat draws - the editor's preview first, then the registry's look,
 * fed the same presence - so the two move together; [fallback] draws when the host cannot bring up
 * the agent's scene.
 */
@Composable
fun MascotMirror(
    agentId: String?,
    size: Dp,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    fallback: @Composable () -> Unit,
) {
    val mascot = drawableMascot(agentId) ?: return fallback()
    Box(modifier.size(size), contentAlignment = Alignment.Center) {
        MascotInPlace(mascot.agentId, mascot.identity, size = size, live = true, onClick = onClick)
    }
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
    /** A drag on the character, in dp (see [SeatHandlers.onDrag]). */
    onDrag: ((dxDp: Float, dyDp: Float) -> Unit)? = null,
    /** Whether a drag starting now goes to [onDrag]; one it turns down is left to what is under it. */
    dragEnabled: () -> Boolean = { true },
    /**
     * Without a transport layer, whether the seat draws the live scene or the agent's still
     * ([MascotInPlace]); a seat that pictures an agent at rest, as the agent panel's hero does,
     * passes [mascotAtWork] so it shows what every avatar of the agent shows. A layer draws live.
     */
    live: Boolean = true,
    empty: @Composable (MascotSeatVacancy) -> Unit,
) {
    val transport = LocalMascotTransport.current
    val presence = seatPresence(agentId, stage, identity)
    val key = agentId?.let { MascotTransport.SeatKey(it, stage) }
    val handlers = remember { SeatHandlers() }
    handlers.onClick = onClick
    handlers.onEdit = onEdit
    handlers.onDrag = onDrag
    handlers.dragEnabled = dragEnabled
    DisposableEffect(transport, key) {
        onDispose { key?.let { transport.seats.remove(it) } }
    }
    // The seat is published from composition, not only from layout: a new identity or overscale
    // with the same bounds must reach the layer too, and layout alone would never report it.
    var bounds by remember { mutableStateOf<Rect?>(null) }
    // The box's own width: a layer scale outside it shows in [bounds] but not here.
    var layoutWidth by remember { mutableFloatStateOf(0f) }
    val seatKey = key?.takeIf { presence != SeatPresence.NoMascot }
    LaunchedEffect(transport, seatKey, bounds, layoutWidth, overscale, identity) {
        transport.publishSeat(seatKey, bounds) { MascotSeatInfo(it, overscale, identity, handlers, layoutWidth) }
    }
    Box(
        modifier = modifier.requiredSize(size).onGloballyPositioned {
            bounds = it.boundsInWindow()
            layoutWidth = it.size.width.toFloat()
        },
        contentAlignment = Alignment.Center,
    ) {
        when (presence) {
            SeatPresence.NoMascot -> empty(MascotSeatVacancy.NO_MASCOT)
            is SeatPresence.InPlace -> MascotInPlace(
                presence.mascot.agentId,
                presence.mascot.identity,
                size = size * overscale,
                live = live,
                onClick = onClick,
                // A seat that takes input at all lets the character be poked and petted (te3n1.4).
                interactive = onClick != null || onEdit != null,
            )
            SeatPresence.Here -> Unit
            SeatPresence.Arriving, SeatPresence.Elsewhere -> empty(MascotSeatVacancy.SEATED_ELSEWHERE)
        }
    }
}
