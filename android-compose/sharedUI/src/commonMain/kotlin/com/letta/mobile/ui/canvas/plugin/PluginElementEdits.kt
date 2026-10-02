package com.letta.mobile.ui.canvas.plugin

import com.letta.mobile.data.canvas.CanvasDocumentFrame
import com.letta.mobile.data.canvas.CanvasGeometryOwner
import com.letta.mobile.data.canvas.CanvasOp
import com.letta.mobile.data.canvas.CanvasOpProjector
import com.letta.mobile.data.canvas.CanvasSession
import com.letta.mobile.data.canvas.plugin.CanvasPluginElement

/**
 * What a person does to a plugin element on the board, as ops on the session (plan section 4,
 * split provenance): a move or resize writes ONLY the frame register, owned by USER, so it never
 * overwrites what the plugin writes to the element's state; a removal removes it.
 *
 * The ops are stamped with the session's clock as they go in ([CanvasSession.applyLocalStamped]).
 * letta-mobile-s416w.3 gives the session its own `movePluginElement`/`removePluginElement` with
 * undo; these then call those.
 */
internal object PluginElementEdits {
    /** The op that puts element [id] at [frame] as a person placed it: frame and owner, nothing else. */
    fun moveOp(id: String, frame: CanvasDocumentFrame): CanvasOp.SetPluginElementOp = CanvasOp.SetPluginElementOp(
        opId = "",
        actorId = CanvasSession.LOCAL_USER_ACTOR_ID,
        lamport = 0L,
        elementId = id,
        frame = frame,
        owner = CanvasGeometryOwner.USER,
    )

    suspend fun move(session: CanvasSession, id: String, frame: CanvasDocumentFrame) {
        session.applyLocalStamped(listOf(moveOp(id, frame)))
    }

    suspend fun remove(session: CanvasSession, id: String) {
        if (elementsOf(session).none { it.id == id }) return
        session.applyLocalStamped(
            listOf(CanvasOp.RemovePluginElementOp(opId = "", actorId = CanvasSession.LOCAL_USER_ACTOR_ID, lamport = 0L, elementId = id)),
        )
    }

    /** The plugin elements on [session]'s board. */
    fun elementsOf(session: CanvasSession): List<CanvasPluginElement> = CanvasOpProjector.pluginElementsOf(session.sceneJsonOrEmpty())
}
