package com.letta.mobile.ui.memory

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.zIndex
import com.letta.mobile.data.memory.graph.MemoryNodeSelection
import com.letta.mobile.data.memory.graph.MemoryPageActions
import com.letta.mobile.data.memory.graph.MemoryPageState
import com.letta.mobile.ui.components.LettaEmptyHint
import com.letta.mobile.ui.components.LettaRefreshable
import com.letta.mobile.ui.components.LettaSidePane
import com.letta.mobile.ui.theme.LettaDimens
import com.letta.mobile.ui.theme.LocalReducedMotion

/**
 * The memory page shared by Android and desktop: overview chrome, the full
 * pan/zoom graph, and the selected node's card. On a wide window the card
 * docks as a side panel; on a phone it rises from the bottom over the graph,
 * above the keyboard while editing.
 *
 * Hosts own navigation chrome (top bar, back handling) and tune the rest
 * through [MemoryPageOptions].
 */
@Composable
fun MemoryPage(
    state: MemoryPageState,
    actions: MemoryPageActions,
    modifier: Modifier = Modifier,
    options: MemoryPageOptions = MemoryPageOptions(),
) {
    val reducedMotion = options.reducedMotion || LocalReducedMotion.current
    // A reload of a graph already drawn; the first load shows the graph area's own spinner.
    val refreshing = state.parity.isLoading && !state.view.isEmpty
    Column(modifier.fillMaxSize()) {
        // Only the chrome pulls to refresh: the graph's drags pan it, and the node card scrolls its own
        // text. Drawn above the graph so the indicator can drop below the chrome.
        LettaRefreshable(refreshing = refreshing, onRefresh = actions::refresh, modifier = Modifier.fillMaxWidth().zIndex(1f)) {
            MemoryPageChrome(state.parity, actions, MemoryChromeOptions(options.showTitle, state.canCreateBlock))
        }
        BoxWithConstraints(Modifier.fillMaxWidth().weight(1f)) {
            val layout = MemoryPageLayout(state, actions, reducedMotion)
            if (maxWidth >= LettaDimens.Pane.wideBreakpoint) {
                WideMemoryLayout(layout)
            } else {
                CompactMemoryLayout(layout, maxHeight)
            }
        }
    }
    MemoryBlockDialogs(state.creation, state.deletion, actions)
}

/**
 * Host presentation choices: [showTitle] false when the host already titles
 * the screen; [reducedMotion] (or [LocalReducedMotion]) turns phone framing of the selection into a
 * jump and keeps the graph still: no bloom on open, no neighbours drifting after a drag.
 */
@Immutable
data class MemoryPageOptions(
    val showTitle: Boolean = true,
    val reducedMotion: Boolean = false,
)

private class MemoryPageLayout(
    val state: MemoryPageState,
    val actions: MemoryPageActions,
    val reducedMotion: Boolean,
)

/** The graph's per-node looks and live positions, shared by the canvas and reveal-on-select. */
private class MemoryGraphRuntime(val looks: Map<String, MemoryNodeLook>, val live: MemoryGraphLiveLayout, val compact: Boolean) {
    /** How much of the canvas's bottom a phone's sheet covers (px, margin included); 0 without one. */
    var coveredPx by mutableIntStateOf(0)
}

@Composable
private fun rememberMemoryGraphRuntime(layout: MemoryPageLayout, compact: Boolean): MemoryGraphRuntime {
    val state = layout.state
    val looks = remember(state.view, state.parity.memory) { MemoryNodeLooks.build(state.view, state.parity.memory) }
    val live = rememberMemoryGraphLiveLayout(state.view, state.layout, looks, motion = !layout.reducedMotion)
    return remember(looks, live, compact) { MemoryGraphRuntime(looks, live, compact) }
}

@Composable
private fun WideMemoryLayout(layout: MemoryPageLayout) {
    val viewportState = rememberMemoryGraphViewportState()
    val runtime = rememberMemoryGraphRuntime(layout, compact = false)
    Row(Modifier.fillMaxSize()) {
        MemoryGraphArea(layout, runtime, viewportState, Modifier.weight(1f).fillMaxHeight())
        layout.state.selection?.let { selection ->
            // The shared docked pane (resizable from its left edge); the card draws its own header.
            LettaSidePane(
                title = selection.detail.title,
                onClose = layout.actions::clearSelection,
                initialWidth = LettaDimens.Pane.sidePanelWidth,
                minWidth = LettaDimens.Pane.sidePanelWidth,
                showHeader = false,
            ) {
                MemoryNodeCardFor(layout.state, selection, layout.actions, Modifier.fillMaxSize())
            }
        }
    }
}

