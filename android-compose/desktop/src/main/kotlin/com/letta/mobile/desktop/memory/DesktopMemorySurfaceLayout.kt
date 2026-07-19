package com.letta.mobile.desktop.memory

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.letta.mobile.desktop.DesktopInlineError

internal data class MemorySurfaceChrome(
    val state: DesktopMemorySurfaceState,
    val onRefresh: () -> Unit,
    val onAgentSelected: (String) -> Unit,
    val blockApi: DesktopBlockApi?,
)

@Composable
internal fun RowScope.MemoryMainColumn(
    chrome: MemorySurfaceChrome,
    agentId: String?,
    onEditorTargetChange: (BlockEditorTarget?) -> Unit,
) {
    // A fixed (non-scrolling) column: the header/agent/stats are fixed
    // height and the graph takes the rest, so the page never scrolls.
    Column(
        modifier = Modifier
            .weight(1f)
            .fillMaxHeight(),
    ) {
        MemorySurfaceTopChrome(
            chrome = chrome,
            canCreateBlock = agentId != null && chrome.blockApi != null,
            onNewBlock = { onEditorTargetChange(BlockEditorTarget.New) },
        )
        Spacer(Modifier.height(12.dp))
        // The graph is the focus — it takes the remaining height, flush to the
        // pane edges. Blocks stay editable by clicking their graph nodes.
        MemoryGraphPanel(
            graph = chrome.state.memory.graph,
            onBlockNodeClick = { node ->
                if (agentId != null) {
                    onEditorTargetChange(BlockEditorTarget.Existing(node.title, node.sourceItemId))
                }
            },
            modifier = Modifier.weight(1f).fillMaxWidth(),
        )
    }
}

@Composable
internal fun MemorySurfaceTopChrome(
    chrome: MemorySurfaceChrome,
    canCreateBlock: Boolean,
    onNewBlock: () -> Unit,
) {
    // Header / selector / summary keep their inset; the graph below runs
    // edge-to-edge so it doesn't waste space.
    Column(
        modifier = Modifier.padding(start = 28.dp, end = 28.dp, top = 18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        MemoryHeader(
            state = chrome.state,
            onRefresh = chrome.onRefresh,
            // Re-expose block creation: the graph-only redesign dropped the
            // only entry point for adding a memory block (Codex review #7).
            // The editor panel needs both an agent and a block API, so gate
            // the action on the same preconditions.
            onNewBlock = onNewBlock.takeIf { canCreateBlock },
        )
        chrome.state.errorMessage?.let { errorMessage ->
            DesktopInlineError(
                message = errorMessage,
                onRetry = chrome.onRefresh,
                retrying = chrome.state.isLoading,
            )
        }
        if (chrome.state.agents.isNotEmpty()) {
            AgentSelector(
                agents = chrome.state.agents,
                selectedAgentId = chrome.state.memory.selectedAgentId,
                onAgentSelected = chrome.onAgentSelected,
            )
        }
        MemorySummaryCard(chrome.state.memory.summary)
    }
}

internal data class MemoryBlockEditorSlotParams(
    val target: BlockEditorTarget?,
    val agentId: String?,
    val blockApi: DesktopBlockApi?,
    val onDismiss: () -> Unit,
    val onChanged: () -> Unit,
)

@Composable
internal fun MemoryBlockEditorSlot(params: MemoryBlockEditorSlotParams) {
    val target = params.target
    val agentId = params.agentId
    val blockApi = params.blockApi
    if (target == null || agentId == null || blockApi == null) return
    Box(
        modifier = Modifier
            .fillMaxHeight()
            .width(1.dp)
            .background(MaterialTheme.colorScheme.outlineVariant),
    )
    BlockEditorPanel(
        request = BlockEditorRequest(
            target = target,
            agentId = agentId,
            blockApi = blockApi,
            onDismiss = params.onDismiss,
            onChanged = params.onChanged,
        ),
    )
}
