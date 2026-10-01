package com.letta.mobile.ui.chat.surface.composer

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.lerp
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import com.letta.mobile.ui.mascot.MascotSeat
import com.letta.mobile.ui.mascot.MascotStage
import com.letta.mobile.ui.mascot.mascotAvailable
import com.letta.mobile.ui.theme.ChatMascotDimens
import com.letta.mobile.ui.theme.ChatMotionTokens
import com.letta.mobile.ui.theme.LocalReducedMotion
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filter

/*
 * letta-mobile-bglj6.1: the chat page declares the agent's [MascotStage.COMPOSER_COMPANION] seat
 * exactly ONCE, and moves it.
 *
 * The transport keys seats by agent and stage, so two seats for the same stage overwrite each
 * other, and the first to leave removes the key while the other still stands there. When the
 * docked panel grew into the page (or folded down to the minimised dock) the composer, and the
 * seat inside it, was disposed and composed again: the transport lost the seat for a frame,
 * dropped the character and brought its scene up again - a visible blink - and the companion
 * slot closed and reopened under it.
 *
 * Now the places the companion can stand (the open panel's badge, the minimised dock's spot, the
 * page composer's slot) only report where they are, and one seat drawn over the page follows
 * them: between the docked and the page layer by the morph's progress, and from one docked spot
 * to the other with a short glide when the dock folds.
 */

/** Which layer of the page an anchor belongs to. */
internal enum class CompanionLayer { Docked, Page }

/** Where the companion may stand in each layer, in window coordinates. */
@Stable
internal class CompanionSeatAnchors {
    /** One reporter's spot; [owner] tells two reporters of the same layer apart. */
    internal data class Anchor(val owner: Any, val rect: Rect)

    var docked: Anchor? by mutableStateOf(null)
        private set
    var page: Anchor? by mutableStateOf(null)
        private set

    /**
     * How much of the companion shows at the page's spot, 0..1 (letta-mobile-bglj6.1.9): the Touch
     * page raises it above the bar only while the agent works, as the legacy Android composer did.
     */
    var pageShown: Float by mutableFloatStateOf(1f)

    /** Some spot has been reported: from then on the seat stays declared. */
    var anchored: Boolean by mutableStateOf(false)
        private set

    fun report(layer: CompanionLayer, owner: Any, rect: Rect) {
        val next = Anchor(owner, rect)
        when (layer) {
            CompanionLayer.Docked -> if (docked != next) docked = next
            CompanionLayer.Page -> if (page != next) page = next
        }
        if (!anchored) anchored = true
    }

    fun clear(layer: CompanionLayer, owner: Any) {
        when (layer) {
            CompanionLayer.Docked -> if (docked?.owner === owner) docked = null
            CompanionLayer.Page -> if (page?.owner === owner) page = null
        }
    }
}

/** The page's anchors; null outside a chat page, where each composer declares its own seat. */
internal val LocalCompanionSeatAnchors = staticCompositionLocalOf<CompanionSeatAnchors?> { null }

/** The layer a composer is drawn in. */
internal val LocalCompanionLayer = staticCompositionLocalOf { CompanionLayer.Docked }

/**
 * Reserves the companion's box here, [size] square, and reports it to [anchors]. Draws nothing:
 * the page's one seat ([CompanionSeatOverlay]) stands over it, scaled to [size] (the docked
 * panel's badge seats it smaller than the composer does).
 */
@Composable
internal fun CompanionSeatAnchor(
    anchors: CompanionSeatAnchors,
    modifier: Modifier = Modifier,
    size: Dp = ChatMascotDimens.composerCompanion,
) {
    val layer = LocalCompanionLayer.current
    val owner = remember { Any() }
    DisposableEffect(anchors, layer, owner) {
        onDispose { anchors.clear(layer, owner) }
    }
    Box(
        modifier
            .requiredSize(size)
            .onGloballyPositioned { anchors.report(layer, owner, it.boundsInWindow()) },
    )
}

/**
 * The page's one [MascotStage.COMPOSER_COMPANION] seat, laid over the whole page. It stands at
 * the docked anchor, the page anchor, or between them by [pageWeight] (0 docked .. 1 page) while
 * both are on screen. It is read at placement time, so following the morph relayouts without
 * recomposing.
 */
