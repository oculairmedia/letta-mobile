package com.letta.mobile.data.chat.tools.bundled

import com.letta.mobile.data.chat.tools.ToolPresenterRegistry

/**
 * What every host ships: one presenter per bundled tool (and per bundled CLI), each its own unit,
 * registered through the same [ToolPresenterRegistry] calls a plugin's contributions go through.
 */
object BundledToolPresenters {
    val registry: ToolPresenterRegistry = ToolPresenterRegistry.Empty
        .register(BashToolPresenter)
}
