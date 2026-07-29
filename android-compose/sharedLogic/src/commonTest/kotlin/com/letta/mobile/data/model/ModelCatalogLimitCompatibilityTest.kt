package com.letta.mobile.data.model

import kotlin.test.Test
import kotlin.test.assertEquals

class ModelCatalogLimitCompatibilityTest {
    @Test
    fun preservesLegacyMaxTokensAsAuthoritativeOutputLimit() {
        val model = LlmModel(
            id = "MiniMax-M3",
            handle = "openai/MiniMax-M3",
            name = "MiniMax-M3",
            providerType = "openai",
            maxTokens = 4_096,
        )

        val enriched = ModelCatalogNormalizer.enrichLimits(model)

        assertEquals(4_096, enriched.maxOutputTokens)
        assertEquals(4_096, enriched.maxTokens)
    }
}
