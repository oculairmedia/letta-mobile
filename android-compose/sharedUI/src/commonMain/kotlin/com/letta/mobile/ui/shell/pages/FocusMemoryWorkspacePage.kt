package com.letta.mobile.ui.shell.pages

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.letta.mobile.data.memory.graph.MemoryPageState
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.ui.shell.pages.memory.MemoryWorkspaceBinding
import com.letta.mobile.ui.shell.pages.memory.MemoryWorkspacePage
import com.letta.mobile.ui.shell.pages.memory.MemoryWorkspaceView
import com.letta.mobile.ui.theme.LettaDimens

/** Shell entry point: agent changes go through the typed focus owner. */
@Composable
internal fun FocusMemoryWorkspacePage(
    binding: MemoryWorkspaceBinding,
    onSelectAgent: (AgentId) -> Unit,
    modifier: Modifier = Modifier,
) {
    val memory = binding.memory
    val page = MemoryWorkspaceBinding(
        memory = memory.copy(parity = memory.parity.copy(agents = emptyList())),
        memoryActions = binding.memoryActions,
        files = binding.files,
        filesActions = binding.filesActions,
        onFilesAgentChange = binding.onFilesAgentChange,
        view = binding.view,
        onViewChange = binding.onViewChange,
        memoryOptions = binding.memoryOptions,
        filesOptions = binding.filesOptions,
    )
    Column(modifier.fillMaxSize()) {
        if (binding.view == MemoryWorkspaceView.Blocks && memory.parity.agents.isNotEmpty()) {
            FocusMemoryAgentPicker(memory, onSelectAgent)
        }
        MemoryWorkspacePage(page, Modifier.weight(1f))
    }
}

@Composable
private fun FocusMemoryAgentPicker(memory: MemoryPageState, onSelectAgent: (AgentId) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val agents = memory.parity.agents
    val selected = agents.firstOrNull { it.id == memory.parity.memory.selectedAgentId }
    Box(Modifier.padding(horizontal = LettaDimens.Space.lg)) {
        OutlinedButton(onClick = { expanded = true }) { Text(selected?.name ?: "Choose agent") }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            agents.forEach { agent ->
                DropdownMenuItem(text = { Text(agent.name) }, onClick = {
                    expanded = false
                    onSelectAgent(AgentId(agent.id))
                })
            }
        }
    }
}
