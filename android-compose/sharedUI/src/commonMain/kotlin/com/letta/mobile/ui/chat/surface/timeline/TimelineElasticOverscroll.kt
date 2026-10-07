package com.letta.mobile.ui.chat.surface.timeline

import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.foundation.OverscrollEffect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.ui.node.LayoutModifierNode
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Velocity
import com.letta.mobile.ui.theme.LocalReducedMotion
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * letta-mobile-bglj6.1.20: the legacy timeline's elastic bounce (feature-chat
 * TimelineKineticOverscroll), in common Compose. The list keeps every drag and fling it can
 * consume; only velocity a fling leaves at a genuinely exhausted edge rubber-bands the rows, by at
 * most [RUBBER_BAND_RATIO] of the list's height, on one spring that settles exactly back at rest.
 *
 * Handed to the LazyColumn as its overscroll effect it replaces the platform's (Android's stretch),
 * so the two never bounce together. Disabled (reduced motion, a pinch) it is a pass-through: no
 * bounce and no platform stretch either.
 */
@Stable
internal class TimelineElasticOverscroll(
    enabled: Boolean = true,
    canBouncePastPositiveEdge: () -> Boolean = { true },
    canBouncePastNegativeEdge: () -> Boolean = { true },
) : OverscrollEffect {
    private var enabled = enabled
    private var canBouncePastPositiveEdge = canBouncePastPositiveEdge
    private var canBouncePastNegativeEdge = canBouncePastNegativeEdge
    private var animationEpoch = 0L
    private var animationJob: Job? = null

    /** The rows' current displacement, in px along the scroll axis (positive is down). */
    var offsetPx by mutableFloatStateOf(0f)
        private set

    /** The furthest the rows may travel: [RUBBER_BAND_RATIO] of the last measured list height. */
    var maxOffsetPx: Float = 0f
        internal set

    override val node: DelegatableNode = ElasticOffsetNode(this)

    override val isInProgress: Boolean
        get() = offsetPx != 0f

    fun update(
        enabled: Boolean,
        canBouncePastPositiveEdge: () -> Boolean,
        canBouncePastNegativeEdge: () -> Boolean,
    ) {
        val activeEdgeBecameUnavailable =
            (offsetPx > 0f && !canBouncePastPositiveEdge()) || (offsetPx < 0f && !canBouncePastNegativeEdge())
        this.canBouncePastPositiveEdge = canBouncePastPositiveEdge
        this.canBouncePastNegativeEdge = canBouncePastNegativeEdge
        if (this.enabled != enabled || activeEdgeBecameUnavailable) {
            this.enabled = enabled
            cancelAndClear()
        }
    }

    fun cancelAndClear() {
        animationEpoch++
        animationJob?.cancel()
        animationJob = null
        offsetPx = 0f
    }

    override fun applyToScroll(
        delta: Offset,
        source: NestedScrollSource,
        performScroll: (Offset) -> Offset,
    ): Offset {
        // A finger back on the list takes over from any bounce still settling.
        if (source == NestedScrollSource.UserInput) cancelAndClear()
        return performScroll(delta)
    }

    override suspend fun applyToFling(
        velocity: Velocity,
        performFling: suspend (Velocity) -> Velocity,
    ) {
        val epoch = ++animationEpoch
        animationJob?.cancel()
        animationJob = currentCoroutineContext()[Job]
        offsetPx = 0f
        try {
            val consumed = performFling(velocity)
            if (!enabled || epoch != animationEpoch) return
            val bounce = elasticBounceVelocity(
                fling = ElasticFling(velocity, consumed, maxOffsetPx),
                canBouncePast = { towardPositive -> if (towardPositive) canBouncePastPositiveEdge() else canBouncePastNegativeEdge() },
            )
            if (bounce == 0f) return
            animate(
                initialValue = 0f,
                targetValue = 0f,
                initialVelocity = bounce,
                animationSpec = spring(
                    dampingRatio = SPRING_DAMPING_RATIO,
                    stiffness = SPRING_STIFFNESS,
                    visibilityThreshold = VISIBILITY_THRESHOLD_PX,
                ),
            ) { value, _ ->
                if (enabled && epoch == animationEpoch) offsetPx = value.coerceIn(-maxOffsetPx, maxOffsetPx)
            }
        } finally {
            if (epoch == animationEpoch) {
                animationJob = null
                offsetPx = 0f
            }
        }
    }

    companion object {
        /** Legacy TIMELINE_OVERSCROLL_RATIO: the band stretches to 8% of the list's height. */
        const val RUBBER_BAND_RATIO: Float = 0.08f
        const val SPRING_DAMPING_RATIO: Float = 0.82f
        const val SPRING_STIFFNESS: Float = 850f

        /** Caps the launch so the spring's peak stays inside the band instead of plateauing on it. */
        internal const val VELOCITY_CAP_FACTOR: Float = 0.72f
        private const val VISIBILITY_THRESHOLD_PX: Float = 0.1f
        private const val MIN_BOUNCE_VELOCITY: Float = 1f
    }
}

