package com.letta.mobile.feature.chat.screen

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Immutable
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.LoadState
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.collectAsLazyPagingItems
import com.letta.mobile.data.chat.projection.ChatRenderItem
import com.letta.mobile.ui.chat.render.ChatUiState
import com.letta.mobile.ui.chat.render.RenderDiagnostics
import com.letta.mobile.ui.mascot.MascotLoading
import java.time.LocalDate

internal fun followNewestEdge(wasScrolling: Boolean, atNewestEdge: Boolean, prependExhausted: Boolean): Boolean =
    wasScrolling && atNewestEdge && prependExhausted

internal fun shouldRepositionAfterPagerRefresh(refresh: LoadState): Boolean =
    refresh is LoadState.Loading

internal fun displayedLiveRows(live: List<ChatRenderItem>, residentSettled: List<ChatRenderItem>): List<ChatRenderItem> {
    val settledKeys = residentSettled.mapTo(mutableSetOf()) { it.key }
    return live.filterNot { it.key in settledKeys }
}

/** Additive S1 diagnostics: composition commits and layout passes, not GPU-presented frames. */
internal sealed interface TimelineOpeningObservation {
    enum class Surface { Opening, OpenFailed, InitialLoading, InitialFailed, Timeline }
    data class Committed(val surface: Surface, val residentRows: Int = 0, val confirmedEmpty: Boolean = false) : TimelineOpeningObservation
    data class Layout(val rows: List<VisibleRow>, val viewportStart: Int, val viewportEnd: Int) : TimelineOpeningObservation
    data class VisibleRow(val key: String, val offset: Int, val size: Int)
}

internal val LocalTimelineOpeningObserver = staticCompositionLocalOf<((TimelineOpeningObservation) -> Unit)?> { null }

@Composable
private fun ObserveOpeningCommit(
    surface: TimelineOpeningObservation.Surface,
    residentRows: Int = 0,
    confirmedEmpty: Boolean = false,
) {
    val observer = LocalTimelineOpeningObserver.current
    if (observer != null) SideEffect {
        observer(TimelineOpeningObservation.Committed(surface, residentRows, confirmedEmpty))
    }
}

internal val LocalTimelineShellLifecycleObserver = staticCompositionLocalOf<((Boolean, Any) -> Unit)?> { null }

internal enum class TimelineRowLifecycle { Mount, Dispose }

internal val LocalTimelineRowLifecycleObserver = staticCompositionLocalOf<(TimelineRowLifecycle, String) -> Unit> {
    { _, _ -> }
}

internal fun residentTargetIndex(
    rows: List<ChatRenderItem>,
    target: String,
    liveCount: Int,
    placeholdersBefore: Int,
): Int? = rows.indexOfFirst { it.containsMessageId(target) }
    .takeIf { it >= 0 }?.let { liveCount + placeholdersBefore + it }

/** S2 readiness preserves the resident-row gate; Ready does not promise an anchored viewport. */
@Immutable
internal sealed interface TimelineOpeningState {
    data object Opening : TimelineOpeningState
    data object Priming : TimelineOpeningState
    data object Ready : TimelineOpeningState
    data object Empty : TimelineOpeningState
    data class Failed(val message: String, val duringOpen: Boolean) : TimelineOpeningState
}

internal fun deriveTimelineOpeningState(
    opening: Boolean,
    openError: String?,
    historyReady: Boolean = false,
    confirmedEmpty: Boolean = false,
    refresh: LoadState = LoadState.Loading,
): TimelineOpeningState = when {
    openError != null -> TimelineOpeningState.Failed(openError, duringOpen = true)
    opening -> TimelineOpeningState.Opening
    historyReady -> if (confirmedEmpty) TimelineOpeningState.Empty else TimelineOpeningState.Ready
    refresh is LoadState.Error -> TimelineOpeningState.Failed("Could not load conversation", duringOpen = false)
    else -> TimelineOpeningState.Priming
}

