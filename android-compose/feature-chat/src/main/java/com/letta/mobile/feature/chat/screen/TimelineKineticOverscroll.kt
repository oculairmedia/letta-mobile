package com.letta.mobile.feature.chat.screen

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.foundation.OverscrollEffect
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import ir.farsroidx.overscroll.ElasticOverscrollEffect
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlin.math.abs
import kotlin.math.sqrt

private const val TIMELINE_OVERSCROLL_RATIO = 0.08f
private const val TIMELINE_SPRING_DAMPING = 0.82f
private const val TIMELINE_SPRING_STIFFNESS = 850f

/**
 * Keeps normal drag/fling consumption with the LazyColumn and only animates the library's render
 * node when a fling leaves velocity at a genuinely exhausted timeline edge.
 */
internal class TimelineKineticOverscroll(
    internal val renderEffect: ElasticOverscrollEffect,
    enabled: Boolean,
    canFlingPastPositiveEdge: () -> Boolean,
    canFlingPastNegativeEdge: () -> Boolean,
) : OverscrollEffect {
    private var enabled = enabled
    private var canFlingPastPositiveEdge = canFlingPastPositiveEdge
    private var canFlingPastNegativeEdge = canFlingPastNegativeEdge
    private var animationEpoch = 0L
    private var animationJob: Job? = null

    override val node: DelegatableNode
        get() = renderEffect.node

    override val isInProgress: Boolean
        get() = renderEffect.mOverscrollValue != 0f

    fun update(
        enabled: Boolean,
        canFlingPastPositiveEdge: () -> Boolean,
        canFlingPastNegativeEdge: () -> Boolean,
    ) {
        val activeEdgeBecameUnavailable =
            (renderEffect.mOverscrollValue > 0f && !canFlingPastPositiveEdge()) ||
                (renderEffect.mOverscrollValue < 0f && !canFlingPastNegativeEdge())
        this.canFlingPastPositiveEdge = canFlingPastPositiveEdge
        this.canFlingPastNegativeEdge = canFlingPastNegativeEdge
        if (this.enabled != enabled || activeEdgeBecameUnavailable) {
            this.enabled = enabled
            cancelAndClear()
        } else if (!enabled) {
            clearOffset()
        }
    }

    fun cancelAndClear() {
        animationEpoch++
        animationJob?.cancel()
        animationJob = null
        clearOffset()
    }

    override fun applyToScroll(
        delta: Offset,
        source: NestedScrollSource,
        performScroll: (Offset) -> Offset,
    ): Offset {
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
        clearOffset()

        try {
            val consumed = performFling(velocity)
            if (!enabled || epoch != animationEpoch) return

            val initial = velocity.y
            val consumedY = consumed.y
            if (!initial.isFinite() || !consumedY.isFinite()) return
            val residual = initial - consumedY
            val edgeIsAvailable = when {
                residual > 0f -> canFlingPastPositiveEdge()
                residual < 0f -> canFlingPastNegativeEdge()
                else -> false
            }
            if (!edgeIsAvailable) return

            // Limiting velocity before the spring avoids an unbounded state and a clipped plateau.
            val maximumVelocity = renderEffect.maxOverscroll * sqrt(TIMELINE_SPRING_STIFFNESS) * 0.72f
            val retainedVelocity = residual.coerceIn(-maximumVelocity, maximumVelocity)
            if (abs(retainedVelocity) < 1f) return

            animate(
                initialValue = 0f,
                targetValue = 0f,
                initialVelocity = retainedVelocity,
                animationSpec = spring(
                    dampingRatio = TIMELINE_SPRING_DAMPING,
                    stiffness = TIMELINE_SPRING_STIFFNESS,
                    visibilityThreshold = 0.1f,
                ),
            ) { value, _ ->
                if (enabled && epoch == animationEpoch) {
                    renderEffect.mOverscrollValue = value.coerceIn(
                        -renderEffect.maxOverscroll,
                        renderEffect.maxOverscroll,
                    )
                }
            }
        } finally {
            if (epoch == animationEpoch) {
                animationJob = null
                clearOffset()
            }
        }
    }

    private fun clearOffset() {
        renderEffect.mOverscrollValue = 0f
    }
}

@Composable
internal fun rememberTimelineKineticOverscroll(
    enabled: Boolean,
    canFlingPastPositiveEdge: () -> Boolean,
    canFlingPastNegativeEdge: () -> Boolean,
): TimelineKineticOverscroll {
    val density = LocalDensity.current
    val configuration = LocalConfiguration.current
    val maxOverscroll = with(density) {
        (configuration.screenHeightDp.dp.toPx() * TIMELINE_OVERSCROLL_RATIO).coerceAtLeast(1f)
    }
    val renderEffect = remember {
        ElasticOverscrollEffect(
            springDampingRatio = TIMELINE_SPRING_DAMPING,
            springStiffness = TIMELINE_SPRING_STIFFNESS,
            maxStretchRatio = (TIMELINE_OVERSCROLL_RATIO * 100).toInt(),
            maxOverscroll = maxOverscroll,
            orientation = Orientation.Vertical,
        )
    }
    val effect = remember(renderEffect) {
        TimelineKineticOverscroll(
            renderEffect = renderEffect,
            enabled = enabled,
            canFlingPastPositiveEdge = canFlingPastPositiveEdge,
            canFlingPastNegativeEdge = canFlingPastNegativeEdge,
        )
    }
    SideEffect {
        renderEffect.maxOverscroll = maxOverscroll
        effect.update(enabled, canFlingPastPositiveEdge, canFlingPastNegativeEdge)
    }
    return effect
}

internal fun shouldShowNewestAffordance(
    isAnchoredAwayFromTail: Boolean,
    canScrollTowardNewest: Boolean,
): Boolean = isAnchoredAwayFromTail || canScrollTowardNewest
