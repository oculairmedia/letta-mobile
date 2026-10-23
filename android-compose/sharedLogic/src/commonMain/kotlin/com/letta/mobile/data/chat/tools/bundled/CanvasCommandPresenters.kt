package com.letta.mobile.data.chat.tools.bundled

import com.letta.mobile.data.chat.tools.CommandPresenter

/**
 * The canvas CLI agents run from a shell tool (meridian canvas ...).
 *
 * One area's registration entries, each presenter in its own file: the builder of this area adds
 * a line here, and [BundledToolPresenters] combines the areas, so no two areas share a list.
 */
internal object CanvasCommandPresenters {
    val commands: List<CommandPresenter> = listOf()
}
