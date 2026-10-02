package com.letta.mobile.data.schema

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The strict JSON-Schema subset lifted from `canvas_compose` (letta-mobile-s416w.2): every keyword of
 * the vocabulary refuses at the JSON pointer of the value that breaks it. Compose's own wording is
 * covered by CanvasComposeSchemaTest, unchanged.
 */
class JsonSchemaCheckTest {
    private fun schema(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject

    private fun json(text: String): JsonElement = Json.parseToJsonElement(text)

    private fun problems(schema: JsonObject, instance: String, discriminator: String? = null) =
        JsonSchemaCheck(schema, discriminator).check(json(instance)).map { it.path to it.code }

    private val widget = schema(
        """
        {"type":"object","additionalProperties":false,"required":["status"],"properties":{
          "status":{"enum":["idle","running","done","failed"]},
          "progress":{"type":"number","minimum":0,"maximum":1},
          "count":{"type":"integer"},
          "label":{"type":"string","maxLength":8,"minLength":2,"pattern":"^[a-z ]+$"},
          "tags":{"type":"array","items":{"type":"string"},"minItems":1,"maxItems":2},
          "a/b~c":{"type":"boolean"}}}
        """,
    )

    @Test
    fun aValueThatHoldsHasNoProblems() {
        assertEquals(emptyList(), problems(widget, """{"status":"idle","progress":0.5,"count":3,"label":"ok","tags":["x"],"a/b~c":true}"""))
    }

    @Test
    fun eachKeywordRefusesAtThePointerOfTheValue() {
        val cases = mapOf(
            """{"status":"queued"}""" to listOf("/status" to "NOT_ALLOWED"),
            """{}""" to listOf("/status" to "MISSING_FIELD"),
            """{"status":"idle","extra":1}""" to listOf("/extra" to "UNKNOWN_FIELD"),
            """{"status":"idle","progress":"half"}""" to listOf("/progress" to "WRONG_TYPE"),
            """{"status":"idle","progress":1.5}""" to listOf("/progress" to "OUT_OF_RANGE"),
            """{"status":"idle","progress":-1}""" to listOf("/progress" to "OUT_OF_RANGE"),
            """{"status":"idle","count":1.5}""" to listOf("/count" to "WRONG_TYPE"),
            """{"status":"idle","label":"much too long"}""" to listOf("/label" to "TOO_LONG"),
            """{"status":"idle","label":"x"}""" to listOf("/label" to "TOO_SHORT"),
            """{"status":"idle","label":"NO"}""" to listOf("/label" to "BAD_PATTERN"),
            """{"status":"idle","tags":[]}""" to listOf("/tags" to "TOO_FEW_ITEMS"),
            """{"status":"idle","tags":["a","b","c"]}""" to listOf("/tags" to "TOO_MANY_ITEMS"),
            """{"status":"idle","tags":["a",2]}""" to listOf("/tags/1" to "WRONG_TYPE"),
            """{"status":"idle","a/b~c":"yes"}""" to listOf("/a~1b~0c" to "WRONG_TYPE"),
            """[]""" to listOf("" to "WRONG_TYPE"),
        )
        cases.forEach { (instance, expected) -> assertEquals(expected, problems(widget, instance), instance) }
    }

    @Test
    fun anAnyOfWithADiscriminatorReportsAgainstTheBranchMeant() {
        val shapes = schema(
            """
            {"anyOf":[
              {"type":"object","additionalProperties":false,"required":["kind","r"],"properties":{"kind":{"type":"string","enum":["CIRCLE"]},"r":{"type":"number"}}},
              {"type":"object","additionalProperties":false,"required":["kind","w"],"properties":{"kind":{"type":"string","enum":["SQUARE"]},"w":{"type":"number"}}}]}
            """,
        )
        assertEquals(emptyList(), problems(shapes, """{"kind":"SQUARE","w":2}""", "kind"))
        assertEquals(listOf("/w" to "MISSING_FIELD", "/r" to "UNKNOWN_FIELD"), problems(shapes, """{"kind":"SQUARE","r":2}""", "kind"))
        assertEquals(listOf("/kind" to "NO_BRANCH"), problems(shapes, """{"kind":"OVAL"}""", "kind"))
        assertEquals(listOf("/kind" to "MISSING_FIELD"), problems(shapes, """{"r":2}""", "kind"))
        // Without a discriminator the value must hold some branch entirely.
        assertEquals(emptyList(), problems(shapes, """{"kind":"CIRCLE","r":2}"""))
        assertEquals(listOf("" to "NO_BRANCH"), problems(shapes, """{"kind":"CIRCLE","w":2}"""))
    }

    @Test
    fun theProblemsStartAtTheGivenPath() {
        val found = JsonSchemaCheck(widget).check(json("""{"status":"queued"}"""), path = "/props")
        assertEquals(listOf("/props/status"), found.map { it.path })
    }

    @Test
    fun theExampleBuiltFromASchemaHoldsIt() {
        val example = JsonSchemaExample.of(widget)
        assertEquals(emptyList(), JsonSchemaCheck(widget).check(example), "$example")
        assertEquals(json("""{"status":"idle","progress":0.0,"count":0,"label":"text","tags":["text"],"a/b~c":false}"""), example)
    }

    private val map = schema(
        """
        {"type":"object","maxProperties":2,"propertyNames":{"type":"string","pattern":"^[a-z]+$","maxLength":5},
         "additionalProperties":{"type":"integer","minimum":0}}
        """,
    )

    @Test
    fun aMapHoldsEveryKeyToPropertyNamesAndEveryValueToAdditionalProperties() {
        assertEquals(emptyList(), problems(map, """{"a":1,"b":2}"""))
        assertEquals(listOf("/Bad" to "BAD_PATTERN"), problems(map, """{"Bad":1}"""))
        assertEquals(listOf("/toolong" to "TOO_LONG"), problems(map, """{"toolong":1}"""))
        assertEquals(listOf("/a" to "OUT_OF_RANGE"), problems(map, """{"a":-1}"""))
        assertEquals(listOf("" to "TOO_MANY_ITEMS"), problems(map, """{"a":1,"b":2,"c":3}"""))
    }
}
