package com.letta.mobile.data.model

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * letta-mobile-i2f23: serialization round-trip for the [SubagentEntry]
 * `todo_progress` field, proving the nullable-with-default contract works
 * both when the field is present AND when it is absent (older shims).
 */
class SubagentEntrySerializationCommonTest {

    private val json = Json { ignoreUnknownKeys = true }

    // ── Round-trip with todo_progress present ─────────────────────────────

    @Test
    fun `serializes SubagentTodoProgressWire with correct wire keys`() {
        val progress = SubagentTodoProgressWire(completed = 3, total = 7)
        val encoded = Json.encodeToString(progress)
        val root = Json.parseToJsonElement(encoded).jsonObject

        assertEquals(3, root["completed"]?.jsonPrimitive?.int)
        assertEquals(7, root["total"]?.jsonPrimitive?.int)
    }

    @Test
    fun `round-trips SubagentEntry with todo_progress present`() {
        val entry = SubagentEntry(
            toolCallId = "toolu_abc",
            description = "test",
            subagentType = "general",
            status = SubagentStatus.RUNNING,
            todoProgress = SubagentTodoProgressWire(completed = 2, total = 5),
        )

        val encoded = Json.encodeToString(entry)
        val decoded = json.decodeFromString<SubagentEntry>(encoded)

        assertEquals("toolu_abc", decoded.toolCallId)
        assertNotNull(decoded.todoProgress)
        assertEquals(2, decoded.todoProgress!!.completed)
        assertEquals(5, decoded.todoProgress!!.total)
    }

    @Test
    fun `wire key is todo_progress not camelCase`() {
        val entry = SubagentEntry(
            toolCallId = "t1",
            status = SubagentStatus.RUNNING,
            todoProgress = SubagentTodoProgressWire(completed = 1, total = 3),
        )

        val encoded = Json.encodeToString(entry)
        assertTrue("\"todo_progress\"" in encoded, "Expected todo_progress in: $encoded")
    }

    // ── Missing todo_progress (older shim compat) ─────────────────────────

    @Test
    fun `missing todo_progress defaults to null`() {
        val raw = """{
            "toolCallId": "toolu_old",
            "description": "legacy shim entry",
            "subagentType": "general",
            "status": "running"
        }""".trimIndent()

        val decoded = json.decodeFromString<SubagentEntry>(raw)
        assertEquals("toolu_old", decoded.toolCallId)
        assertEquals("running", decoded.status)
        assertNull(decoded.todoProgress, "todo_progress must be null when absent")
    }

    @Test
    fun `todo_progress with zero values parses correctly`() {
        val raw = """
        {
            "toolCallId": "toolu_z",
            "status": "running",
            "todo_progress": { "completed": 0, "total": 0 }
        }
        """.trimIndent()

        val decoded = json.decodeFromString<SubagentEntry>(raw)
        assertNotNull(decoded.todoProgress)
        assertEquals(0, decoded.todoProgress!!.completed)
        assertEquals(0, decoded.todoProgress!!.total)
    }

    // ── Serialization within the parent frame shape ───────────────────────

    @Test
    fun `todo_progress survives encode-decode round-trip within parent frame`() {
        // Build a payload the shim would send for a subagent entry inside a
        // subagents_updated or subagent_list_response frame.
        val raw = buildJsonObject {
            put("toolCallId", "toolu_1")
            put("status", "running")
            putJsonObject("todo_progress") {
                put("completed", 2)
                put("total", 4)
            }
        }

        val encoded = raw.toString()
        val decoded = json.decodeFromString<SubagentEntry>(encoded)

        assertEquals("toolu_1", decoded.toolCallId)
        assertNotNull(decoded.todoProgress)
        assertEquals(2, decoded.todoProgress!!.completed)
        assertEquals(4, decoded.todoProgress!!.total)
    }

    @Test
    fun `SubagentTodoProgressWire encodes with numeric values`() {
        val wire = SubagentTodoProgressWire(completed = 0, total = 5)
        val encoded = Json.encodeToString(wire)

        // JSON standard allows optional whitespace; just verify both fields
        // are present and carry the right numeric values after a round-trip.
        val decoded = json.decodeFromString<SubagentTodoProgressWire>(encoded)
        assertEquals(0, decoded.completed)
        assertEquals(5, decoded.total)
    }
}
