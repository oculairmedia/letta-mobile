package com.letta.mobile.data.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ModelCatalogTest {

    @Test
    fun testValueOf() {
        val modelWithHandle = LlmModel(id = "1", name = "Test Name", handle = "test-handle")
        assertEquals("test-handle", ModelCatalog.valueOf(modelWithHandle))

        val modelWithName = LlmModel(id = "2", name = "Test Name", handle = " ")
        assertEquals("Test Name", ModelCatalog.valueOf(modelWithName))

        val modelWithIdOnly = LlmModel(id = "test-id", name = "", handle = null)
        assertEquals("test-id", ModelCatalog.valueOf(modelWithIdOnly))
    }

    @Test
    fun testGroup() {
        val models = listOf(
            LlmModel(id = "1", name = "Claude", handle = "claude-3", providerType = "anthropic"),
            LlmModel(id = "2", name = "Local Llama", handle = "llama", providerType = "ollama", contextWindow = 8500),
            LlmModel(id = "3", name = "BYOK Model", handle = "byok-1", providerType = "openai", providerCategory = "byok", enableReasoner = true),
            LlmModel(id = "4", name = "Unknown Model", handle = "unknown", providerType = "custom"),
            LlmModel(id = "5", name = "No Provider", handle = "no-provider"),
            LlmModel(id = "6", name = "Big Context", handle = "big", providerType = "anthropic", contextWindow = 2_000_000),
            LlmModel(id = "7", name = "Small Context", handle = "small", providerType = "anthropic", contextWindow = 500)
        )

        val groups = ModelCatalog.group(models)

        // Providers expected:
        // "anthropic" -> "Anthropic" (sorts to 'anthropic')
        // "custom" -> "Custom" (sorts to 'custom')
        // "openai" -> "OpenAI" (sorts to 'openai')
        // no provider -> "Other" (sorts to 'zzy_other')
        // "ollama" -> "Local · On-device" (sorts to 'zzz_local')
        // Sort order: Anthropic, Custom, OpenAI, Other, Local · On-device

        assertEquals(5, groups.size)
        assertEquals("Anthropic", groups[0].provider)
        assertEquals("Custom", groups[1].provider)
        assertEquals("OpenAI", groups[2].provider)
        assertEquals("Other", groups[3].provider)
        assertEquals("Local · On-device", groups[4].provider)

        // Check Anthropic group
        val anthropicModels = groups[0].models
        assertEquals(3, anthropicModels.size)

        // Claude
        assertEquals("claude-3", anthropicModels[0].value)
        assertNull(anthropicModels[0].sublabel)
        assertNull(anthropicModels[0].badge)

        // Big Context (2M format)
        assertEquals("2M", anthropicModels[1].sublabel)

        // Small Context (500 format)
        assertEquals("500", anthropicModels[2].sublabel)

        // Check Custom group
        val customModels = groups[1].models
        assertEquals(1, customModels.size)
        assertEquals("unknown", customModels[0].value)

        // Check OpenAI group (BYOK logic and reasoning)
        val openaiModels = groups[2].models
        assertEquals(1, openaiModels.size)
        assertEquals("byok-1", openaiModels[0].value)
        assertEquals(ModelBadge.Byok, openaiModels[0].badge)
        assertEquals("reasoning", openaiModels[0].sublabel)

        // Check Other group
        val otherModels = groups[3].models
        assertEquals(1, otherModels.size)
        assertEquals("no-provider", otherModels[0].value)

        // Check Local group (Local logic and K format)
        val localModels = groups[4].models
        assertEquals(1, localModels.size)
        assertEquals("llama", localModels[0].value)
        assertEquals(ModelBadge.Local, localModels[0].badge)
        assertEquals("8K", localModels[0].sublabel) // 8500 / 1000 = 8K
    }

    @Test
    fun testSublabelReasoningEffort() {
        // Test sublabel generation for reasoningEffort
        val models = listOf(
            LlmModel(id = "1", name = "Effort", handle = "eff", providerType = "custom", reasoningEffort = "high", contextWindow = 128000)
        )
        val groups = ModelCatalog.group(models)
        val customModels = groups[0].models
        assertEquals("128K · reasoning", customModels[0].sublabel)
    }

    @Test
    fun testMatches() {
        val option = ModelOption("test-value", "Display Name", null, null)
        val provider = "Anthropic"

        // Blank query -> true
        assertTrue(ModelCatalog.matches(option, provider, " "))

        // Matches value
        assertTrue(ModelCatalog.matches(option, provider, "test-val"))

        // Matches display name (case insensitive)
        assertTrue(ModelCatalog.matches(option, provider, "display name"))
        assertTrue(ModelCatalog.matches(option, provider, "DISPLAY"))

        // Matches provider
        assertTrue(ModelCatalog.matches(option, provider, "anthrop"))

        // Doesn't match
        assertFalse(ModelCatalog.matches(option, provider, "openai"))
    }

    @Test
    fun testFilter() {
        val groups = listOf(
            ModelGroup("Anthropic", listOf(
                ModelOption("claude-1", "Claude 1", null, null),
                ModelOption("claude-2", "Claude 2", null, null)
            )),
            ModelGroup("OpenAI", listOf(
                ModelOption("gpt-4", "GPT 4", null, null)
            ))
        )

        // Blank query -> returns all groups unchanged
        val filteredBlank = ModelCatalog.filter(groups, "   ")
        assertEquals(groups, filteredBlank)

        // Match one item -> returns only matching item and group
        val filteredClaude = ModelCatalog.filter(groups, "claude-1")
        assertEquals(1, filteredClaude.size)
        assertEquals("Anthropic", filteredClaude[0].provider)
        assertEquals(1, filteredClaude[0].models.size)
        assertEquals("claude-1", filteredClaude[0].models[0].value)

        // Match group -> returns all items in matching group
        val filteredProvider = ModelCatalog.filter(groups, "open")
        assertEquals(1, filteredProvider.size)
        assertEquals("OpenAI", filteredProvider[0].provider)
        assertEquals(1, filteredProvider[0].models.size)
        assertEquals("gpt-4", filteredProvider[0].models[0].value)

        // Match nothing -> returns empty list
        val filteredNothing = ModelCatalog.filter(groups, "llama")
        assertEquals(0, filteredNothing.size)
    }
}
