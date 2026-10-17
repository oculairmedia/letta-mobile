package com.letta.mobile.data.model

import com.letta.mobile.data.transport.appserver.AppServerProtocol
import kotlinx.collections.immutable.persistentListOf
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The compact profile is picked from the model's metadata, the same on every platform. */
class CompactAgentProfileTest {
    private val onDevice = LlmModel(
        id = "google/gemma-3n-E2B-it-litert-lm",
        handle = "lmstudio/google/gemma-3n-E2B-it-litert-lm",
        contextWindow = 128_000,
    )
    private val big = LlmModel(id = "gpt-5", handle = "openai/gpt-5", contextWindow = 400_000)

    @Test
    fun onDeviceAndTinyModelsAreSmall() {
        listOf(
            "lmstudio/google/gemma-3n-E2B-it-litert-lm",
            "google/gemma-3n-E4B-it-litert-lm",
            "gemma3-1b-it.task",
            "lmstudio/meta/llama-3.2-1b-instruct",
            "ollama/qwen2.5:0.5b",
            "lmstudio/qwen2.5-3b-instruct",
        ).forEach { assertTrue(CompactAgentProfile.isSmallModelName(it), it) }
    }

    @Test
    fun capableModelsAreNotSmall() {
        listOf(
            "openai/gpt-5",
            "anthropic/claude-sonnet-4-5",
            "lmstudio/minimax-m3",
            "ollama/qwen3:8b",
            "groq/llama-3.1-70b-versatile",
            "lmstudio/qwen3-14b",
        ).forEach { assertFalse(CompactAgentProfile.isSmallModelName(it), it) }
    }

    @Test
    fun aSmallContextWindowMakesAnyModelSmall() {
        assertTrue(CompactAgentProfile.isSmallModel(big.copy(contextWindow = 8_192)))
        assertTrue(CompactAgentProfile.isSmallModel(big.copy(contextWindow = CompactAgentProfile.SMALL_CONTEXT_WINDOW)))
        assertFalse(CompactAgentProfile.isSmallModel(big))
        assertTrue(CompactAgentProfile.isSmallModel(onDevice), "an on-device name wins over a generic default window")
    }

    @Test
    fun aCompactCreateRequestHasTheShortPromptTheTagsAndNoTools() {
        val params = CompactAgentProfile.applyTo(
            AgentCreateParams(
                name = "bobs",
                model = onDevice.handle,
                toolIds = persistentListOf(ToolId("t-1")),
                tags = persistentListOf("team"),
                includeBaseTools = true,
            ),
        )
        val body = AppServerProtocol.json.encodeToJsonElement(AgentCreateParams.serializer(), params).jsonObject

        assertEquals(JsonPrimitive(CompactAgentProfile.SYSTEM_PROMPT), body["system"])
        assertEquals(
            listOf("team", CompactAgentProfile.TAG, CompactAgentProfile.MEMFS_DISABLED_TAG),
            (body["tags"] as JsonArray).map { (it as JsonPrimitive).content },
        )
        assertEquals(JsonPrimitive(false), body["include_base_tools"])
        assertNull(body["tool_ids"])
        assertNull(body["tools"])
        assertTrue(CompactAgentProfile.SYSTEM_PROMPT.length in 400..800)
    }

    @Test
    fun aWrittenPromptIsKeptAndTheMarkerComesOff() {
        val params = CompactAgentProfile.applyTo(AgentCreateParams(name = "a", system = "You are Bob."))
        assertEquals("You are Bob.", params.system)
        assertEquals(listOf("team"), CompactAgentProfile.tagsWith(listOf("team", CompactAgentProfile.TAG), compact = false))
        assertTrue(CompactAgentProfile.isCompact(params.tags))
    }
}
