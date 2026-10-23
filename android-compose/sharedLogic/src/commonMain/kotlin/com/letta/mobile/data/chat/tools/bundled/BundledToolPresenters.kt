package com.letta.mobile.data.chat.tools.bundled

import com.letta.mobile.data.chat.tools.ToolPresenterRegistry

/**
 * What every host ships: the presenters of every area (shell, files, tasks and agents, memory, and
 * the canvas), registered through the same [ToolPresenterRegistry] calls a plugin's
 * contributions go through. Each area keeps its own registration list, so adding a presenter
 * never edits this file.
 */
object BundledToolPresenters {
    val registry: ToolPresenterRegistry = ToolPresenterRegistry.Empty
        .registerAll(
            ShellToolPresenters.tools +
                FileToolPresenters.tools +
                TaskToolPresenters.tools +
                MemoryToolPresenters.tools +
                CanvasToolPresenters.tools,
        )
}