@Composable
private fun OpeningTreatment(
    readiness: TimelineOpeningState,
    agentId: String?,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier.fillMaxSize(),
) {
    val label = when (readiness) {
        TimelineOpeningState.Opening -> "Opening conversation..."
        TimelineOpeningState.Priming -> "Loading conversation..."
        TimelineOpeningState.Empty -> "No messages yet"
        is TimelineOpeningState.Failed -> readiness.message
        TimelineOpeningState.Ready -> return
    }
    androidx.compose.foundation.layout.Column(modifier.semantics {
        stateDescription = label
        liveRegion = LiveRegionMode.Polite
        if (readiness is TimelineOpeningState.Failed) error(label)
        if (readiness == TimelineOpeningState.Opening || readiness == TimelineOpeningState.Priming) {
            progressBarRangeInfo = ProgressBarRangeInfo.Indeterminate
        }
    }) {
        if (readiness == TimelineOpeningState.Opening || readiness == TimelineOpeningState.Priming) MascotLoading(agentId)
        Text(label)
        if (readiness is TimelineOpeningState.Failed) TextButton(onClick = onRetry) { Text("Retry") }
    }
}

/** Paging owns load hints, retries and dropping. Never materialize the settled snapshot. */
@Composable
internal fun PagedChatMessageList(
    presentation: ChatPagingPresentation,
    state: ChatUiState,
    callbacks: ChatContentCallbacks,
    appearance: ChatContentAppearance,
    modifier: Modifier = Modifier,
) {
    // Outside the open/content branches: the actual surface survives even the immutable
    // opening-placeholder -> opened-presentation handoff. Paging state remains presentation-keyed.
    // Caller constraints, padding and semantics belong exclusively to this outer container.
    // Internal fill consumes its content bounds; the separate shell tag cannot replace a caller tag.
    Box(modifier = modifier, propagateMinConstraints = true) {
        Box(Modifier.fillMaxSize().testTag("timeline-opening-shell")) {
            val shellIdentity = remember { Any() }
            val shellObserver = LocalTimelineShellLifecycleObserver.current
            androidx.compose.runtime.DisposableEffect(shellIdentity) {
                shellObserver?.invoke(true, shellIdentity)
                onDispose { shellObserver?.invoke(false, shellIdentity) }
            }
            key(presentation) {
                val readiness = deriveTimelineOpeningState(presentation.opening, presentation.openError)
                if (readiness == TimelineOpeningState.Opening || readiness is TimelineOpeningState.Failed) {
                    ObserveOpeningCommit(if (readiness is TimelineOpeningState.Failed) TimelineOpeningObservation.Surface.OpenFailed else TimelineOpeningObservation.Surface.Opening)
                    OpeningTreatment(readiness, state.agentId, presentation.retryOpen)
                } else {
                    PagedChatMessageListContent(presentation, state, callbacks, appearance, Modifier.fillMaxSize())
                }
            }
        }
    }
}

