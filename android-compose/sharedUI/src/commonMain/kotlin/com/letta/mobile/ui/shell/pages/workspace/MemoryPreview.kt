package com.letta.mobile.ui.shell.pages.workspace

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import com.letta.mobile.data.chat.tools.ToolPreviewTarget

/**
 * What a host binds so a memory tool step opens the agent's Memory view: the step's chip for a
 * memory block ([ToolPreviewTarget.MemoryBlock]) or MemFS entry ([ToolPreviewTarget.Memory]), and
 * the pane it opens in the window's detail pane (the sidebar on a wide window, a bottom card on a
 * phone). The chat never draws the Memory view itself; the shell's own one is reused. A [Step] is
 * the chat's own tool step, which only the chat draws.
 */
@Stable
interface MemoryPreview<Step> {
    /** [target]'s chip on [step]: the entry's name, with its usage when the host knows it; tapping opens it. */
    @Composable
    fun Chip(target: ToolPreviewTarget, step: Step)

    /**
     * The open entry's Memory view, focused on it, with an inspector of the step that opened it and
     * the conversation's [edits] of the same entry (asked for only while open), each drawn with [draw].
     */
    @Composable
    fun Pane(edits: (ToolPreviewTarget) -> List<Step>, draw: @Composable (Step) -> Unit)
}
