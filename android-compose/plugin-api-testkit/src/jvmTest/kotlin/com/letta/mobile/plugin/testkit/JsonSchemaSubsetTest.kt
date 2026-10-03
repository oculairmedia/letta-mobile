package com.letta.mobile.plugin.testkit

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals

class JsonSchemaSubsetTest {
    private fun schema(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject

    private val job = schema(
        """{"type":"object","additionalProperties":false,"required":["label","count"],"properties":{
           "label":{"type":"string","minLength":2,"maxLength":4},
           "count":{"type":"integer","minimum":1,"maximum":3},
           "status":{"enum":["idle","done"]},
           "tags":{"type":"array","maxItems":1,"items":{"type":"string"}}}}""",
    )

    @Test
    fun `a sample holds its schema with the required fields only`() {
        val sample = JsonSchemaSubset.sample(job)
        assertEquals("""{"label":"xx","count":1}""", sample.toString())
        assertEquals(emptyList(), JsonSchemaSubset.problems(job, sample))
    }

    @Test
    fun `problems are at their pointers`() {
        val value = Json.parseToJsonElement("""{"label":"x","count":9,"status":"busy","tags":[1,"a"],"extra":true}""")
        assertEquals(
            listOf(
                "/extra: is not a declared field",
                "/label: shorter than 2",
                "/count: above 3.0",
                "/status: \"busy\" is not one of [\"idle\",\"done\"]",
                "/tags: more than 1 items",
                "/tags/0: expected string",
            ),
            JsonSchemaSubset.problems(job, value),
        )
    }

    @Test
    fun `a missing required field and a wrong type`() {
        assertEquals(listOf("/count: is required"), JsonSchemaSubset.problems(job, Json.parseToJsonElement("""{"label":"ab"}""")))
        assertEquals(listOf("(root): expected object"), JsonSchemaSubset.problems(job, Json.parseToJsonElement("[]")))
    }
}
