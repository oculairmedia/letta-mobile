package com.letta.mobile.data.chat.tools.bundled

import com.letta.mobile.data.chat.tools.ToolPresenter

/**
 * Shell tools: letta-code's Bash and the shell tools of its other toolsets.
 *
 * One area's registration entries, each presenter in its own file: the builder of this area adds
 * a line here, and [BundledToolPresenters] combines the areas, so no two areas share a list.
 */
internal object ShellToolPresenters {
    val tools: List<ToolPresenter> = listOf(BashToolPresenter)
}