@Composable
internal fun CompanionSeatOverlay(
    anchors: CompanionSeatAnchors,
    agentId: String?,
    pageWeight: () -> Float,
    onClick: (() -> Unit)?,
    onEdit: (() -> Unit)?,
    modifier: Modifier = Modifier,
    /** Moves the docked chat when the character is grabbed while seated on it (not on the page). */
    onDockDrag: ((dxDp: Float, dyDp: Float) -> Unit)? = null,
) {
    if (agentId == null || !mascotAvailable(agentId) || !anchors.anchored) return
    val glide = rememberDockedGlide()
    val animate = !LocalReducedMotion.current
    val placement = remember(anchors, glide) { SeatPlacement(anchors, glide) }
    placement.animate = animate
    Box(
        modifier.fillMaxSize().onGloballyPositioned {
            val origin = it.positionInWindow()
            if (placement.origin != origin) placement.origin = origin
        },
    ) {
        MascotSeat(
            agentId = agentId,
            stage = MascotStage.COMPOSER_COMPANION,
            size = ChatMascotDimens.composerCompanion,
            modifier = Modifier.layout { measurable, _ ->
                val placeable = measurable.measure(Constraints())
                layout(placeable.width, placeable.height) {
                    val rect = placement.rect(pageWeight())
                    // The seat keeps its one size and is scaled to the anchor's (the badge's is
                    // smaller): the renderer is never resized while the seat glides between them.
                    val scale = if (placeable.width > 0 && rect.width > 0f) rect.width / placeable.width else 1f
                    // Where the page hides its companion (Touch, at rest) it fades and sinks away.
                    // Gone, it is still placed, at no size: an unplaced seat would leave its last
                    // bounds published (whole, under reduced motion), and the mascot layer would go
                    // on drawing it there and taking its taps over the bar.
                    val shown = (1f - pageWeight().coerceIn(0f, 1f) * (1f - anchors.pageShown)).coerceAtLeast(0f)
                    placeable.placeWithLayer(
                        (rect.center.x - placeable.width / 2f - placement.origin.x).roundToInt(),
                        (rect.center.y - placeable.height / 2f - placement.origin.y).roundToInt(),
                    ) {
                        scaleX = scale * (HIDDEN_SCALE + (1f - HIDDEN_SCALE) * shown)
                        scaleY = scale * (HIDDEN_SCALE + (1f - HIDDEN_SCALE) * shown)
                        alpha = shown
                    }
                }
            },
            onClick = onClick,
            onEdit = onEdit,
            onDrag = onDockDrag?.let { drag -> { dx: Float, dy: Float -> if (pageWeight() <= 0f) drag(dx, dy) } },
            empty = {},
        )
    }
}

/**
 * A hidden companion shrinks away into the bar (the mascot layer draws the character at the seat's
 * bounds, so the size is what reaches it; legacy faded and scaled it out).
 */
private const val HIDDEN_SCALE = 0f

/** Resolves the seat's rect from the anchors; keeps the last one for the frames between anchors. */
@Stable
private class SeatPlacement(private val anchors: CompanionSeatAnchors, private val glide: DockedGlide) {
    /** The overlay's top-left in the window; anchors are reported in window coordinates. */
    var origin: Offset by mutableStateOf(Offset.Zero)
    var animate: Boolean = true
    private var last: Rect? = null

    fun rect(pageWeight: Float): Rect {
        val docked = anchors.docked?.let { glide.shown(it, last, animate) }
        val page = anchors.page?.rect
        val next = when {
            docked != null && page != null -> lerp(docked, page, pageWeight.coerceIn(0f, 1f))
            else -> docked ?: page ?: last ?: Rect.Zero
        }
        last = next
        return next
    }
}

/**
 * When the docked anchor changes hands (the open panel's badge, or the minimised dock's place above the
 * bar), the seat glides from where it stood instead of jumping. It holds still from the moment
 * the change is seen until the glide starts, so no frame shows it at the far end.
 */
@Stable
private class DockedGlide {
    val progress = Animatable(1f)
    var from: Rect = Rect.Zero

    /** A glide is due; [progress] has not been reset for it yet. */
    var pending: Boolean = false

    /** Bumped for every change of hands; the effect starts a glide for each. */
    var handoffs: Int by mutableIntStateOf(0)
    private var owner: Any? = null

    fun shown(anchor: CompanionSeatAnchors.Anchor, last: Rect?, animate: Boolean): Rect {
        val previous = owner
        owner = anchor.owner
        if (previous != null && previous !== anchor.owner && last != null && animate) {
            from = last
            pending = true
            handoffs++
        }
        if (pending) return from
        val t = progress.value
        return if (t >= 1f) anchor.rect else lerp(from, anchor.rect, t)
    }
}

@Composable
private fun rememberDockedGlide(): DockedGlide {
    val glide = remember { DockedGlide() }
    LaunchedEffect(glide) {
        snapshotFlow { glide.handoffs }.filter { it > 0 }.collectLatest {
            glide.progress.snapTo(0f)
            glide.pending = false
            glide.progress.animateTo(
                1f,
                tween(ChatMotionTokens.DockCollapse.MILLIS, easing = ChatMotionTokens.SurfaceMorph.easing),
            )
        }
    }
    return glide
}
