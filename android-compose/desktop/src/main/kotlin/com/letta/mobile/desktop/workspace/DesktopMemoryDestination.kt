package com.letta.mobile.desktop.workspace

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.letta.mobile.data.memory.graph.MemoryPageActions
import com.letta.mobile.data.memory.graph.MemoryPageState
import com.letta.mobile.data.memory.memfs.MemfsPageController
import com.letta.mobile.ui.memory.MemoryPage
import com.letta.mobile.ui.shell.pages.memfs.MemfsPage
import com.letta.mobile.ui.theme.LettaDimens

private enum class MemoryView(val label: String) { Blocks("Blocks"), Files("Files") }

/**
 * The Memory destination: the memory graph, or the agent's MemFS files with their history
 * (letta-mobile-bzvro.24). Both follow the agent chosen in the graph's agent picker.
 */
@Composable
internal fun DesktopMemoryDestination(
    memoryState: MemoryPageState,
    actions: MemoryPageActions,
    memfs: MemfsPageController,
    modifier: Modifier = Modifier,
) {
    var view by rememberSaveable { mutableStateOf(MemoryView.Blocks.name) }
    val agentId = memoryState.parity.memory.selectedAgentId
    LaunchedEffect(agentId) { memfs.selectAgent(agentId) }
    Column(modifier.fillMaxSize()) {
        SingleChoiceSegmentedButtonRow(Modifier.padding(horizontal = LettaDimens.Space.lg, vertical = LettaDimens.Space.sm)) {
            MemoryView.entries.forEachIndexed { index, option ->
                SegmentedButton(
                    selected = view == option.name,
                    onClick = { view = option.name },
                    shape = SegmentedButtonDefaults.itemShape(index = index, count = MemoryView.entries.size),
                ) { Text(option.label) }
            }
        }
        if (view == MemoryView.Files.name) {
            val state by memfs.state.collectAsState()
            MemfsPage(state = state, actions = memfs, modifier = Modifier.weight(1f))
        } else {
            MemoryPage(state = memoryState, actions = actions, modifier = Modifier.weight(1f))
        }
    }
}
