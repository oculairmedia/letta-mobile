package com.letta.mobile.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.letta.mobile.ui.host.LettaInput
import com.letta.mobile.ui.host.LocalLettaHost
import com.letta.mobile.ui.icons.LettaIcons

/**
 * The one way a shared page lets the user reload a list (letta-mobile-ckmub.3.21): pull-to-refresh
 * on a touch host, a refresh icon on a pointer host - never both, and never a refresh button on a
 * phone.
 *
 * [LettaRefreshable] wraps the page's scrolling body, below its header, so the pull indicator drops
 * in under the header. On a pointer host it is a plain box and the page's header shows
 * [LettaRefreshAction] instead, which draws nothing on touch. [refreshing] comes from the page's
 * controller; a page whose controller does not report progress passes false and the indicator
 * springs back as soon as the refresh is requested.
 *
 * The pull reaches the indicator through nested scrolling, so [content] must scroll vertically (a
 * lazy list, or a `verticalScroll` column for an empty or error state).
 */
@Composable
fun LettaRefreshable(
    refreshing: Boolean,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    if (isPointerHost()) {
        Box(modifier, content = content)
        return
    }
    val state = rememberPullToRefreshState()
    PullToRefreshBox(
        isRefreshing = refreshing,
        onRefresh = onRefresh,
        modifier = modifier.testTag(LettaRefreshTags.PULL),
        state = state,
        indicator = {
            PullToRefreshDefaults.Indicator(
                state = state,
                isRefreshing = refreshing,
                modifier = Modifier.align(Alignment.TopCenter).testTag(LettaRefreshTags.INDICATOR),
            )
        },
        content = content,
    )
}

/**
 * The pointer half of [LettaRefreshable]: the header's refresh button on desktop and web, nothing
 * on touch (the pull replaces it). [button] is a host's own control (desktop's compact icon
 * button); it defaults to [LettaRefreshIconButton].
 */
@Composable
fun LettaRefreshAction(
    refreshing: Boolean,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
    button: (@Composable (onRefresh: () -> Unit, modifier: Modifier) -> Unit)? = null,
) {
    if (!isPointerHost()) return
    if (button != null) button(onRefresh, modifier) else LettaRefreshIconButton(onRefresh, modifier, enabled = !refreshing)
}

/** The shared refresh icon button; only [LettaRefreshAction] and pointer-only host slots draw it. */
@Composable
fun LettaRefreshIconButton(onRefresh: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    IconButton(onClick = onRefresh, enabled = enabled, modifier = modifier) {
        Icon(LettaIcons.Refresh, contentDescription = if (enabled) REFRESH_LABEL else REFRESHING_LABEL)
    }
}

/** True on desktop and web, where a page shows a refresh button instead of the pull. */
@Composable
fun isPointerHost(): Boolean = LocalLettaHost.current.input == LettaInput.Pointer

/** Test tags of [LettaRefreshable]. */
object LettaRefreshTags {
    const val PULL = "letta_refresh_pull"
    const val INDICATOR = "letta_refresh_indicator"
}

private const val REFRESH_LABEL = "Refresh"
private const val REFRESHING_LABEL = "Refreshing"
