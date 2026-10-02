package com.letta.mobile.data.canvas

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull

/** Who wrote something, in the one total order of the op log ([CanvasOpOrder]). */
internal data class CanvasWriter(val lamport: Long, val actorId: String, val opId: String) : Comparable<CanvasWriter> {
    override fun compareTo(other: CanvasWriter): Int =
        CanvasOpOrder.compare(lamport, actorId, opId, other.lamport, other.actorId, other.opId)

    fun toJson(): JsonObject = buildJsonObject {
        put(LAMPORT, JsonPrimitive(lamport))
        put(ACTOR, JsonPrimitive(actorId))
        put(OP_ID, JsonPrimitive(opId))
    }

    companion object {
        const val LAMPORT = "_lamport"
        const val ACTOR = "_actorId"
        const val OP_ID = "_opId"

        fun of(op: CanvasOp): CanvasWriter = CanvasWriter(op.lamport, op.actorId, op.opId)

        /** The writer recorded in [value] (an object with `_lamport`, `_actorId`, `_opId`), or null. */
        fun from(value: JsonElement?): CanvasWriter? {
            val obj = value as? JsonObject ?: return null
            val lamport = (obj[LAMPORT] as? JsonPrimitive)?.longOrNull ?: return null
            return CanvasWriter(lamport, obj[ACTOR].text(), obj[OP_ID].text())
        }

        private fun JsonElement?.text(): String = (this as? JsonPrimitive)?.contentOrNull.orEmpty()
    }
}

/**
 * One entry of a scene-root collection kept as named last-writer-wins registers, so writers that
 * touch different parts of it never overwrite each other (letta-mobile-bglj6.16's per-property LWW;
 * plugin elements are its first user, and block documents can adopt it).
 *
 * Each register holds some of the entry's fields and the writer that last set them, under
 * `_clock.<register>`. A write names the registers it sets; each one it names is settled on its
 * own, against that register's writer, and the registers it does not name keep what they have.
 * Every register is the latest of its own writes, so two peers reach the same entry whatever
 * order the writes arrive in.
 *
 * A removal is a write to every register at once: it clears each one older than itself and is
 * kept as `_removed`, so a write older than the removal that arrives late still loses. A write
 * newer than the removal brings back what it names.
 *
 * [groups] are provenance summaries for readers (the newest writer of each group's registers,
 * under the group's key), and the entry-level `_lamport`/`_actorId`/`_opId` is the newest of all.
 */
internal class CanvasLwwRegisters(
    /** Register name to the entry fields it holds. */
    private val registers: Map<String, List<String>>,
    /** Summary key (e.g. `_frame`) to the registers it summarises. */
    private val groups: Map<String, List<String>>,
) {
    /** [entry] (null for a new one) after [writer] sets [values], register name to its fields. */
    fun write(entry: JsonObject?, id: String, writer: CanvasWriter, values: Map<String, Map<String, JsonElement>>): JsonObject {
        val state = parse(entry)
        if (state.removedAfter(writer)) return state.render(id)
        values.forEach { (register, fields) -> state.offer(register, fields, writer) }
        return state.render(id)
    }

    /** [entry] after [writer] removes it: every register older than the removal is cleared. */
    fun remove(entry: JsonObject?, id: String, writer: CanvasWriter): JsonObject {
        val state = parse(entry)
        if (state.removedAfter(writer)) return state.render(id)
        state.clocks.filterValues { it < writer }.keys.forEach(state::clear)
        state.removed = writer
        return state.render(id)
    }

    /** Whether [entry] holds no register at all: a tombstone, or nothing. */
    fun isEmpty(entry: JsonObject): Boolean = parse(entry).clocks.isEmpty()

    private fun parse(entry: JsonObject?): Registers {
        val clockTable = entry?.get(CLOCK) as? JsonObject
        val clocks = registers.keys.mapNotNull { name -> CanvasWriter.from(clockTable?.get(name))?.let { name to it } }.toMap()
        val values = clocks.keys.associateWith { register ->
            registers.getValue(register).mapNotNull { field -> entry?.get(field)?.let { field to it } }.toMap()
        }
        return Registers(values.toMutableMap(), clocks.toMutableMap(), CanvasWriter.from(entry?.get(REMOVED)))
    }

    private inner class Registers(
        val values: MutableMap<String, Map<String, JsonElement>>,
        val clocks: MutableMap<String, CanvasWriter>,
        var removed: CanvasWriter?,
    ) {
        fun removedAfter(writer: CanvasWriter): Boolean = removed?.let { writer < it } == true

        /** Sets [register] unless it already holds a newer write. */
        fun offer(register: String, fields: Map<String, JsonElement>, writer: CanvasWriter) {
            if (clocks[register]?.let { writer < it } == true) return
            values[register] = fields
            clocks[register] = writer
        }

        fun clear(register: String) {
            values.remove(register)
            clocks.remove(register)
        }

        fun render(id: String): JsonObject = buildJsonObject {
            put(ID, JsonPrimitive(id))
            values.values.flatMap { it.entries }.sortedBy { it.key }.forEach { (key, value) -> put(key, value) }
            if (clocks.isNotEmpty()) put(CLOCK, JsonObject(clocks.entries.sortedBy { it.key }.associate { it.key to it.value.toJson() }))
            groups.entries.sortedBy { it.key }.forEach { (key, members) ->
                members.mapNotNull(clocks::get).maxOrNull()?.let { put(key, it.toJson()) }
            }
            removed?.let { put(REMOVED, it.toJson()) }
            (clocks.values + listOfNotNull(removed)).maxOrNull()?.toJson()?.forEach { (key, value) -> put(key, value) }
        }
    }

    companion object {
        const val ID = "id"
        const val CLOCK = "_clock"
        const val REMOVED = "_removed"
    }
}