@Composable
private fun PagedChatMessageListContent(
    presentation: ChatPagingPresentation,
    state: ChatUiState,
    callbacks: ChatContentCallbacks,
    appearance: ChatContentAppearance,
    modifier: Modifier,
) {
    val routeTarget = if (presentation.hasBoundRoute) presentation.routeTarget else appearance.scrollToMessageId
    val pages = presentation.settled.collectAsLazyPagingItems()
    val live by presentation.live.collectAsStateWithLifecycle()
    val displayedLive = displayedLiveRows(live, pages.itemSnapshotList.items)
    val refresh = pages.loadState.source.refresh
    val readiness = deriveTimelineOpeningState(
        opening = false,
        openError = null,
        historyReady = rememberHistoryGate(presentation, pages),
        confirmedEmpty = pages.itemCount == 0 && displayedLive.isEmpty() &&
            PagedTimelineLazyLayout.isInitialPageAvailable(pages, refresh),
        refresh = refresh,
    )
    if (readiness == TimelineOpeningState.Priming || readiness is TimelineOpeningState.Failed) {
        ObserveOpeningCommit(if (readiness is TimelineOpeningState.Failed) TimelineOpeningObservation.Surface.InitialFailed else TimelineOpeningObservation.Surface.InitialLoading)
        OpeningTreatment(readiness, state.agentId, pages::retry, modifier)
        return
    }
    ObserveOpeningCommit(
        TimelineOpeningObservation.Surface.Timeline,
        residentRows = pages.itemSnapshotList.items.size,
        confirmedEmpty = pages.itemCount == 0 && displayedLive.isEmpty() &&
            PagedTimelineLazyLayout.isInitialPageAvailable(pages, refresh),
    )
    ObserveResidentRows(presentation, pages)
    val listState = key(presentation) { rememberLazyListState() }
    val missingTarget by presentation.missingTarget.collectAsStateWithLifecycle()
    val restoreAnchor = remember(presentation) { presentation.viewport?.takeUnless { it.following } }
    var following by remember(presentation, routeTarget) {
        mutableStateOf(routeTarget == null && restoreAnchor == null)
    }
    val (highlightedTarget, onHighlightTarget) = rememberHighlightedTarget(presentation, routeTarget)

    PagedTimelineEffects.ScrollEffects(
        params = PagedTimelineScrollEffectsParams(
            listState = listState,
            pages = pages,
            live = live,
            presentation = presentation,
            following = following,
            onFollowingChange = { following = it },
        ),
    )
    PagedTimelineEffects.TargetEffects(
        params = PagedTimelineTargetEffectsParams(
            presentation = presentation,
            pages = pages,
            displayedLive = displayedLive,
            listState = listState,
            routeTarget = routeTarget,
            restoreAnchor = restoreAnchor,
            following = following,
            onHighlightTarget = onHighlightTarget,
        ),
    )

    PagedTimelineLazyLayout.Viewport(
        params = PagedTimelineViewportParams(
            presentation = presentation,
            state = state,
            pages = pages,
            displayedLive = displayedLive,
            live = live,
            listState = listState,
            following = following,
            onFollowingChange = { following = it },
            highlightedTarget = highlightedTarget,
            routeTarget = routeTarget,
            missingTarget = missingTarget,
            appearance = appearance,
            callbacks = callbacks,
            modifier = modifier,
        ),
    )
    if (readiness == TimelineOpeningState.Empty) OpeningTreatment(readiness, state.agentId, pages::retry)
}

@Composable
private fun rememberHistoryGate(
    presentation: ChatPagingPresentation,
    pages: LazyPagingItems<ChatRenderItem>,
): Boolean {
    var initialHistoryReady by remember(presentation) { mutableStateOf(false) }
    val refresh = pages.loadState.source.refresh
    val initialPageAvailable = PagedTimelineLazyLayout.isInitialPageAvailable(pages, refresh)
    if (initialPageAvailable) SideEffect { initialHistoryReady = true }
    return initialHistoryReady || initialPageAvailable
}

@Composable
private fun ObserveResidentRows(
    presentation: ChatPagingPresentation,
    pages: LazyPagingItems<ChatRenderItem>,
) {
    LaunchedEffect(presentation, pages) {
        snapshotFlow { pages.itemSnapshotList.items }.collect { resident ->
            // letta-mobile-x1xnl: this host builds its rows from LazyPagingItems,
            // so it never calls RenderDiagnostics.onRenderItemsBuilt — the hook
            // that ends a render generation. Without a bump here the composed-key
            // set accumulates across every page load and each legitimate
            // re-composition is reported as lazyItem.doubleComposed at WARN.
            RenderDiagnostics.newRenderGeneration()
            presentation.onResidentRows(resident)
        }
    }
}

@Composable
private fun rememberHighlightedTarget(
    presentation: ChatPagingPresentation,
    routeTarget: String?,
): Pair<String?, (String?) -> Unit> {
    var highlightedTarget by remember(presentation, routeTarget) { mutableStateOf<String?>(null) }
    LaunchedEffect(highlightedTarget) {
        if (highlightedTarget != null) {
            kotlinx.coroutines.delay(2_000)
            highlightedTarget = null
        }
    }
    return highlightedTarget to { highlightedTarget = it }
}

internal fun pagedBoundaryDate(newer: ChatRenderItem, older: ChatRenderItem?): LocalDate? {
    if (older == null || newer.boundaryTimestamp.take(10) == older.boundaryTimestamp.take(10)) return null
    return runCatching { LocalDate.parse(newer.boundaryTimestamp.take(10)) }.getOrNull()
}
