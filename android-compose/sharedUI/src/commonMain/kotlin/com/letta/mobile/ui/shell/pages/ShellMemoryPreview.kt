package com.letta.mobile.ui.shell.pages

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.letta.mobile.data.chat.tools.ToolPreviewTarget
import com.letta.mobile.data.memory.MemoryParityState
import com.letta.mobile.data.memory.graph.MemoryNodeDetails
import com.letta.mobile.data.memory.graph.MemoryPageActions
import com.letta.mobile.data.memory.graph.MemoryPageState
import com.letta.mobile.data.memory.memfs.MemfsPageActions
import com.letta.mobile.data.memory.memfs.MemfsPageState
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.UiToolCall
import com.letta.mobile.data.workspace.WorkspacePaths
import com.letta.mobile.sharedui.resources.Res
import com.letta.mobile.sharedui.resources.rows_memory_chip_usage
import com.letta.mobile.sharedui.resources.rows_memory_preview_earlier
import com.letta.mobile.sharedui.resources.rows_memory_preview_this_edit
import com.letta.mobile.sharedui.resources.rows_memory_preview_title
import com.letta.mobile.sharedui.resources.rows_memory_preview_usage
import com.letta.mobile.sharedui.resources.rows_memory_preview_usage_unlimited
import com.letta.mobile.ui.host.InspectorPane
import com.letta.mobile.ui.memory.MemoryPageOptions
import com.letta.mobile.ui.shell.pages.memfs.MemfsPageOptions
import com.letta.mobile.ui.shell.pages.memory.MemoryWorkspaceBinding
import com.letta.mobile.ui.shell.pages.memory.MemoryWorkspacePage
import com.letta.mobile.ui.shell.pages.memory.MemoryWorkspaceView
import com.letta.mobile.ui.shell.pages.workspace.MemoryPreview
import com.letta.mobile.ui.theme.LettaDimens
import kotlinx.coroutines.flow.StateFlow
import org.jetbrains.compose.resources.stringResource

/** What the shell's memory preview reads and drives: the Memory page's own controllers. */
@Immutable
class ShellMemorySources(
    val memory: StateFlow<MemoryPageState>,
    val memoryActions: MemoryPageActions,
    val files: StateFlow<MemfsPageState>,
    val filesActions: MemfsPageActions,
    /** Shows another agent's MemFS files (the files controller's own agent switch). */
    val selectFilesAgent: (AgentId?) -> Unit,
)

/** Test tags for the Memory view a memory step opens. */
object ShellMemoryPreviewTags {
    const val CHIP = "memory_preview_chip"
    const val PANE = "memory_preview_pane"
    const val EARLIER = "memory_preview_earlier"
}

/**
 * The shell's [MemoryPreview]: a memory step's chip opens the shell's own Memory view (the same
 * graph and files the Memory page draws, over the same controllers) in the window's detail pane,
 * focused on the step's block (Blocks) or MemFS entry (Files), above an inspector of the edit,
 * the block's usage and the conversation's earlier edits of it.
 */
@Stable
class ShellMemoryPreview(private val sources: ShellMemorySources, private val agent: () -> AgentId?) : MemoryPreview<UiToolCall> {
    private var opened by mutableStateOf<OpenedEntry?>(null)

    /** What is open, if anything. */
    val openTarget: ToolPreviewTarget? get() = opened?.target

    /** Opens [target], which [step] touched, focusing the Memory view on it for the conversation's agent. */
    fun open(target: ToolPreviewTarget, step: UiToolCall) {
        val view = viewOf(target) ?: return
        opened = OpenedEntry(target, step, view)
        val shown = sources.memory.value.parity.memory.selectedAgentId?.let(::AgentId)
        val focused = agent() ?: shown
        // The agent's blocks as they are now: the step may have just changed them.
        if (focused != null && focused != shown) sources.memoryActions.selectAgent(focused.value) else sources.memoryActions.refresh()
        if (target is ToolPreviewTarget.Memory) {
            sources.selectFilesAgent(focused)
            sources.filesActions.openFile(target.path)
        }
    }

    fun close() {
        opened = null
    }

    @Composable
    override fun Chip(target: ToolPreviewTarget, step: UiToolCall) {
        if (viewOf(target) == null) return
        val state by sources.memory.collectAsState()
        AssistChip(
            onClick = { open(target, step) },
            label = { Text(chipLabel(target, state.parity.memory), maxLines = 1, overflow = TextOverflow.Ellipsis) },
            modifier = Modifier.testTag(ShellMemoryPreviewTags.CHIP),
        )
    }

