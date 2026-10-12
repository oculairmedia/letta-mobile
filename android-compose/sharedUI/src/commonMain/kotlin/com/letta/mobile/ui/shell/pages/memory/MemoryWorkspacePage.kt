package com.letta.mobile.ui.shell.pages.memory

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.letta.mobile.data.memory.graph.MemoryPageActions
import com.letta.mobile.data.memory.graph.MemoryPageState
import com.letta.mobile.data.memory.memfs.MemfsPageActions
import com.letta.mobile.data.memory.memfs.MemfsPageState
import com.letta.mobile.ui.memory.MemoryPage
import com.letta.mobile.ui.memory.MemoryPageOptions
import com.letta.mobile.ui.shell.pages.memfs.MemfsPage
import com.letta.mobile.ui.shell.pages.memfs.MemfsPageOptions
import com.letta.mobile.ui.theme.LettaDimens

/** The Memory destination's two views of the same agent. */
enum class MemoryWorkspaceView(val label: String) { Blocks("Blocks"), Files("Files") }

/** The view a Memory destination shows; saved across configuration changes. */
@Composable
fun rememberMemoryWorkspaceView(): MutableState<MemoryWorkspaceView> =
    rememberSaveable { mutableStateOf(MemoryWorkspaceView.Blocks) }

/** The memory graph and the agent's MemFS files, and the agent the files follow. */
class MemoryWorkspaceBinding(
    val memory: MemoryPageState,
    val memoryActions: MemoryPageActions,
    val files: MemfsPageState,
    val filesActions: MemfsPageActions,
    /** The files view follows the agent chosen in the graph's agent picker. */
    val onFilesAgentChange: (agentId: String?) -> Unit,
    val view: MemoryWorkspaceView,
    val onViewChange: (MemoryWorkspaceView) -> Unit,
    val memoryOptions: MemoryPageOptions = MemoryPageOptions(),
    val filesOptions: MemfsPageOptions = MemfsPageOptions(),
)

object MemoryWorkspaceTags {
    const val PAGE = "memory_workspace_page"
    fun view(view: MemoryWorkspaceView) = "memory_workspace_view_${view.name.lowercase()}"
}

/**
 * letta-mobile-ckmub.3.4: the Memory destination - the memory graph, or the agent's MemFS files
 * with their history (letta-mobile-bzvro.24/.37) - behind one Blocks | Files switch. Replaces the
 * desktop's DesktopMemoryDestination and the switch inside Android's MemoryOverviewScreen. The view
 * is hoisted so a host's back handling can close an open file first.
 */
@Composable
fun MemoryWorkspacePage(binding: MemoryWorkspaceBinding, modifier: Modifier = Modifier) {
    val agentId = binding.memory.parity.memory.selectedAgentId
    LaunchedEffect(agentId) { binding.onFilesAgentChange(agentId) }
    Column(modifier.fillMaxSize().testTag(MemoryWorkspaceTags.PAGE)) {
        MemoryWorkspaceSwitch(binding.view, binding.onViewChange)
        if (binding.view == MemoryWorkspaceView.Files) {
            MemfsPage(
                state = binding.files,
                actions = binding.filesActions,
                options = binding.filesOptions,
                modifier = Modifier.weight(1f),
            )
        } else {
            MemoryPage(
                state = binding.memory,
                actions = binding.memoryActions,
                options = binding.memoryOptions,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun MemoryWorkspaceSwitch(view: MemoryWorkspaceView, onSelect: (MemoryWorkspaceView) -> Unit) {
    SingleChoiceSegmentedButtonRow(Modifier.padding(horizontal = LettaDimens.Space.lg, vertical = LettaDimens.Space.sm)) {
        MemoryWorkspaceView.entries.forEachIndexed { index, option ->
            SegmentedButton(
                selected = view == option,
                onClick = { onSelect(option) },
                shape = SegmentedButtonDefaults.itemShape(index = index, count = MemoryWorkspaceView.entries.size),
                modifier = Modifier.testTag(MemoryWorkspaceTags.view(option)),
            ) { Text(option.label) }
        }
    }
}
