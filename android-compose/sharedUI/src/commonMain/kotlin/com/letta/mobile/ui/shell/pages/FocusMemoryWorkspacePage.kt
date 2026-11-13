package com.letta.mobile.ui.shell.pages

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.ui.memory.MemoryAgentPicker
import com.letta.mobile.ui.shell.pages.memory.MemoryWorkspaceBinding
import com.letta.mobile.ui.shell.pages.memory.MemoryWorkspacePage

/** Shell entry point: agent changes go through the typed focus owner. */
@Composable
internal fun FocusMemoryWorkspacePage(
    binding: MemoryWorkspaceBinding,
    onSelectAgent: (AgentId) -> Unit,
    modifier: Modifier = Modifier,
) {
    val memory = binding.memory
    val agents = memory.parity.agents
    // The focus owner switches agents, so the page's own picker gives way to one that reports here;
    // it keeps the header's context place beside the title.
    val picker: (@Composable () -> Unit)? = if (agents.isNotEmpty()) {
        { MemoryAgentPicker(agents, memory.parity.memory.selectedAgentId) { onSelectAgent(AgentId(it)) } }
    } else {
        null
    }
    val page = MemoryWorkspaceBinding(
        memory = memory.copy(parity = memory.parity.copy(agents = emptyList())),
        memoryActions = binding.memoryActions,
        files = binding.files,
        filesActions = binding.filesActions,
        onFilesAgentChange = binding.onFilesAgentChange,
        view = binding.view,
        onViewChange = binding.onViewChange,
        memoryOptions = binding.memoryOptions.copy(headerContext = picker),
        filesOptions = binding.filesOptions,
    )
    MemoryWorkspacePage(page, modifier)
}
