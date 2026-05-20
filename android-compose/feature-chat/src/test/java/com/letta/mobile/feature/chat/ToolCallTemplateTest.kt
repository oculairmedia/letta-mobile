package com.letta.mobile.feature.chat

import com.letta.mobile.data.model.Tool
import com.letta.mobile.data.model.ToolId
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

@Tag("unit")
class ToolCallTemplateTest {

    private fun tool(name: String, schema: JsonObject? = null) =
        Tool(id = ToolId("id-$name"), name = name, jsonSchema = schema)

    private fun prop(type: String) = JsonObject(mapOf("type" to JsonPrimitive(type)))

    @Test
    fun `falls back to flat template when jsonSchema is null`() {
        val out = buildToolCallTemplate(tool("fetch_url"))
        assertEquals("Call tool: fetch_url with parameters: ", out)
    }

    @Test
    fun `falls back to flat template when properties is absent`() {
        val schema = JsonObject(mapOf("type" to JsonPrimitive("object")))
        val out = buildToolCallTemplate(tool("noop", schema))
        assertEquals("Call tool: noop with parameters: ", out)
    }

    @Test
    fun `falls back to flat template when properties is an empty object`() {
        val schema = JsonObject(
            mapOf(
                "type" to JsonPrimitive("object"),
                "properties" to JsonObject(emptyMap()),
            ),
        )
        val out = buildToolCallTemplate(tool("noop", schema))
        assertEquals("Call tool: noop with parameters: ", out)
    }

    @Test
    fun `renders typed placeholders for each json schema type`() {
        val schema = JsonObject(
            mapOf(
                "type" to JsonPrimitive("object"),
                "properties" to JsonObject(
                    linkedMapOf(
                        "s" to prop("string"),
                        "n" to prop("number"),
                        "i" to prop("integer"),
                        "b" to prop("boolean"),
                        "a" to prop("array"),
                        "o" to prop("object"),
                    ),
                ),
            ),
        )
        val out = buildToolCallTemplate(tool("typed", schema))
        // Header
        assertTrue(out.startsWith("Call tool: typed\nArguments: {"))
        // Each type → correct placeholder
        assertTrue(out.contains("\"s\": \"\""))
        assertTrue(out.contains("\"n\": 0"))
        assertTrue(out.contains("\"i\": 0"))
        assertTrue(out.contains("\"b\": false"))
        assertTrue(out.contains("\"a\": []"))
        assertTrue(out.contains("\"o\": {}"))
        // Closing brace on its own line
        assertTrue(out.endsWith("\n}"))
    }

    @Test
    fun `unknown or missing type collapses to empty-string placeholder`() {
        val schema = JsonObject(
            mapOf(
                "type" to JsonPrimitive("object"),
                "properties" to JsonObject(
                    linkedMapOf(
                        "unknown_type" to JsonObject(mapOf("type" to JsonPrimitive("date"))),
                        "no_type" to JsonObject(emptyMap()),
                        "non_object" to JsonPrimitive("nope"),
                    ),
                ),
            ),
        )
        val out = buildToolCallTemplate(tool("loose", schema))
        assertTrue(out.contains("\"unknown_type\": \"\""))
        assertTrue(out.contains("\"no_type\": \"\""))
        assertTrue(out.contains("\"non_object\": \"\""))
    }

    @Test
    fun `preserves property insertion order from schema`() {
        val schema = JsonObject(
            mapOf(
                "type" to JsonPrimitive("object"),
                "properties" to JsonObject(
                    linkedMapOf(
                        "zeta" to prop("string"),
                        "alpha" to prop("string"),
                        "mu" to prop("string"),
                    ),
                ),
            ),
        )
        val out = buildToolCallTemplate(tool("ordered", schema))
        val zetaAt = out.indexOf("\"zeta\"")
        val alphaAt = out.indexOf("\"alpha\"")
        val muAt = out.indexOf("\"mu\"")
        assertTrue(zetaAt in 0 until alphaAt, "zeta should appear before alpha (got $zetaAt vs $alphaAt)")
        assertTrue(alphaAt in 0 until muAt, "alpha should appear before mu (got $alphaAt vs $muAt)")
    }

    @Test
    fun `properties value that is a non-object JsonElement still produces a placeholder`() {
        // Defensive: if upstream ever sends an array where we expected an
        // object descriptor, we should fall back to the string placeholder
        // rather than crash. Verify against JsonArray and JsonPrimitive shapes.
        val schema = JsonObject(
            mapOf(
                "type" to JsonPrimitive("object"),
                "properties" to JsonObject(
                    linkedMapOf(
                        "arr_descriptor" to JsonArray(listOf(JsonPrimitive("string"))),
                        "prim_descriptor" to JsonPrimitive(42),
                    ),
                ),
            ),
        )
        val out = buildToolCallTemplate(tool("weird", schema))
        assertTrue(out.contains("\"arr_descriptor\": \"\""))
        assertTrue(out.contains("\"prim_descriptor\": \"\""))
    }
}