    @Composable
    override fun Pane(edits: (ToolPreviewTarget) -> List<UiToolCall>, draw: @Composable (UiToolCall) -> Unit) {
        val entry = opened ?: return
        val memory by sources.memory.collectAsState()
        val files by sources.files.collectAsState()
        FocusBlock(entry, memory)
        var view by remember(entry) { mutableStateOf(entry.view) }
        val binding = MemoryWorkspaceBinding(
            memory = memory,
            memoryActions = sources.memoryActions,
            files = files,
            filesActions = sources.filesActions,
            onFilesAgentChange = { shown -> sources.selectFilesAgent(shown?.let(::AgentId)) },
            view = view,
            onViewChange = { view = it },
            memoryOptions = MemoryPageOptions(showTitle = false),
            filesOptions = MemfsPageOptions(showTitle = false),
        )
        val title = stringResource(Res.string.rows_memory_preview_title)
        InspectorPane(title = title, onClose = ::close, testTag = ShellMemoryPreviewTags.PANE) {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                MemoryWorkspacePage(binding, Modifier.height(MEMORY_VIEW_HEIGHT))
                // Asked for only while the pane is open, once per opened entry.
                val earlier = remember(entry) { edits(entry.target).filterNot { it.sameStepAs(entry.step) }.take(EARLIER_EDITS_MAX) }
                MemoryInspector(InspectorSteps(entry.step, earlier, draw), usageOf(entry.target, memory.parity.memory))
            }
        }
    }

    /** Selects the block's node once the agent's graph has it; the graph may still be loading when the pane opens. */
    @Composable
    private fun FocusBlock(entry: OpenedEntry, memory: MemoryPageState) {
        val label = (entry.target as? ToolPreviewTarget.MemoryBlock)?.label ?: return
        val nodeId = MemoryNodeDetails.blockNodeId(memory.parity.memory, label)
        LaunchedEffect(entry, nodeId) {
            if (nodeId != null && sources.memory.value.selectedNodeId != nodeId) sources.memoryActions.selectNode(nodeId)
        }
    }

    private class OpenedEntry(val target: ToolPreviewTarget, val step: UiToolCall, val view: MemoryWorkspaceView)

    private companion object {
        fun viewOf(target: ToolPreviewTarget): MemoryWorkspaceView? = when (target) {
            is ToolPreviewTarget.MemoryBlock -> MemoryWorkspaceView.Blocks
            is ToolPreviewTarget.Memory -> MemoryWorkspaceView.Files
            else -> null
        }

        val MEMORY_VIEW_HEIGHT: Dp = 420.dp
        const val EARLIER_EDITS_MAX = 20
    }
}

/** A block's characters used against its limit (null when it has none). */
@Immutable
private data class MemoryUsage(val used: Int, val limit: Int?)

private fun usageOf(target: ToolPreviewTarget, memory: MemoryParityState): MemoryUsage? {
    val label = (target as? ToolPreviewTarget.MemoryBlock)?.label ?: return null
    val block = MemoryNodeDetails.block(memory, label) ?: return null
    return MemoryUsage(block.length, block.limit)
}

/** The same step, though its result may have arrived since it was opened. */
private fun UiToolCall.sameStepAs(other: UiToolCall): Boolean =
    toolCallId?.let { it == other.toolCallId } ?: (this == other)

/** The inspector's steps: the one that opened the view, the conversation's earlier ones on the same entry, and how the chat draws one. */
private class InspectorSteps(val opened: UiToolCall, val earlier: List<UiToolCall>, val draw: @Composable (UiToolCall) -> Unit)

/** "persona · 812/5000" for a block the agent's memory holds with a limit, else its name. */
@Composable
private fun chipLabel(target: ToolPreviewTarget, memory: MemoryParityState): String = when (target) {
    is ToolPreviewTarget.MemoryBlock -> {
        val usage = usageOf(target, memory)
        val limit = usage?.limit
        if (limit == null) target.label else stringResource(Res.string.rows_memory_chip_usage, target.label, usage.used, limit)
    }
    is ToolPreviewTarget.Memory -> WorkspacePaths.fileName(target.path)
    else -> ""
}

/** Under the Memory view: the edit that opened it, the block's usage, then the conversation's earlier edits of it. */
@Composable
private fun MemoryInspector(steps: InspectorSteps, usage: MemoryUsage?) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(LettaDimens.Space.lg),
        verticalArrangement = Arrangement.spacedBy(LettaDimens.Space.sm),
    ) {
        InspectorHeading(stringResource(Res.string.rows_memory_preview_this_edit))
        steps.draw(steps.opened)
        usage?.let { UsageLine(it) }
        if (steps.earlier.isNotEmpty()) {
            InspectorHeading(stringResource(Res.string.rows_memory_preview_earlier))
            Column(Modifier.testTag(ShellMemoryPreviewTags.EARLIER), verticalArrangement = Arrangement.spacedBy(LettaDimens.Space.sm)) {
                steps.earlier.forEach { steps.draw(it) }
            }
        }
    }
}

@Composable
private fun InspectorHeading(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
}

@Composable
private fun UsageLine(usage: MemoryUsage) {
    val limit = usage.limit
    val text = if (limit == null) {
        stringResource(Res.string.rows_memory_preview_usage_unlimited, usage.used)
    } else {
        stringResource(Res.string.rows_memory_preview_usage, usage.used, limit)
    }
    Text(text, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}
