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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import com.letta.mobile.data.memory.graph.MemoryGraphPoint
import com.letta.mobile.data.memory.graph.MemoryNodeSelection
import com.letta.mobile.data.memory.graph.MemoryPageActions
import com.letta.mobile.data.memory.graph.MemoryPageState
import com.letta.mobile.ui.theme.LettaDimens

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
    val reducedMotion = options.reducedMotion
    Column(modifier.fillMaxSize()) {
        MemoryPageChrome(state.parity, actions, options.showTitle)
        BoxWithConstraints(Modifier.fillMaxWidth().weight(1f)) {
            val layout = MemoryPageLayout(state, actions, reducedMotion)
            if (maxWidth >= LettaDimens.Pane.wideBreakpoint) {
                WideMemoryLayout(layout)
            } else {
                CompactMemoryLayout(layout, maxHeight)
            }
        }
    }
}

/**
 * Host presentation choices: [showTitle] false when the host already titles
 * the screen; [reducedMotion] turns the reveal-selected-node pan into a jump.
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

@Composable
private fun WideMemoryLayout(layout: MemoryPageLayout) {
    val viewportState = rememberMemoryGraphViewportState()
    Row(Modifier.fillMaxSize()) {
        MemoryGraphArea(layout.state, layout.actions, viewportState, Modifier.weight(1f).fillMaxHeight())
        layout.state.selection?.let { selection ->
            VerticalDivider()
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                modifier = Modifier.width(LettaDimens.Pane.sidePanelWidth).fillMaxHeight(),
            ) {
                MemoryNodeCardFor(layout.state, selection, layout.actions, Modifier.fillMaxSize())
            }
        }
    }
}

@Composable
private fun CompactMemoryLayout(layout: MemoryPageLayout, maxHeight: Dp) {
    val viewportState = rememberMemoryGraphViewportState()
    val selection = layout.state.selection
    RevealSelectedNode(layout, viewportState)
    Box(Modifier.fillMaxSize()) {
        MemoryGraphArea(layout.state, layout.actions, viewportState, Modifier.fillMaxSize())
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
                    .imePadding()
                    .padding(LettaDimens.Space.sm)
                    .heightIn(max = cap),
            ) {
                MemoryNodeCardFor(layout.state, selection, layout.actions)
            }
        }
    }
}

/** Pan the tapped node into the strip the bottom card leaves uncovered. */
@Composable
private fun RevealSelectedNode(layout: MemoryPageLayout, viewportState: MemoryGraphViewportState) {
    val nodeId = layout.state.selectedNodeId
    LaunchedEffect(nodeId) {
        val point = nodeId?.let { layout.state.layout[it] } ?: return@LaunchedEffect
        val size = viewportState.size
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
    val node = state.view.node(selection.detail.nodeId)
    MemoryNodeCardContent(
        card = MemoryNodeCardState(
            selection = selection,
            degree = state.view.degreeOf(selection.detail.nodeId),
            accent = node?.let(palette::nodeColor) ?: palette.kindColor(selection.detail.kind),
        ),
        actions = actions,
        modifier = modifier,
    )
}

@Composable
private fun MemoryGraphArea(
    state: MemoryPageState,
    actions: MemoryPageActions,
    viewportState: MemoryGraphViewportState,
    modifier: Modifier = Modifier,
) {
    Box(modifier) {
        when {
            state.view.isEmpty && state.parity.isLoading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
            state.view.isEmpty -> EmptyMemoryGraph(Modifier.align(Alignment.Center))
            else -> MemoryGraphCanvas(
                params = MemoryGraphCanvasParams(
                    view = state.view,
                    layout = state.layout,
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
                    MemoryKindFilterBar(view = state.view, onToggle = actions::toggleKind)
                }
                MemoryGraphZoomControls(viewportState = viewportState, onFit = { viewportState.fit(state.layout) })
            }
        }
    }
}

@Composable
private fun EmptyMemoryGraph(modifier: Modifier = Modifier) {
    Text(
        text = "No memory graph available.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier,
    )
}

private const val CARD_READ_HEIGHT_FRACTION = 0.6f
private const val REVEAL_HEIGHT_FRACTION = 0.2f
