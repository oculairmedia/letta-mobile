package com.letta.mobile.feature.chat.screen

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyListItemInfo
import androidx.compose.foundation.lazy.LazyListLayoutInfo
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
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.semantics.clearAndSetSemantics
import kotlinx.coroutines.flow.first
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.LoadState
import androidx.paging.LoadStates
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

internal class TimelineShellToken

internal sealed interface TimelineShellLifecycle {
    val token: TimelineShellToken

    data class Mounted(override val token: TimelineShellToken) : TimelineShellLifecycle
    data class Disposed(override val token: TimelineShellToken) : TimelineShellLifecycle
}

internal val LocalTimelineShellLifecycleObserver =
    staticCompositionLocalOf<((TimelineShellLifecycle) -> Unit)?> { null }

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

internal val LocalTimelineReadinessObserver = staticCompositionLocalOf<((androidx.compose.foundation.lazy.LazyListState, Boolean) -> Unit)?> { null }

/** Ready is latched only after source completeness, anchor application and bounded measurement. */
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
            val shellToken = remember { TimelineShellToken() }
            val shellObserver = LocalTimelineShellLifecycleObserver.current
            androidx.compose.runtime.DisposableEffect(shellToken) {
                shellObserver?.invoke(TimelineShellLifecycle.Mounted(shellToken))
                onDispose { shellObserver?.invoke(TimelineShellLifecycle.Disposed(shellToken)) }
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
    val source = pages.loadState.source
    val restoreAnchor = remember(presentation) { presentation.viewport?.takeUnless { it.following } }
    val requestedTarget = routeTarget ?: restoreAnchor?.messageId
    val targetIndex = requestedTarget?.let {
        PagedTimelineEffects.resolveTargetScrollPosition(it, displayedLive, pages.itemSnapshotList)
    }
    val sourceState = deriveTimelineOpeningSourceState(
        source = source,
        refresh = refresh,
        requestedTarget = requestedTarget,
        targetIndex = targetIndex,
        itemCount = pages.itemCount,
        displayedLiveEmpty = displayedLive.isEmpty(),
    )
    var anchorApplied by remember(presentation, sourceState.requestedTarget) { mutableStateOf(false) }
    var revealed by remember(presentation) { mutableStateOf(false) }
    val readiness = deriveTimelineOpeningState(
        opening = false,
        openError = null,
        historyReady = revealed || sourceState.confirmedEmpty,
        confirmedEmpty = sourceState.confirmedEmpty && !revealed,
        refresh = sourceState.failure ?: refresh,
    )
    ObserveOpeningCommit(
        when (readiness) {
            TimelineOpeningState.Ready, TimelineOpeningState.Empty -> TimelineOpeningObservation.Surface.Timeline
            is TimelineOpeningState.Failed -> TimelineOpeningObservation.Surface.InitialFailed
            else -> TimelineOpeningObservation.Surface.InitialLoading
        },
        residentRows = if (revealed) pages.itemSnapshotList.items.size else 0,
        confirmedEmpty = sourceState.confirmedEmpty,
    )
    ObserveResidentRows(presentation, pages)
    val listState = key(presentation) { rememberLazyListState() }
    val readinessObserver = LocalTimelineReadinessObserver.current
    SideEffect { readinessObserver?.invoke(listState, revealed) }
    val missingTarget by presentation.missingTarget.collectAsStateWithLifecycle()
    LaunchedEffect(sourceState.sourceReady, anchorApplied, sourceState.requestedTarget, pages.itemSnapshotList, displayedLive) {
        if (!revealed && sourceState.sourceReady && (sourceState.requestedTarget == null || anchorApplied)) {
            if (sourceState.requestedTarget == null) listState.scrollToItem(0)
            snapshotFlow {
                isViewportReady(
                    layout = listState.layoutInfo,
                    targetIndex = sourceState.targetIndex ?: 0,
                    routeTarget = routeTarget != null,
                    savedOffset = restoreAnchor?.offset,
                )
            }.first { it }
            revealed = true
        }
    }
    var following by remember(presentation, routeTarget) {
        mutableStateOf(routeTarget == null && restoreAnchor == null)
    }
    val highlightedTargetState = rememberHighlightedTarget(presentation, routeTarget)

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
            onHighlightTarget = highlightedTargetState.onHighlight,
            onAnchorApplied = { anchorApplied = true },
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
            highlightedTarget = highlightedTargetState.target,
            routeTarget = routeTarget,
            missingTarget = missingTarget,
            appearance = appearance,
            callbacks = callbacks,
            modifier = modifier
                .then(if (revealed) Modifier else Modifier.clearAndSetSemantics { })
                .focusProperties { canFocus = revealed }
                // Placement must continue during Priming so lazy measurement and anchoring run.
                .drawWithContent { if (revealed) drawContent() },
        ),
    )
    if (!revealed) Box(Modifier.fillMaxSize().pointerInput(Unit) {
        // This topmost sibling owns hit testing, including empty space around the treatment.
        awaitPointerEventScope {
            while (true) {
                awaitPointerEvent(PointerEventPass.Final).changes.forEach { it.consume() }
            }
        }
    }) {
        OpeningTreatment(readiness, state.agentId, pages::retry)
    }
}

