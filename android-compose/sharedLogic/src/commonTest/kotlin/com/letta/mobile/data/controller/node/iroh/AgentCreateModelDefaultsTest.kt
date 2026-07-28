package com.letta.mobile.data.controller.node.iroh

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

class AgentCreateModelDefaultsTest {
    @Test
    fun knownLimitsRequireLlmuxRouteProvenance() {
        val llmux = request("openai/MiniMax-M3", providerName = "llmux-openai")
            .withDefaultContextWindow()
        assertEquals("200000", llmux["context_window_limit"]?.jsonPrimitive?.content)
        assertEquals(
            "16384",
            llmux["model_settings"]?.jsonObject?.get("max_output_tokens")?.jsonPrimitive?.content,
        )

        val independent = listOf(
            request(
                "openai/MiniMax-M3",
                providerName = "customer-openai",
                endpoint = "https://custom.example/v1",
                category = "byok",
            ),
            request("azure/MiniMax-M3"),
        )
        independent.forEach { body ->
            val enriched = body.withDefaultContextWindow()
            assertNull(enriched["context_window_limit"])
            assertNull(enriched["model_settings"])
        }
    }

    @Test
    fun unknownModelsRetainGenericContextDefault() {
        val enriched = request("openai/custom-model").withDefaultContextWindow()
        assertEquals("200000", enriched["context_window_limit"]?.jsonPrimitive?.content)
    }

    private fun request(
        handle: String,
        providerName: String? = null,
        endpoint: String? = null,
        category: String? = null,
    ): JsonObject = buildJsonObject {
        put("model", handle)
        providerName?.let { put("provider_name", it) }
        endpoint?.let { put("model_endpoint", it) }
        category?.let { put("provider_category", it) }
    }
}
