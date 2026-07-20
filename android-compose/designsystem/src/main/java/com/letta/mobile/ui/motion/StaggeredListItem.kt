package com.letta.mobile.ui.motion

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import kotlinx.coroutines.delay

import kotlin.time.Duration.Companion.milliseconds
/** Per-index stagger delay. Single source of truth for the entrance cadence. */
const val StaggerDelayPerIndexMs: Long = 30L

/**
 * Items at indices >= this value render immediately (no stagger). Items
 * loaded by endless-scroll past the initial viewport should pop in, not
 * march. Bounded stagger also caps total coroutine work on large lists.
 */
const val StaggerViewportIndexLimit: Int = 8

/**
 * Wrap a LazyColumn item to fade + slide in on first composition. Use inside
 * `LazyColumn { itemsIndexed(...) { index, item -> StaggeredListItem(index) { ... } } }`.
 *
 * - First-viewport items (index < [StaggerViewportIndexLimit]) animate with a
 *   45 ms × index delay; later items skip the stagger and appear immediately.
 * - Slide is proportional ([height / 4]) so cards rise from below rather than
 *   teleport across the full row height.
 * - `rememberSaveable` records whether the entrance has already played, so
 *   back-nav restores instantly without re-marching the list.
 * - The inner [Box] calls [LazyItemScope.animateItem] so reorders / additions
 *   animate naturally once the entrance is complete.
 */
@Composable
fun LazyItemScope.StaggeredListItem(
    index: Int,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    var entranceCompleted by rememberSaveable { mutableStateOf(false) }
    val withinViewport = index < StaggerViewportIndexLimit
    var isVisible by remember { mutableStateOf(entranceCompleted) }

    LaunchedEffect(Unit) {
        if (!entranceCompleted) {
            if (withinViewport) {
                delay((index * StaggerDelayPerIndexMs).milliseconds)
            }
            isVisible = true
            entranceCompleted = true
        }
    }

    AnimatedVisibility(
        visible = isVisible,
        enter = fadeIn(animationSpec = spring(stiffness = Spring.StiffnessLow)) +
            slideInVertically(
                animationSpec = spring(stiffness = Spring.StiffnessLow),
                initialOffsetY = { it / 4 },
            ),
        exit = fadeOut(animationSpec = spring(stiffness = Spring.StiffnessLow)),
    ) {
        Box(
            modifier = modifier
                .fillMaxWidth()
                .animateItem(),
        ) {
            content()
        }
    }
}
