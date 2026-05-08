package com.letta.mobile.data.model

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.jupiter.api.Tag

@Tag("integration")
class AgentUpdateParamsSerializationTest {

    private val json = Json

    @Test
    fun `agent update advanced settings serialize with server wire keys and typed values`() {
        val params = AgentUpdateParams(
            model = "openai/gpt-5-mini",
            modelSettings = ModelSettings(
                providerType = "openai",
                providerName = "OpenAI",
                providerCategory = "cloud",
                maxOutputTokens = 4096,
                parallelToolCalls = true,
                enableReasoner = true,
                maxReasoningTokens = 2048,
                reasoningEffort = "high",
                reasoning = buildJsonObject {
                    put("summary", JsonPrimitive("auto"))
                },
                responseFormat = buildJsonObject {
                    put("type", JsonPrimitive("json_schema"))
                },
                responseSchema = buildJsonObject {
                    put("name", JsonPrimitive("handoff_summary"))
                },
                thinkingConfig = buildJsonObject {
                    put("budget_tokens", JsonPrimitive(1024))
                },
                strict = true,
                toolCallParser = "openai-tools",
                putInnerThoughtsInKwargs = true,
                effort = "medium",
            ),
            responseFormat = buildJsonObject {
                put("type", JsonPrimitive("json_object"))
            },
            parallelToolCalls = false,
            toolRules = listOf(
                buildJsonObject {
                    put("type", JsonPrimitive("requires_approval"))
                    put("tool_name", JsonPrimitive("shell"))
                },
                buildJsonObject {
                    put("type", JsonPrimitive("max_count_per_step"))
                    put("tool_name", JsonPrimitive("web_search"))
                    put("max_count_limit", JsonPrimitive(2))
                },
            ),
            compactionSettings = CompactionSettings(
                model = "anthropic/claude-3-5-sonnet",
                modelSettings = buildJsonObject {
                    put("max_output_tokens", JsonPrimitive(1024))
                },
                prompt = "Keep decisions, tasks, and blockers.",
                promptAcknowledgement = true,
                clipChars = 24_000,
                mode = "self_compact_all",
                slidingWindowPercentage = 0.35,
            ),
        )

        val root = Json.parseToJsonElement(json.encodeToString(params)).jsonObject

        assertTrue("model_settings key must be present", "model_settings" in root)
        assertTrue("response_format key must be present", "response_format" in root)
        assertTrue("parallel_tool_calls key must be present", "parallel_tool_calls" in root)
        assertTrue("tool_rules key must be present", "tool_rules" in root)
        assertTrue("compaction_settings key must be present", "compaction_settings" in root)
        assertFalse("camelCase modelSettings must not leak", "modelSettings" in root)
        assertFalse("camelCase responseFormat must not leak", "responseFormat" in root)
        assertFalse("camelCase parallelToolCalls must not leak", "parallelToolCalls" in root)
        assertFalse("camelCase toolRules must not leak", "toolRules" in root)
        assertFalse("camelCase compactionSettings must not leak", "compactionSettings" in root)
        assertEquals(false, root["parallel_tool_calls"]?.jsonPrimitive?.boolean)

        val toolRules = root["tool_rules"]!!.jsonArray
        assertEquals(2, toolRules.size)
        assertEquals("requires_approval", toolRules[0].jsonObject["type"]?.jsonPrimitive?.contentOrNull)
        assertEquals("shell", toolRules[0].jsonObject["tool_name"]?.jsonPrimitive?.contentOrNull)
        assertEquals("max_count_per_step", toolRules[1].jsonObject["type"]?.jsonPrimitive?.contentOrNull)
        assertEquals(2, toolRules[1].jsonObject["max_count_limit"]?.jsonPrimitive?.int)

        val modelSettings = root["model_settings"]!!.jsonObject
        assertEquals("openai", modelSettings["provider_type"]?.jsonPrimitive?.contentOrNull)
        assertEquals("OpenAI", modelSettings["provider_name"]?.jsonPrimitive?.contentOrNull)
        assertEquals("cloud", modelSettings["provider_category"]?.jsonPrimitive?.contentOrNull)
        assertEquals(4096, modelSettings["max_output_tokens"]?.jsonPrimitive?.int)
        assertEquals(true, modelSettings["parallel_tool_calls"]?.jsonPrimitive?.boolean)
        assertEquals(true, modelSettings["enable_reasoner"]?.jsonPrimitive?.boolean)
        assertEquals(2048, modelSettings["max_reasoning_tokens"]?.jsonPrimitive?.int)
        assertEquals("high", modelSettings["reasoning_effort"]?.jsonPrimitive?.contentOrNull)
        assertEquals(true, modelSettings["strict"]?.jsonPrimitive?.boolean)
        assertEquals("openai-tools", modelSettings["tool_call_parser"]?.jsonPrimitive?.contentOrNull)
        assertEquals(true, modelSettings["put_inner_thoughts_in_kwargs"]?.jsonPrimitive?.boolean)
        assertEquals("medium", modelSettings["effort"]?.jsonPrimitive?.contentOrNull)
        assertJsonObjectKey(modelSettings, "reasoning", "summary")
        assertJsonObjectKey(modelSettings, "response_format", "type")
        assertJsonObjectKey(modelSettings, "response_schema", "name")
        assertJsonObjectKey(modelSettings, "thinking_config", "budget_tokens")

        val responseFormat = root["response_format"]!!.jsonObject
        assertEquals("json_object", responseFormat["type"]?.jsonPrimitive?.contentOrNull)

        val compaction = root["compaction_settings"]!!.jsonObject
        assertEquals("anthropic/claude-3-5-sonnet", compaction["model"]?.jsonPrimitive?.contentOrNull)
        assertJsonObjectKey(compaction, "model_settings", "max_output_tokens")
        assertEquals("Keep decisions, tasks, and blockers.", compaction["prompt"]?.jsonPrimitive?.contentOrNull)
        assertEquals(true, compaction["prompt_acknowledgement"]?.jsonPrimitive?.boolean)
        assertEquals(24_000, compaction["clip_chars"]?.jsonPrimitive?.int)
        assertEquals("self_compact_all", compaction["mode"]?.jsonPrimitive?.contentOrNull)
        assertEquals(0.35, compaction["sliding_window_percentage"]!!.jsonPrimitive.double, 0.001)
        assertFalse("camelCase promptAcknowledgement must not leak", "promptAcknowledgement" in compaction)
        assertFalse("camelCase clipChars must not leak", "clipChars" in compaction)
        assertFalse("camelCase slidingWindowPercentage must not leak", "slidingWindowPercentage" in compaction)
    }

    private fun assertJsonObjectKey(parent: JsonObject, objectKey: String, childKey: String) {
        val child = parent[objectKey]
        assertTrue("$objectKey must serialize as a JSON object", child is JsonObject)
        assertTrue("$objectKey must contain $childKey", childKey in child!!.jsonObject)
    }
}
