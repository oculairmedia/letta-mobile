package com.letta.mobile.ui.shell.pages.memory

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
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
import com.letta.mobile.ui.components.LettaChipTab
import com.letta.mobile.ui.components.LettaChipTabRow
import com.letta.mobile.ui.memory.MemoryPage
import com.letta.mobile.ui.memory.MemoryPageOptions
import com.letta.mobile.ui.shell.pages.memfs.MemfsPage
import com.letta.mobile.ui.shell.pages.memfs.MemfsPageOptions

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
 * The Memory destination - the memory graph, or the agent's MemFS files
 * with their history - behind one Blocks | Files switch. Replaces the
 * desktop's DesktopMemoryDestination and the switch inside Android's MemoryOverviewScreen. The view
 * is hoisted so a host's back handling can close an open file first.
 */
@Composable
fun MemoryWorkspacePage(binding: MemoryWorkspaceBinding, modifier: Modifier = Modifier) {
    val agentId = binding.memory.parity.memory.selectedAgentId
    LaunchedEffect(agentId) { binding.onFilesAgentChange(agentId) }
    // Both views draw the same page header with this switch on its controls row, so the title, the
    // switch and the actions hold their place when the view changes.
    val switch: @Composable () -> Unit = { MemoryWorkspaceSwitch(binding.view, binding.onViewChange) }
    Box(modifier.fillMaxSize().testTag(MemoryWorkspaceTags.PAGE)) {
        if (binding.view == MemoryWorkspaceView.Files) {
            MemfsPage(
                state = binding.files,
                actions = binding.filesActions,
                options = binding.filesOptions.copy(title = PAGE_TITLE, headerControls = switch),
            )
        } else {
            MemoryPage(
                state = binding.memory,
                actions = binding.memoryActions,
                options = binding.memoryOptions.copy(headerControls = switch),
            )
        }
    }
}

@Composable
private fun MemoryWorkspaceSwitch(view: MemoryWorkspaceView, onSelect: (MemoryWorkspaceView) -> Unit) {
    LettaChipTabRow {
        MemoryWorkspaceView.entries.forEach { option ->
            LettaChipTab(
                text = option.label,
                active = view == option,
                onClick = { onSelect(option) },
                modifier = Modifier.testTag(MemoryWorkspaceTags.view(option)),
            )
        }
    }
}

private const val PAGE_TITLE = "Memory"
