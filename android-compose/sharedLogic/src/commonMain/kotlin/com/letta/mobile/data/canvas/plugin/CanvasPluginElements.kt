package com.letta.mobile.data.canvas.plugin

import com.letta.mobile.data.canvas.CanvasDocumentFrame
import com.letta.mobile.data.canvas.CanvasGeometryOwner
import com.letta.mobile.data.canvas.CanvasLwwRegisters
import com.letta.mobile.data.canvas.CanvasOp
import com.letta.mobile.data.canvas.CanvasWriter
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The scene-root collection `_pluginElements` as the projector keeps it (plan section 4.1): an
 * array of entries ordered by id, removed ones kept as tombstones, like `_documents`.
 *
 * Provenance is split. The `frame` register (frame and owner) is what a person moving the element
 * writes; the state registers (`type` with `v`, `ref`, `props`, `snapshot`, `fallback`, `meta`) are
 * what the plugin writes. Each register is last-writer-wins on its own ([CanvasLwwRegisters]), so a
 * move and a state update made at the same time both survive, on every peer, in any arrival order.
 * `_frame` and `_state` summarise the newest writer of each side; the entry-level provenance is the
 * newest of all.
 *
 * A write's null fields keep what the element has. An entry can be partial: a move that arrives
 * before the element's first write, or one newer than a removal, holds a frame and no type. It is
 * kept (the first write fills it in) but it is not an element: [elementsOf] skips it.
 */
internal object CanvasPluginElements {
    const val KEY: String = "_pluginElements"

    /** Tombstones kept, newest first; the same bound idea as the projector's element tombstones. */
    private const val MAX_TOMBSTONES = 256

    private const val FRAME = "frame"
    private const val OWNER = "owner"
    private const val TYPE = "type"
    private const val VERSION = "v"
    private const val REF = "ref"
    private const val PROPS = "props"
    private const val SNAPSHOT = "snapshot"
    private const val FALLBACK = "fallback"
    private const val META = "meta"

    private val stateRegisters = listOf(TYPE, REF, PROPS, SNAPSHOT, FALLBACK, META)

    private val lww = CanvasLwwRegisters(
        registers = mapOf(FRAME to listOf(FRAME, OWNER), TYPE to listOf(TYPE, VERSION)) +
            listOf(REF, PROPS, SNAPSHOT, FALLBACK, META).associateWith { listOf(it) },
        groups = mapOf("_frame" to listOf(FRAME), "_state" to stateRegisters),
    )

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
        explicitNulls = false
    }

    /** The collection [root] holds after [op]. */
    fun set(root: JsonObject, op: CanvasOp.SetPluginElementOp): JsonArray =
        update(root, op.elementId) { entry -> lww.write(entry, op.elementId, CanvasWriter.of(op), registersOf(op)) }

    /** The collection [root] holds after [op]; recorded even when the element is not there yet. */
    fun remove(root: JsonObject, op: CanvasOp.RemovePluginElementOp): JsonArray =
        update(root, op.elementId) { entry -> lww.remove(entry, op.elementId, CanvasWriter.of(op)) }

    /** The plugin elements on the board, ordered by id: tombstones, partial and undecodable entries skipped. */
    fun elementsOf(root: JsonObject): List<CanvasPluginElement> = typedEntries(root).mapNotNull(::decode)

    /** The entries that carry a type: what the board holds as elements, decodable or not. */
    fun typedEntries(root: JsonObject): List<JsonObject> = entries(root).filter { it[TYPE] != null }

    /** [entry] as an element, or null when it does not decode. */
    fun decode(entry: JsonObject): CanvasPluginElement? =
        runCatching { json.decodeFromJsonElement(CanvasPluginElement.serializer(), entry) }.getOrNull()

    private fun entries(root: JsonObject): List<JsonObject> = (root[KEY] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }

    private fun idOf(entry: JsonObject): String = (entry[CanvasLwwRegisters.ID] as? JsonPrimitive)?.content.orEmpty()

    private inline fun update(root: JsonObject, id: String, write: (JsonObject?) -> JsonObject): JsonArray {
        val byId = entries(root).associateBy(::idOf).toMutableMap()
        byId[id] = write(byId[id])
        return JsonArray(bounded(byId.values).sortedBy(::idOf))
    }

    /** Every live entry, and the newest [MAX_TOMBSTONES] tombstones, so removals cannot grow the board forever. */
    private fun bounded(entries: Collection<JsonObject>): List<JsonObject> {
        val (tombstones, live) = entries.partition(lww::isEmpty)
        val kept = tombstones.sortedWith(compareByDescending<JsonObject> { CanvasWriter.from(it) }.thenBy(::idOf)).take(MAX_TOMBSTONES)
        return live + kept
    }

    private fun registersOf(op: CanvasOp.SetPluginElementOp): Map<String, Map<String, JsonElement>> = buildMap {
        op.frame?.let { frame ->
            put(FRAME, mapOf(FRAME to encode(frame), OWNER to encode(op.owner ?: CanvasGeometryOwner.EXPLICIT)))
        }
        op.elementType?.let { type -> put(TYPE, mapOf(TYPE to JsonPrimitive(type), VERSION to JsonPrimitive(op.v ?: 1))) }
        op.ref?.let { put(REF, mapOf(REF to JsonPrimitive(it))) }
        op.props?.let { put(PROPS, mapOf(PROPS to it)) }
        op.snapshot?.let { put(SNAPSHOT, mapOf(SNAPSHOT to encode(it))) }
        op.fallback?.let { put(FALLBACK, mapOf(FALLBACK to encode(it))) }
        op.meta?.let { put(META, mapOf(META to it)) }
    }

    private fun encode(frame: CanvasDocumentFrame): JsonElement = json.encodeToJsonElement(CanvasDocumentFrame.serializer(), frame)

    private fun encode(owner: CanvasGeometryOwner): JsonElement = json.encodeToJsonElement(CanvasGeometryOwner.serializer(), owner)

    private fun encode(snapshot: CanvasPluginSnapshot): JsonElement = json.encodeToJsonElement(CanvasPluginSnapshot.serializer(), snapshot)

    private fun encode(fallback: CanvasPluginFallback): JsonElement = json.encodeToJsonElement(CanvasPluginFallback.serializer(), fallback)
}
