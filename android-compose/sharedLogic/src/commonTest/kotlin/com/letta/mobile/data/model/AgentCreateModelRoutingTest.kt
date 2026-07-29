package com.letta.mobile.data.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AgentCreateModelRoutingTest {
    @Test
    fun customRouteProvenanceIsSerialized() {
        val selected = LlmModel(
            id = "custom-minimax",
            name = "MiniMax-M3",
            handle = "openai/MiniMax-M3",
            providerType = "openai",
            providerName = "customer-openai",
            providerCategory = "byok",
            modelEndpointType = "openai",
            modelEndpoint = "https://custom.example/v1",
        )

        val config = selected.toAgentCreateLlmConfig()

        assertEquals("MiniMax-M3", config?.model)
        assertEquals("openai/MiniMax-M3", config?.handle)
        assertEquals("openai", config?.modelEndpointType)
        assertEquals("https://custom.example/v1", config?.modelEndpoint)
        assertEquals("customer-openai", config?.providerName)
        assertEquals("byok", config?.providerCategory)
        assertNull(config?.contextWindow)
    }

    @Test
    fun bareCatalogSelectionCarriesExplicitEnrichedContext() {
        val selected = LlmModel(
            id = "openai/MiniMax-M3",
            name = "MiniMax-M3",
            handle = "openai/MiniMax-M3",
            providerType = "openai",
            contextWindow = 200_000,
        )

        val config = selected.toAgentCreateLlmConfig()

        assertEquals(200_000, config?.contextWindow)
        assertEquals("openai/MiniMax-M3", config?.handle)
    }

    @Test
    fun namespacedModelIdLosesOnlyRoutingProviderPrefix() {
        val selected = LlmModel(
            id = "openrouter/nvidia/nemotron-nano-9b-v2:free",
            name = "Nemotron Nano",
            model = "nvidia/nemotron-nano-9b-v2:free",
            handle = "openrouter/nvidia/nemotron-nano-9b-v2:free",
            providerType = "openrouter",
            providerName = "openrouter",
        )

        val config = selected.toAgentCreateLlmConfig()

        assertEquals("nvidia/nemotron-nano-9b-v2:free", config?.model)
        assertEquals("openrouter/nvidia/nemotron-nano-9b-v2:free", config?.handle)
    }

    @Test
    fun sharedCreateShapingAddsDesktopCatalogRouting() {
        val selected = LlmModel(
            id = "openai/MiniMax-M3",
            name = "MiniMax-M3",
            handle = "openai/MiniMax-M3",
            providerType = "openai",
            providerName = "llmux",
            modelEndpoint = "http://llmux:4000/v1",
            contextWindow = 200_000,
        )

        val params = AgentCreateParams(
            name = "Desktop agent",
            model = "openai/MiniMax-M3",
            modelSettings = ModelSettings(temperature = 0.5),
        ).withCatalogModelRouting(listOf(selected))

        assertEquals("openai", params.modelSettings?.providerType)
        assertEquals("llmux", params.modelSettings?.providerName)
        assertEquals(0.5, params.modelSettings?.temperature)
        assertEquals("MiniMax-M3", params.llmConfig?.model)
        assertEquals("http://llmux:4000/v1", params.llmConfig?.modelEndpoint)
        assertEquals(200_000, params.llmConfig?.contextWindow)
    }

    @Test
    fun sharedCreateShapingPrefersExactCustomRouteOverAlias() {
        val shared = LlmModel(
            id = "shared",
            name = "MiniMax-M3",
            handle = "openai/MiniMax-M3",
            providerType = "openai",
            providerName = "llmux",
            selectionAliases = setOf("lmstudio/MiniMax-M3"),
        )
        val custom = LlmModel(
            id = "custom",
            name = "MiniMax-M3",
            handle = "lmstudio/MiniMax-M3",
            providerType = "lmstudio",
            providerName = "custom-route",
            providerCategory = "byok",
            modelEndpoint = "https://custom.example/v1",
        )

        val params = AgentCreateParams(
            name = "Custom agent",
            model = "lmstudio/MiniMax-M3",
        ).withCatalogModelRouting(listOf(shared, custom))

        assertEquals("custom-route", params.modelSettings?.providerName)
        assertEquals("https://custom.example/v1", params.llmConfig?.modelEndpoint)
    }
}
