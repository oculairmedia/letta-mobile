package com.letta.mobile.data.canvas.plugin

import com.letta.mobile.data.canvas.CanvasGeometryOwner
import com.letta.mobile.data.canvas.CanvasHistory
import com.letta.mobile.data.canvas.CanvasOp
import com.letta.mobile.data.canvas.CanvasOpDiffer
import com.letta.mobile.data.canvas.CanvasSession
import kotlinx.serialization.json.JsonObject

/**
 * How to put a plugin element change back (letta-mobile-s416w.3), the plugin-element side of
 * [com.letta.mobile.data.canvas.CanvasDocumentUndo].
 *
 * A step is ops both ways, like a document step, and goes on the board's one [CanvasHistory]. It
 * names only the registers the change touched: undoing a person's move writes the frame back and
 * nothing else, so state the plugin wrote since (progress, a new snapshot) is kept. Undoing a
 * placement removes the element; undoing a removal writes every part back, under a fresh stamp
 * ([CanvasSession.applyLocalStamped]) that is newer than the removal.
 *
 * A part the element did not have (no ref, no meta) cannot be written back as absent, because a
 * null field in [CanvasOp.SetPluginElementOp] keeps what the element has; such a part stays.
 */
object CanvasPluginElementUndo {

    /**
     * The history step for [change] made to the plugin elements [before] it, or null when it would
     * change none of them. [change] is taken as the newest write, as a local op is.
     */
    fun stepFor(change: CanvasOp, before: List<CanvasPluginElement>, label: String = ""): CanvasHistory.Step.Documents? =
        stepBetween(before, applied(change, before), label)

    /** The history step that turns [before] into [after], or null when no element differs. */
    fun stepBetween(
        before: List<CanvasPluginElement>,
        after: List<CanvasPluginElement>,
        label: String = "",
    ): CanvasHistory.Step.Documents? {
        val beforeById = before.associateBy { it.id }
        val afterById = after.associateBy { it.id }
        val undo = mutableListOf<CanvasOp>()
        val redo = mutableListOf<CanvasOp>()
        (beforeById.keys + afterById.keys).sorted().forEach { id ->
            val (back, again) = opsBetween(id, beforeById[id], afterById[id]) ?: return@forEach
            undo += back
            redo += again
        }
        if (undo.isEmpty()) return null
        return CanvasHistory.Step.Documents(undo = undo, redo = redo, label = label)
    }

    /** The op that turns [now] back into [was], and the one that does it again; null when nothing differs. */
    private fun opsBetween(id: String, was: CanvasPluginElement?, now: CanvasPluginElement?): Pair<CanvasOp, CanvasOp>? = when {
        was == now -> null
        was == null -> remove(id) to restore(requireNotNull(now))
        now == null -> restore(was) to remove(id)
        // A part [now] lacks cannot be named back as absent, so the redo of such a step writes all of [now].
        else -> restoreChanged(was, now)?.let { back -> back to (restoreChanged(now, was) ?: restore(now)) }
    }

    /** [elements] after [change], modelled the way the projector settles a newest write. */
    private fun applied(change: CanvasOp, elements: List<CanvasPluginElement>): List<CanvasPluginElement> = when (change) {
        is CanvasOp.SetPluginElementOp -> upsert(elements, change)
        is CanvasOp.RemovePluginElementOp -> elements.filterNot { it.id == change.elementId }
        is CanvasOp.BatchOp -> change.ops.fold(elements) { acc, child -> applied(child, acc) }
        else -> elements
    }

    private fun upsert(elements: List<CanvasPluginElement>, op: CanvasOp.SetPluginElementOp): List<CanvasPluginElement> {
        val existing = elements.firstOrNull { it.id == op.elementId }
        val updated = existing?.let { merged(it, op) } ?: created(op) ?: return elements
        return (elements.filterNot { it.id == op.elementId } + updated).sortedBy { it.id }
    }