@Immutable
internal data class TimelineOpeningSourceState(
    val failure: LoadState.Error?,
    val confirmedEmpty: Boolean,
    val requestedTarget: String?,
    val targetIndex: Int?,
    val sourceReady: Boolean,
)

internal fun deriveTimelineOpeningSourceState(
    source: LoadStates,
    refresh: LoadState,
    requestedTarget: String?,
    targetIndex: Int?,
    itemCount: Int,
    displayedLiveEmpty: Boolean,
): TimelineOpeningSourceState {
    val failure = listOf(source.refresh, source.prepend, source.append)
        .filterIsInstance<LoadState.Error>()
        .firstOrNull()
    val isRefreshNotLoading = refresh is LoadState.NotLoading
    val isPrependNotLoading = source.prepend is LoadState.NotLoading
    val isAppendNotLoading = source.append is LoadState.NotLoading

    val sourceReady = failure == null && isRefreshNotLoading && if (requestedTarget == null) {
        isPrependNotLoading && source.prepend.endOfPaginationReached
    } else {
        targetIndex != null && isPrependNotLoading && isAppendNotLoading
    }

    val confirmedEmpty = isRefreshNotLoading &&
        source.prepend.endOfPaginationReached &&
        source.append.endOfPaginationReached &&
        itemCount == 0 &&
        displayedLiveEmpty

    return TimelineOpeningSourceState(
        failure = failure,
        confirmedEmpty = confirmedEmpty,
        requestedTarget = requestedTarget,
        targetIndex = targetIndex,
        sourceReady = sourceReady,
    )
}

internal fun isLayoutBounded(layout: LazyListLayoutInfo): Boolean =
    layout.viewportSize.width > 0 && layout.viewportSize.height > 0

internal fun isAnchorItemPositioned(
    item: LazyListItemInfo,
    targetIndex: Int,
    expectedOffset: Int,
    layout: LazyListLayoutInfo,
): Boolean {
    if (item.index != targetIndex || item.size <= 0) return false
    if (item.offset != expectedOffset) return false
    return item.offset < layout.viewportEndOffset && item.offset + item.size > layout.viewportStartOffset
}

internal fun isViewportReady(
    layout: LazyListLayoutInfo,
    targetIndex: Int,
    routeTarget: Boolean,
    savedOffset: Int?,
): Boolean {
    if (!isLayoutBounded(layout)) return false
    return layout.visibleItemsInfo.any { item ->
        val expectedOffset = PagedTimelineEffects.openingAnchorOffset(
            layout = layout,
            itemSize = item.size,
            routeTarget = routeTarget,
            savedOffset = savedOffset,
        )
        isAnchorItemPositioned(item, targetIndex, expectedOffset, layout)
    }
}

@Immutable
private data class HighlightedTargetState(
    val target: String?,
    val onHighlight: (String?) -> Unit,
)

@Composable
private fun rememberHighlightedTarget(
    presentation: ChatPagingPresentation,
    routeTarget: String?,
): HighlightedTargetState {
    var highlightedTarget by remember(presentation, routeTarget) { mutableStateOf<String?>(null) }
    LaunchedEffect(highlightedTarget) {
        if (highlightedTarget != null) {
            kotlinx.coroutines.delay(2_000)
            highlightedTarget = null
        }
    }
    return remember(highlightedTarget) {
        HighlightedTargetState(highlightedTarget) { highlightedTarget = it }
    }
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


internal fun pagedBoundaryDate(newer: ChatRenderItem, older: ChatRenderItem?): LocalDate? {
    if (older == null || newer.boundaryTimestamp.take(10) == older.boundaryTimestamp.take(10)) return null
    return runCatching { LocalDate.parse(newer.boundaryTimestamp.take(10)) }.getOrNull()
}
