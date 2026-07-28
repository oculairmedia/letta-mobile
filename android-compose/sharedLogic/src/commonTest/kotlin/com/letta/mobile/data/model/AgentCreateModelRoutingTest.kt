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
}
