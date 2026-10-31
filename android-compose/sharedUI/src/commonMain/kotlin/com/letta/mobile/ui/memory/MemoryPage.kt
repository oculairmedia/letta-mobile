package com.letta.mobile.ui.memory

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.zIndex
import com.letta.mobile.data.memory.graph.MemoryGraphPoint
import com.letta.mobile.data.memory.graph.MemoryNodeSelection
import com.letta.mobile.data.memory.graph.MemoryPageActions
import com.letta.mobile.data.memory.graph.MemoryPageState
import com.letta.mobile.ui.components.LettaEmptyHint
import com.letta.mobile.ui.components.LettaRefreshable
import com.letta.mobile.ui.components.LettaSidePane
import com.letta.mobile.ui.theme.LettaDimens
import com.letta.mobile.ui.theme.LocalReducedMotion
import kotlinx.coroutines.flow.first

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
 * the screen; [reducedMotion] (or [LocalReducedMotion]) turns the reveal-selected-node pan into a
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
private class MemoryGraphRuntime(val looks: Map<String, MemoryNodeLook>, val live: MemoryGraphLiveLayout, val compact: Boolean)

@Composable
private fun rememberMemoryGraphRuntime(layout: MemoryPageLayout, compact: Boolean): MemoryGraphRuntime {
    val state = layout.state
    val looks = remember(state.view, state.parity.memory) { MemoryNodeLooks.build(state.view, state.parity.memory) }
    val live = rememberMemoryGraphLiveLayout(state.view, state.layout, looks, motion = !layout.reducedMotion)
    return MemoryGraphRuntime(looks, live, compact)
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
    RevealSelectedNode(layout, runtime.live, viewportState)
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

/** Pan the tapped node into the strip the bottom card leaves uncovered. */
@Composable
private fun RevealSelectedNode(layout: MemoryPageLayout, live: MemoryGraphLiveLayout, viewportState: MemoryGraphViewportState) {
    val nodeId = layout.state.selectedNodeId
    LaunchedEffect(nodeId) {
        if (nodeId == null) return@LaunchedEffect
        // Wait for the canvas to be measured (and fitted) before panning against its size.
        val size = snapshotFlow { viewportState.size }.first { it.width > 0f && it.height > 0f }
        // Mid-bloom the node is still travelling: aim at where it settles.
        val point = (if (live.isSettled) live.layout[nodeId] else null) ?: layout.state.layout[nodeId] ?: return@LaunchedEffect
        val target = MemoryGraphPoint(size.width / 2f, size.height * REVEAL_HEIGHT_FRACTION)
        viewportState.reveal(point, target, layout.reducedMotion)
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

@Composable
private fun MemoryGraphArea(
    layout: MemoryPageLayout,
    runtime: MemoryGraphRuntime,
    viewportState: MemoryGraphViewportState,
    modifier: Modifier = Modifier,
) {
    val state = layout.state
    val actions = layout.actions
    Box(modifier) {
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
        if (!state.view.isEmpty) {
            Row(
                modifier = Modifier.align(Alignment.TopStart).fillMaxWidth().padding(LettaDimens.Space.sm),
                horizontalArrangement = Arrangement.spacedBy(LettaDimens.Space.sm),
                verticalAlignment = Alignment.Top,
            ) {
                // The weighted Box caps the chips; the scroll row inside wraps them,
                // so drags over empty graph beside the chips still pan.
                Box(Modifier.weight(1f)) {
                    MemoryKindFilterBar(
                        view = state.view,
                        counts = state.parity.memory.graph.nodes.groupingBy { it.kind }.eachCount(),
                        onToggle = actions::toggleKind,
                    )
                }
                MemoryGraphZoomControls(viewportState = viewportState, onFit = { viewportState.fit(state.layout) })
            }
        }
    }
}

@Composable
private fun EmptyMemoryGraph(modifier: Modifier = Modifier) {
    LettaEmptyHint(text = "No memory graph available.", modifier = modifier)
}

private const val CARD_READ_HEIGHT_FRACTION = 0.6f
private const val REVEAL_HEIGHT_FRACTION = 0.26f
