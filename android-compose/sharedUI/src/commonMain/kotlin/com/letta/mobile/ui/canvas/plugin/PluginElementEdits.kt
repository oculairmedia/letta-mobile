package com.letta.mobile.ui.canvas.plugin

import com.letta.mobile.data.canvas.CanvasDocumentFrame
import com.letta.mobile.data.canvas.CanvasHistory
import com.letta.mobile.data.canvas.CanvasOpProjector
import com.letta.mobile.data.canvas.CanvasSession
import com.letta.mobile.data.canvas.movePluginElement
import com.letta.mobile.data.canvas.plugin.CanvasPluginElement
import com.letta.mobile.data.canvas.removePluginElement

/**
 * What a person does to a plugin element on the board, through the session's own plugin element
 * writes (letta-mobile-s416w.3, plan section 4): a move or resize writes ONLY the frame register,
 * owned by USER, so it never overwrites what the plugin writes to the element's state; a removal
 * removes it. Each returns its undo step for the board's history, or null when nothing changed.
 */
internal object PluginElementEdits {
    suspend fun move(session: CanvasSession, id: String, frame: CanvasDocumentFrame): CanvasHistory.Step.Documents? =
        session.movePluginElement(id, frame)

    suspend fun remove(session: CanvasSession, id: String): CanvasHistory.Step.Documents? = session.removePluginElement(id)

    /** The plugin elements on [session]'s board. */
    fun elementsOf(session: CanvasSession): List<CanvasPluginElement> = CanvasOpProjector.pluginElementsOf(session.sceneJsonOrEmpty())
}