/** A fling's launch velocity, what the list consumed of it, and the band it may stretch into. */
internal class ElasticFling(val initial: Velocity, val consumed: Velocity, val maxOffsetPx: Float) {
    fun isBounceable(): Boolean = initial.y.isFinite() && consumed.y.isFinite() && maxOffsetPx > 0f
}

/**
 * The velocity the bounce launches with, or 0 for none: the fling's residual past what the list
 * consumed, only toward an edge that is truly the end (not a page boundary), capped so the spring
 * peaks inside [maxOffsetPx].
 */
internal fun elasticBounceVelocity(
    fling: ElasticFling,
    canBouncePast: (towardPositive: Boolean) -> Boolean,
): Float {
    if (!fling.isBounceable()) return 0f
    val residual = fling.initial.y - fling.consumed.y
    val maxOffsetPx = fling.maxOffsetPx
    val edgeIsAvailable = when {
        residual > 0f -> canBouncePast(true)
        residual < 0f -> canBouncePast(false)
        else -> false
    }
    if (!edgeIsAvailable) return 0f
    val cap = maxOffsetPx * sqrt(TimelineElasticOverscroll.SPRING_STIFFNESS) * TimelineElasticOverscroll.VELOCITY_CAP_FACTOR
    val retained = residual.coerceIn(-cap, cap)
    return if (abs(retained) < 1f) 0f else retained
}

/** Draws the rows at the effect's offset: a layer translation, so a bounce frame only redraws. */
private class ElasticOffsetNode(private val effect: TimelineElasticOverscroll) : Modifier.Node(), LayoutModifierNode {
    override fun MeasureScope.measure(measurable: Measurable, constraints: Constraints): MeasureResult {
        val placeable = measurable.measure(constraints)
        effect.maxOffsetPx = (placeable.height * TimelineElasticOverscroll.RUBBER_BAND_RATIO).coerceAtLeast(1f)
        return layout(placeable.width, placeable.height) {
            placeable.placeWithLayer(0, 0) { translationY = effect.offsetPx }
        }
    }
}

/**
 * The timeline's elastic overscroll, standing down under reduced motion and while [pinching].
 * [canBouncePastPositiveEdge] / [canBouncePastNegativeEdge] say whether a fling toward that
 * physical edge (positive: the finger moving down, toward the oldest rows of a reversed list) has
 * reached the true end of the history rather than a page still to load.
 */
@Composable
internal fun rememberTimelineElasticOverscroll(
    pinching: Boolean,
    canBouncePastPositiveEdge: () -> Boolean,
    canBouncePastNegativeEdge: () -> Boolean,
): TimelineElasticOverscroll {
    val enabled = timelineElasticOverscrollEnabled(reducedMotion = LocalReducedMotion.current, pinching = pinching)
    val effect = remember { TimelineElasticOverscroll(enabled, canBouncePastPositiveEdge, canBouncePastNegativeEdge) }
    SideEffect { effect.update(enabled, canBouncePastPositiveEdge, canBouncePastNegativeEdge) }
    DisposableEffect(effect) { onDispose(effect::cancelAndClear) }
    return effect
}

internal fun timelineElasticOverscrollEnabled(reducedMotion: Boolean, pinching: Boolean): Boolean =
    !reducedMotion && !pinching