    /** [element] after [op]: what [op] names replaces its register, the rest is kept. */
    private fun merged(element: CanvasPluginElement, op: CanvasOp.SetPluginElementOp): CanvasPluginElement =
        withState(withPlacement(element, op), op)

    /** The frame register (frame and owner) and the type register (type and v). */
    private fun withPlacement(element: CanvasPluginElement, op: CanvasOp.SetPluginElementOp): CanvasPluginElement = element.copy(
        type = op.elementType ?: element.type,
        v = op.elementType?.let { op.v ?: 1 } ?: element.v,
        frame = op.frame ?: element.frame,
        owner = op.frame?.let { op.owner ?: CanvasGeometryOwner.EXPLICIT } ?: element.owner,
    )

    /** The registers the plugin writes besides the type. */
    private fun withState(element: CanvasPluginElement, op: CanvasOp.SetPluginElementOp): CanvasPluginElement = element.copy(
        ref = op.ref ?: element.ref,
        props = op.props ?: element.props,
        snapshot = op.snapshot ?: element.snapshot,
        fallback = op.fallback ?: element.fallback,
        meta = op.meta ?: element.meta,
    )

    /** The element a first write makes, or null when [op] is not one (no type or no fallback). */
    private fun created(op: CanvasOp.SetPluginElementOp): CanvasPluginElement? {
        val type = op.elementType ?: return null
        val fallback = op.fallback ?: return null
        return CanvasPluginElement(
            id = op.elementId, type = type, v = op.v ?: 1,
            frame = op.frame, owner = op.frame?.let { op.owner ?: CanvasGeometryOwner.EXPLICIT },
            ref = op.ref, props = op.props ?: JsonObject(emptyMap()), snapshot = op.snapshot, fallback = fallback, meta = op.meta,
        )
    }

    /**
     * The op that gives the element [target]'s value of every register in which it differs from
     * [other], and names no other register; null when none differs.
     */
    private fun restoreChanged(target: CanvasPluginElement, other: CanvasPluginElement): CanvasOp.SetPluginElementOp? {
        fun <T> changed(part: (CanvasPluginElement) -> T?): T? = part(target).takeIf { it != part(other) }
        val moved = target.frame != other.frame || target.owner != other.owner
        val retyped = target.type != other.type || target.v != other.v
        val op = write(target.id).copy(
            elementType = target.type.takeIf { retyped },
            v = target.v.takeIf { retyped },
            frame = target.frame.takeIf { moved },
            owner = target.owner.takeIf { moved },
            ref = changed { it.ref },
            props = changed { it.props },
            snapshot = changed { it.snapshot },
            fallback = changed { it.fallback },
            meta = changed { it.meta },
        )
        return op.takeIf { it.namesAnyRegister() }
    }

    private fun CanvasOp.SetPluginElementOp.namesAnyRegister(): Boolean =
        listOf(elementType, frame, ref, props, snapshot, fallback, meta).any { it != null }

    /** The op that writes every part of [element] back. */
    private fun restore(element: CanvasPluginElement): CanvasOp.SetPluginElementOp = write(element.id).copy(
        elementType = element.type, v = element.v, frame = element.frame, owner = element.owner, ref = element.ref,
        props = element.props, snapshot = element.snapshot, fallback = element.fallback, meta = element.meta,
    )

    /** An op naming nothing yet; stamped when it is applied ([CanvasSession.applyLocalStamped]). */
    private fun write(id: String): CanvasOp.SetPluginElementOp =
        CanvasOp.SetPluginElementOp(CanvasOpDiffer.generateOpId("undo"), CanvasSession.LOCAL_USER_ACTOR_ID, 0L, id)

    private fun remove(id: String): CanvasOp.RemovePluginElementOp =
        CanvasOp.RemovePluginElementOp(CanvasOpDiffer.generateOpId("undo"), CanvasSession.LOCAL_USER_ACTOR_ID, 0L, id)
}
