package com.letta.mobile.data.shell.layout.edit

import com.letta.mobile.data.shell.layout.edit.ShellLayoutToolInput.ACTION
import com.letta.mobile.data.shell.layout.edit.ShellLayoutToolInput.DRY_RUN
import com.letta.mobile.data.shell.layout.edit.ShellLayoutToolInput.INDEX
import com.letta.mobile.data.shell.layout.edit.ShellLayoutToolInput.LANE
import com.letta.mobile.data.shell.layout.edit.ShellLayoutToolInput.OBJECT
import com.letta.mobile.data.shell.layout.edit.ShellLayoutToolInput.OP
import com.letta.mobile.data.shell.layout.edit.ShellLayoutToolInput.OPS
import com.letta.mobile.data.shell.layout.edit.ShellLayoutToolInput.SLOTS
import com.letta.mobile.data.shell.layout.edit.ShellLayoutToolInput.TO_LANE
import com.letta.mobile.data.shell.layout.edit.ShellLayoutToolInput.VIEW
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/** The `shell_layout` tool's JSON input schema: strict at every level, as [ShellLayoutToolInput] reads it. */
object ShellLayoutSchema {
    private fun strings(values: List<String>) = JsonArray(values.map(::JsonPrimitive))

    private fun stringProperty(description: String, values: List<String>? = null) = buildJsonObject {
        put("type", "string")
        put("description", description)
        values?.let { put("enum", strings(it)) }
    }

    private fun objectSchema(properties: JsonObject, required: List<String>) = buildJsonObject {
        put("type", "object")
        put("properties", properties)
        put("required", strings(required))
        put("additionalProperties", false)
    }

    private val laneIds = ShellLayoutCatalog.lanes.map { it.lane }

    private val slot = objectSchema(
        buildJsonObject {
            put(VIEW, stringProperty("A view id from action get's catalog."))
            put(OBJECT, stringProperty("The object the view shows (the same canvas id for shell.canvas or shell.note); omit otherwise."))
        },
        required = listOf(VIEW),
    )

    private val op = objectSchema(
        buildJsonObject {
            put(OP, stringProperty("The op.", ShellLayoutToolInput.opNames))
            put(LANE, stringProperty("The lane the op works on.", laneIds))
            put(VIEW, stringProperty("add, remove, move: the slot's view id."))
            put(OBJECT, stringProperty("add, remove, move: the slot's object, when its view needs one."))
            put(TO_LANE, stringProperty("move: the lane it goes to (default: the same lane).", laneIds))
            putJsonObject(INDEX) {
                put("type", "integer")
                put("minimum", 0)
                put("description", "add, move: the position, 0 first (default: the end).")
            }
            putJsonObject(SLOTS) {
                put("type", "array")
                put("description", "set_lane: every slot of the lane, in order.")
                put("items", slot)
            }
        },
        required = listOf(OP),
    )

    /** The input schema the model sees. */
    val input: JsonObject = objectSchema(
        buildJsonObject {
            put(ACTION, stringProperty("What to do.", ShellLayoutAction.entries.map { it.wire }))
            putJsonObject(OPS) {
                put("type", "array")
                put("description", "apply: the ops, in order; all are applied or none.")
                put("maxItems", ShellLayoutEdits.MAX_OPS)
                put("items", op)
            }
            putJsonObject(DRY_RUN) {
                put("type", "boolean")
                put("description", "apply: true checks the ops and returns the receipt without changing anything.")
            }
        },
        required = listOf(ACTION),
    )
}