@Composable
private fun CompactMemoryLayout(layout: MemoryPageLayout, maxHeight: Dp) {
    val viewportState = rememberMemoryGraphViewportState()
    val runtime = rememberMemoryGraphRuntime(layout, compact = true)
    val selection = layout.state.selection
    // The sheet's height (with its margin), so the graph is framed above it and the fit button sits on it.
    var sheetPx by remember { mutableIntStateOf(0) }
    runtime.coveredPx = if (selection != null) sheetPx else 0
    FrameSelectionAboveSheet(MemoryFraming(layout.state, runtime.live, layout.reducedMotion), viewportState, runtime.coveredPx)
    Box(Modifier.fillMaxSize()) {
        MemoryGraphArea(layout, runtime, viewportState, Modifier.fillMaxSize())
        if (selection != null) {
            // Editing may take the full height (keyboard up); reading keeps the
            // top of the graph visible so the selected node stays in view.
            val cap = if (selection.editor != null) maxHeight else maxHeight * CARD_READ_HEIGHT_FRACTION
            Surface(
                shape = MaterialTheme.shapes.large,
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                shadowElevation = LettaDimens.Space.sm,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .onSizeChanged { sheetPx = it.height }
                    .padding(LettaDimens.Space.sm)
                    .heightIn(max = cap),
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    BottomSheetDefaults.DragHandle()
                    MemoryNodeCardFor(layout.state, selection, layout.actions)
                }
            }
        }
    }
}

@Composable
private fun MemoryNodeCardFor(
    state: MemoryPageState,
    selection: MemoryNodeSelection,
    actions: MemoryPageActions,
    modifier: Modifier = Modifier,
) {
    val palette = rememberMemoryGraphPalette()
    val card = remember(state.view, state.parity.memory, selection, palette) { MemoryNodeCards.build(state, selection, palette) }
    MemoryNodeCardContent(card = card, actions = actions, modifier = modifier)
}

/**
 * The legend/filter row, then the canvas below it, so the chrome never overlaps the graph. On a
 * phone, framing and the fit button stay above the part the sheet covers ([MemoryGraphRuntime.coveredPx]).
 */
@Composable
private fun MemoryGraphArea(
    layout: MemoryPageLayout,
    runtime: MemoryGraphRuntime,
    viewportState: MemoryGraphViewportState,
    modifier: Modifier = Modifier,
) {
    val state = layout.state
    Column(modifier) {
        if (!state.view.isEmpty) {
            MemoryKindFilterBar(
                view = state.view,
                counts = state.parity.memory.graph.nodes.groupingBy { it.kind }.eachCount(),
                onToggle = layout.actions::toggleKind,
            )
        }
        Box(Modifier.fillMaxWidth().weight(1f)) {
            MemoryGraphBody(layout, runtime, viewportState)
            if (!state.view.isEmpty) MemoryGraphControls(state, viewportState, runtime.compact, runtime.coveredPx)
        }
    }
}

@Composable
private fun BoxScope.MemoryGraphBody(layout: MemoryPageLayout, runtime: MemoryGraphRuntime, viewportState: MemoryGraphViewportState) {
    val state = layout.state
    val actions = layout.actions
    when {
        state.view.isEmpty && state.parity.isLoading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
        state.view.isEmpty -> EmptyMemoryGraph(Modifier.align(Alignment.Center))
        else -> MemoryGraphCanvas(
            params = MemoryGraphCanvasParams(
                view = state.view,
                layout = state.layout,
                live = runtime.live,
                looks = runtime.looks,
                compact = runtime.compact,
                selectedNodeId = state.selectedNodeId,
                onNodeTap = { nodeId -> if (nodeId == null) actions.clearSelection() else actions.selectNode(nodeId) },
            ),
            viewportState = viewportState,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

/**
 * Wide: zoom in/out/fit at the canvas's top-right. Phone: only fit (pinch zooms), at the
 * bottom-right just above the sheet; framing reserves that corner and the sheet.
 */
@Composable
private fun BoxScope.MemoryGraphControls(
    state: MemoryPageState,
    viewportState: MemoryGraphViewportState,
    compact: Boolean,
    coveredPx: Int,
) {
    val density = LocalDensity.current
    val gap = LettaDimens.Space.sm
    val reservePx = with(density) { (LettaDimens.Control.minTouchTarget + gap * 2).toPx() }
    SideEffect {
        viewportState.compact = compact
        viewportState.insets = if (compact) MemoryGraphInsets(right = reservePx, bottom = coveredPx.toFloat()) else MemoryGraphInsets.None
    }
    val covered = with(density) { coveredPx.toDp() }
    val placement = if (compact) {
        Modifier.align(Alignment.BottomEnd).padding(end = gap, bottom = covered + gap)
    } else {
        Modifier.align(Alignment.TopEnd).padding(gap)
    }
    MemoryGraphZoomControls(
        viewportState = viewportState,
        onFit = { viewportState.fit(state.layout) },
        fitOnly = compact,
        modifier = placement,
    )
}

@Composable
private fun EmptyMemoryGraph(modifier: Modifier = Modifier) {
    LettaEmptyHint(text = "No memory graph available.", modifier = modifier)
}

private const val CARD_READ_HEIGHT_FRACTION = 0.6f
