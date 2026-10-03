package com.letta.mobile.ui.chat.surface.timeline

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.gestures.ScrollScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import com.letta.mobile.sharedui.resources.Res
import com.letta.mobile.sharedui.resources.timeline_scroll_to_latest
import com.letta.mobile.ui.icons.LettaIcons
import com.letta.mobile.ui.theme.ChatMotionTokens
import com.letta.mobile.ui.theme.ChatTimelineDimens
import com.letta.mobile.ui.theme.LocalReducedMotion
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.sqrt
import org.jetbrains.compose.resources.stringResource

/**
 * letta-mobile-bglj6.1: the scroll-to-latest glide both timelines run when the button is tapped.
 *
 * A tap far up the history first snaps to about one viewport from the newest edge, so the glide
 * never streams every row in between. One underdamped spring then carries the list the rest of
 * the way: it eases into the edge, runs a little past it and settles back. The run past the edge
 * is a lift of the rows ([overshootPx], drawn by the frame's graphics layer), never a scroll past
 * the end, so it leaves the platform's own overscroll effect alone. Under reduced motion the tap
 * jumps straight to the edge.
 */
@Stable
internal class NewestEdgeGlide(
    private val listState: LazyListState,
    private val reducedMotion: Boolean,
    private val maxOvershootPx: Float,
) {
    /** The rows' lift while the glide runs past the newest edge (px; negative lifts them up). */
    var overshootPx: Float by mutableFloatStateOf(0f)
        private set

    /** From the tap until the list rests on the newest edge; the button stands down meanwhile. */
    var isGliding: Boolean by mutableStateOf(false)
        private set

    suspend fun toNewest() {
        if (reducedMotion) {
            listState.scrollToItem(0)
            return
        }
        isGliding = true
        try {
            listState.snapWithinGlideReach()
            listState.scroll { springToNewest() }
            // A row that grew under the glide still lands the reader exactly on the edge.
            if (!listState.isAtNewestEdge()) listState.scrollToItem(0)
        } finally {
            overshootPx = 0f
            isGliding = false
        }
    }

    /**
     * Drives the remaining distance as a fraction, 1 to 0, so the rows below the viewport (only
     * estimated until they lay out) are re-measured every frame and the landing is exact.
     */
    private suspend fun ScrollScope.springToNewest() {
        val start = listState.distanceToNewestPx()
        if (start <= 0f) return
        // The lift is sized as if the glide were no longer than the overshoot cap allows.
        val liftReach = min(start, maxOvershootPx / SPRING_PEAK_FRACTION)
        var previous = 1f
        var landed = false
        val spec = spring<Float>(ChatMotionTokens.ScrollToLatest.DAMPING_RATIO, ChatMotionTokens.ScrollToLatest.STIFFNESS)
        animate(initialValue = 1f, targetValue = 0f, animationSpec = spec) { fraction, _ ->
            if (!landed && fraction > 0f) {
                val remaining = listState.distanceToNewestPx()
                scrollBy(remaining * (fraction / previous) - remaining)
            } else {
                if (!landed) scrollBy(-listState.distanceToNewestPx())
                landed = true
                overshootPx = fraction * liftReach
            }
            previous = fraction
        }
    }

    /** A tap from far up snaps to within one glide of the edge first. */
    private suspend fun LazyListState.snapWithinGlideReach() {
        val reach = layoutInfo.viewportSize.height * ChatMotionTokens.ScrollToLatest.GLIDE_VIEWPORTS
        if (reach <= 0f || distanceToNewestPx() <= reach) return
        val row = averageVisibleRowPx()
        if (firstVisibleItemIndex == 0 || row <= 0f) {
            scrollToItem(0, reach.toInt())
        } else {
            scrollToItem(ceil(reach / row).toInt().coerceIn(1, firstVisibleItemIndex))
        }
    }
}

/** The page's glide for this list, under the composition's reduced-motion setting. */
@Composable
internal fun rememberNewestEdgeGlide(listState: LazyListState): NewestEdgeGlide {
    val reducedMotion = LocalReducedMotion.current
    val maxOvershootPx = with(LocalDensity.current) { ChatTimelineDimens.scrollToLatestMaxOvershoot.toPx() }
    return remember(listState, reducedMotion, maxOvershootPx) {
        NewestEdgeGlide(listState, reducedMotion, maxOvershootPx)
    }
}

/**
 * How far the list is from its newest edge: exact once the newest row is laid out (it is then the
 * first visible one), estimated from the visible rows' average height until then.
 */
internal fun LazyListState.distanceToNewestPx(): Float =
    firstVisibleItemScrollOffset + firstVisibleItemIndex * averageVisibleRowPx()

private fun LazyListState.averageVisibleRowPx(): Float {
    val info = layoutInfo
    val visible = info.visibleItemsInfo
    if (visible.isEmpty()) return 0f
    return visible.sumOf { it.size + info.mainAxisItemSpacing }.toFloat() / visible.size
}

/** The share of the travel the spring runs past its target, from its damping ratio. */
private val SPRING_PEAK_FRACTION: Float = ChatMotionTokens.ScrollToLatest.DAMPING_RATIO.let { zeta ->
    exp(-zeta * PI.toFloat() / sqrt(1f - zeta * zeta))
}

/**
 * The Touch idiom's button, as feature-chat's ChatMessageList draws it (designsystem
 * ScrollToBottomFab): a small round FAB at the list's bottom end that rises in from below.
 */
@Composable
internal fun TouchScrollToLatestButton(visible: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    AnimatedVisibility(
        visible = visible,
        modifier = modifier,
        enter = fadeIn() + slideInVertically(initialOffsetY = { it / 2 }),
        exit = fadeOut() + slideOutVertically(targetOffsetY = { it / 2 }),
    ) {
        SmallFloatingActionButton(
            onClick = onClick,
            modifier = Modifier.testTag(ChatTimelineTags.SCROLL_TO_LATEST),
            shape = CircleShape,
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            contentColor = MaterialTheme.colorScheme.onSurface,
            elevation = FloatingActionButtonDefaults.elevation(
                defaultElevation = ChatTimelineDimens.scrollToLatestTouchElevation,
                pressedElevation = ChatTimelineDimens.scrollToLatestTouchPressedElevation,
            ),
        ) {
            Icon(
                imageVector = LettaIcons.KeyboardArrowDown,
                contentDescription = stringResource(Res.string.timeline_scroll_to_latest),
            )
        }
    }
}
