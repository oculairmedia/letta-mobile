package com.letta.mobile.data.canvas

import com.letta.mobile.data.canvas.plugin.CanvasPluginElement
import com.letta.mobile.data.canvas.plugin.CanvasPluginElementUndo

/*
 * A person's writes to plugin elements (canvas plugin platform plan 4.5, letta-mobile-s416w.3):
 * moving and removing them. Plugins and agents write through the one validator path
 * (canvas_apply_ops, CanvasSession.applyAgentBatch); these are the board's own gestures.
 *
 * Each returns the history step of what it did, for the board's one CanvasHistory, or null when
 * it changed nothing (an element not on the board, or already there).
 */

/**
 * Moves or resizes plugin element [elementId] to [frame], in world units. The op names the frame
 * register only, owned by [CanvasGeometryOwner.USER], so a state update the plugin makes at the
 * same time survives it on every peer, and it survives the update.
 */
suspend fun CanvasSession.movePluginElement(
    elementId: String,
    frame: CanvasDocumentFrame,
    actorId: String = CanvasSession.LOCAL_USER_ACTOR_ID,
): CanvasHistory.Step.Documents? = movePluginElements(mapOf(elementId to frame), actorId)

/**
 * Moves several plugin elements at once, as [movePluginElement] does each: one op for one element,
 * one batch (one revision, one message to peers) for a group drag. Elements that are not on the
 * board, or already at their frame, are skipped.
 */
suspend fun CanvasSession.movePluginElements(
    frames: Map<String, CanvasDocumentFrame>,
    actorId: String = CanvasSession.LOCAL_USER_ACTOR_ID,
): CanvasHistory.Step.Documents? = writePluginElements(label = "plugin element moved") { elements, lamport ->
    val onBoard = elements.associateBy { it.id }
    val moves = frames.filter { (id, frame) -> onBoard[id]?.let { it.frame != frame } == true }
        .map { (id, frame) -> pluginMove(id, frame, actorId, lamport) }
    when (moves.size) {
        0 -> null
        1 -> moves.single()
        else -> CanvasOp.BatchOp(CanvasOpDiffer.generateOpId("batch"), actorId, lamport, moves)
    }
}

/** Removes plugin element [elementId] from the board; null when it is not there. */
suspend fun CanvasSession.removePluginElement(
    elementId: String,
    actorId: String = CanvasSession.LOCAL_USER_ACTOR_ID,
): CanvasHistory.Step.Documents? = writePluginElements(label = "plugin element removed") { elements, lamport ->
    if (elements.none { it.id == elementId }) null
    else CanvasOp.RemovePluginElementOp(CanvasOpDiffer.generateOpId("plugin"), actorId, lamport, elementId)
}

private fun pluginMove(id: String, frame: CanvasDocumentFrame, actorId: String, lamport: Long) = CanvasOp.SetPluginElementOp(
    opId = CanvasOpDiffer.generateOpId("plugin"),
    actorId = actorId,
    lamport = lamport,
    elementId = id,
    frame = frame,
    owner = CanvasGeometryOwner.USER,
)

/** Applies the op [build] makes of the plugin elements on the board, and returns its history step. */
private suspend fun CanvasSession.writePluginElements(
    label: String,
    build: (elements: List<CanvasPluginElement>, lamport: Long) -> CanvasOp?,
): CanvasHistory.Step.Documents? {
    var step: CanvasHistory.Step.Documents? = null
    applyLocalBuilt { sceneJson, lamport ->
        val before = CanvasOpProjector.pluginElementsOf(sceneJson)
        build(before, lamport)?.also { op -> step = CanvasPluginElementUndo.stepFor(op, before, label) }
    }
    return step
}
