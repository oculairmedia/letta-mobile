package com.letta.mobile.data.model

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AgentSerializationCommonTest {
    @Test
    fun serializesAgentUpdateParamsWithServerWireKeys() {
        val params = AgentUpdateParams(
            modelSettings = ModelSettings(
                providerType = "openai",
                maxOutputTokens = 4096,
                parallelToolCalls = true,
                reasoningEffort = "high",
            ),
            responseFormat = buildJsonObject {
                put("type", JsonPrimitive("json_object"))
            },
            parallelToolCalls = false,
            compactionSettings = CompactionSettings(
                prompt = "Keep decisions, tasks, and blockers.",
                promptAcknowledgement = true,
                clipChars = 24_000,
            ),
        )

        val root = Json.parseToJsonElement(Json.encodeToString(params)).jsonObject

        assertTrue("model_settings" in root)
        assertTrue("response_format" in root)
        assertTrue("parallel_tool_calls" in root)
        assertTrue("compaction_settings" in root)
        assertFalse("modelSettings" in root)
        assertFalse("responseFormat" in root)
        assertFalse("parallelToolCalls" in root)
        assertFalse("compactionSettings" in root)
        assertEquals(false, root["parallel_tool_calls"]?.jsonPrimitive?.boolean)

        val modelSettings = root["model_settings"]!!.jsonObject
        assertEquals("openai", modelSettings["provider_type"]?.jsonPrimitive?.contentOrNull)
        assertEquals(4096, modelSettings["max_output_tokens"]?.jsonPrimitive?.int)
        assertEquals(true, modelSettings["parallel_tool_calls"]?.jsonPrimitive?.boolean)
        assertEquals("high", modelSettings["reasoning_effort"]?.jsonPrimitive?.contentOrNull)

        val compaction = root["compaction_settings"]!!.jsonObject
        assertEquals("Keep decisions, tasks, and blockers.", compaction["prompt"]?.jsonPrimitive?.contentOrNull)
        assertEquals(true, compaction["prompt_acknowledgement"]?.jsonPrimitive?.boolean)
        assertEquals(24_000, compaction["clip_chars"]?.jsonPrimitive?.int)
        assertFalse("promptAcknowledgement" in compaction)
        assertFalse("clipChars" in compaction)
    }
}
