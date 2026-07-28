package com.letta.mobile.data.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

class AppServerListModelsAdapterTest {
    @Test
    fun mapsPresentationEntryIntoLlmModelShape() {
        val adapted = AppServerListModelsAdapter.toLlmModelArray(
            JsonArray(
                listOf(
                    buildJsonObject {
                        put("id", "model-1")
                        put("handle", "anthropic/claude-fable-5")
                        put("label", "Claude Fable 5")
                        put("description", "presentation only")
                    },
                ),
            ),
        )
        val model = adapted.single().jsonObject
        assertEquals("model-1", model["id"]!!.jsonPrimitive.content)
        assertEquals("anthropic/claude-fable-5", model["handle"]!!.jsonPrimitive.content)
        assertEquals("Claude Fable 5", model["display_name"]!!.jsonPrimitive.content)
        assertEquals("anthropic", model["provider_type"]!!.jsonPrimitive.content)
        assertNull(model["context_window"])
    }
}
