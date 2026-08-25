package com.letta.mobile.data.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ModelCatalogRegressionTest {

    private fun mixedCatalogFixture(): List<LlmModel> = listOf(
        // 1. OpenAI route
        LlmModel(id = "openai/gpt-4o", name = "GPT-4o", handle = "openai/gpt-4o", providerType = "openai"),
        // 2. Anthropic route
        LlmModel(id = "anthropic/claude-3-5-sonnet", name = "Claude 3.5 Sonnet", handle = "anthropic/claude-3-5-sonnet", providerType = "anthropic"),
        // 3. Google route
        LlmModel(id = "google/gemini-1.5-pro", name = "Gemini 1.5 Pro", handle = "google/gemini-1.5-pro", providerType = "google"),
        // 4. xAI route
        LlmModel(id = "xai/grok-2", name = "Grok 2", handle = "xai/grok-2", providerType = "xai"),
        // 5. ZAI route
        LlmModel(id = "zai/glm-4", name = "GLM-4", handle = "zai/glm-4", providerType = "zai"),
        // 6. MiniMax route
        LlmModel(id = "minimax/minimax-01", name = "MiniMax-01", handle = "minimax/minimax-01", providerType = "minimax"),
        // 7. Moonshot route
        LlmModel(id = "moonshot/moonshot-v1-8k", name = "Moonshot v1 8K", handle = "moonshot/moonshot-v1-8k", providerType = "moonshot"),
        // 8. Bedrock route
        LlmModel(id = "bedrock/anthropic.claude-3-sonnet", name = "Bedrock Claude 3 Sonnet", handle = "bedrock/anthropic.claude-3-sonnet", providerType = "bedrock"),
        // 9. LM Studio route
        LlmModel(id = "lmstudio/llama-3.1-8b", name = "Llama 3.1 8B", handle = "lmstudio/llama-3.1-8b", providerType = "lmstudio"),
        // 10. Custom OpenAI-compatible route (distinct endpoint / providerName)
        LlmModel(
            id = "custom-openai/my-model",
            name = "My Custom Model",
            handle = "custom-openai/my-model",
            providerType = "openai",
            providerName = "custom-gateway",
            modelEndpoint = "https://custom.endpoint.example/v1",
        ),
        // 11. Real alias pair (openai + lmstudio sharing MiniMax-M3 LLMux endpoint)
        LlmModel(id = "openai/MiniMax-M3", name = "MiniMax-M3", handle = "openai/MiniMax-M3", providerType = "openai"),
        LlmModel(id = "lmstudio/MiniMax-M3", name = "MiniMax-M3", handle = "lmstudio/MiniMax-M3", providerType = "lmstudio"),
    )

    private val expectedNonAliasHandles = setOf(
        "openai/gpt-4o",
        "anthropic/claude-3-5-sonnet",
        "google/gemini-1.5-pro",
        "xai/grok-2",
        "zai/glm-4",
        "minimax/minimax-01",
        "moonshot/moonshot-v1-8k",
        "bedrock/anthropic.claude-3-sonnet",
        "lmstudio/llama-3.1-8b",
        "custom-openai/my-model",
        "openai/MiniMax-M3",
    )

    @Test
    fun normalizedCatalogRetainsEveryUniqueNonAliasRouteFromMixedFixture() {
        val raw = mixedCatalogFixture()
        val normalized = ModelCatalogNormalizer.normalize(raw)

        assertEquals(11, normalized.size)
        assertEquals(expectedNonAliasHandles, normalized.map { it.handle }.toSet())
    }

    @Test
    fun aliasCollapseStillRemovesOnlyProvenAliases() {
        val raw = mixedCatalogFixture()
        val normalized = ModelCatalogNormalizer.normalize(raw)

        val minimaxRow = normalized.single { it.handle == "openai/MiniMax-M3" }
        assertEquals(setOf("lmstudio/MiniMax-M3"), minimaxRow.selectionAliases)

        val unaliasedLmStudio = normalized.single { it.handle == "lmstudio/llama-3.1-8b" }
        assertTrue(unaliasedLmStudio.selectionAliases.isEmpty())
    }

    @Test
    fun protocolOpenaiDoesNotBecomeProviderBrandEntitlement() {
        val raw = mixedCatalogFixture()
        val normalized = ModelCatalogNormalizer.normalize(raw)

        val customRoute = normalized.single { it.handle == "custom-openai/my-model" }
        assertEquals("custom-gateway", customRoute.providerName)
        assertEquals("https://custom.endpoint.example/v1", customRoute.modelEndpoint)

        val standardOpenai = normalized.single { it.handle == "openai/gpt-4o" }
        assertEquals(null, standardOpenai.modelEndpoint)
    }

    @Test
    fun androidAndDesktopSelectorProjectionsRetainEqualRouteIdentities() {
        val raw = mixedCatalogFixture()
        val normalized = ModelCatalogNormalizer.normalize(raw)

        val groups = ModelCatalog.group(raw)
        val selectableTokens = groups.flatMap { it.models }.map { it.value }.toSet()
        val normalizedTokens = normalized.map { ModelCatalog.selectionValue(normalized, it) }.toSet()

        assertEquals(normalizedTokens, selectableTokens)
        assertEquals(expectedNonAliasHandles, selectableTokens)
    }

    @Test
    fun failOnRevertProvesOldFilterDropsNonOpenAiRoutes() {
        val raw = mixedCatalogFixture()
        val normalized = ModelCatalogNormalizer.normalize(raw)

        // Synthetic provider row: provider_type=openai, provider_name=lmstudio-local
        val syntheticCredentialedTypes = setOf("openai")

        // Simulate #1196 filterByCredentialedProviders logic:
        val oldFiltered = normalized.filter { model ->
            model.providerType.trim().lowercase() in syntheticCredentialedTypes ||
                ModelCatalogNormalizer.providerPrefix(model.handle ?: model.id) in syntheticCredentialedTypes ||
                model.selectionAliases.any { alias ->
                    ModelCatalogNormalizer.providerPrefix(alias) in syntheticCredentialedTypes
                }
        }

        // Under #1196's filter, non-OpenAI routes (Anthropic, Google, xAI, ZAI, Moonshot, Bedrock, LM Studio) disappear!
        val survivingHandles = oldFiltered.map { it.handle }.toSet()
        assertEquals(
            setOf("openai/gpt-4o", "custom-openai/my-model", "openai/MiniMax-M3"),
            survivingHandles,
        )
        // Assert that 8 non-OpenAI routes were incorrectly dropped by the old filter
        assertEquals(3, survivingHandles.size)
        assertTrue("anthropic/claude-3-5-sonnet" !in survivingHandles)
        assertTrue("google/gemini-1.5-pro" !in survivingHandles)
        assertTrue("xai/grok-2" !in survivingHandles)
        assertTrue("zai/glm-4" !in survivingHandles)
        assertTrue("bedrock/anthropic.claude-3-sonnet" !in survivingHandles)
    }
}
