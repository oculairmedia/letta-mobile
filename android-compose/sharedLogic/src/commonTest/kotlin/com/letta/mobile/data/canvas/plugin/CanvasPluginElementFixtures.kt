package com.letta.mobile.data.canvas.plugin

import com.letta.mobile.data.canvas.CanvasDocumentFrame
import com.letta.mobile.data.canvas.CanvasGeometryOwner
import com.letta.mobile.data.canvas.CanvasOp
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Plugin element ops for the tests: a generic `ext:letta.example/widget`, placed, updated, moved and removed. */
internal object CanvasPluginElementFixtures {
    const val ID = "pe-widget-1"
    const val TYPE = "ext:letta.example/widget"
    const val AGENT = "agent:agent-123"
    const val PLUGIN = "plugin:letta.example"
    const val USER = "local_user"

    val frame = CanvasDocumentFrame(1200f, 80f, 320f, 240f)
    val moved = CanvasDocumentFrame(400f, 640f, 320f, 240f)
    val snapshot = CanvasPluginSnapshot("sha256:" + "a1".repeat(32), "image/png", 1024, 1024, 3, 1_770_000_000_000)
    val fallback = CanvasPluginFallback("Example widget", "Example · running 42%", "box", "https://example.test/widgets/1")

    fun props(vararg entries: Pair<String, Any>): JsonObject = JsonObject(
        entries.associate { (key, value) ->
            key to when (value) {
                is Number -> JsonPrimitive(value)
                is Boolean -> JsonPrimitive(value)
                else -> JsonPrimitive(value.toString())
            }
        },
    )

    /** The first write: everything the element needs, placed at [frame]. */
    fun place(lamport: Long, id: String = ID, actor: String = AGENT): CanvasOp.SetPluginElementOp = CanvasOp.SetPluginElementOp(
        opId = "place-$id-$lamport", actorId = actor, lamport = lamport, elementId = id,
        elementType = TYPE, v = 1, frame = frame, ref = "job:7f3a",
        props = props("status" to "queued", "progress" to 0),
        snapshot = snapshot, fallback = fallback,
        meta = props("pluginId" to "letta.example", "createdBy" to actor),
    )

    /** A state-only update, as the plugin writes progress. */
    fun progress(lamport: Long, progress: Double, id: String = ID, actor: String = PLUGIN): CanvasOp.SetPluginElementOp =
        CanvasOp.SetPluginElementOp(
            opId = "progress-$id-$lamport", actorId = actor, lamport = lamport, elementId = id,
            props = props("status" to "running", "progress" to progress),
        )

    /** A frame-only move, as a person drags the element. */
    fun move(lamport: Long, to: CanvasDocumentFrame = moved, id: String = ID): CanvasOp.SetPluginElementOp = CanvasOp.SetPluginElementOp(
        opId = "move-$id-$lamport", actorId = USER, lamport = lamport, elementId = id, frame = to, owner = CanvasGeometryOwner.USER,
    )

    fun remove(lamport: Long, id: String = ID, actor: String = USER): CanvasOp.RemovePluginElementOp =
        CanvasOp.RemovePluginElementOp(opId = "remove-$id-$lamport", actorId = actor, lamport = lamport, elementId = id)

    /** Every order of [ops]. */
    fun <T> permutations(ops: List<T>): List<List<T>> =
        if (ops.size <= 1) listOf(ops)
        else ops.indices.flatMap { index -> permutations(ops.filterIndexed { i, _ -> i != index }).map { listOf(ops[index]) + it } }
}
