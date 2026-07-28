package com.letta.mobile.data.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

class ModelCatalogNormalizerTest {
    @Test
    fun dedupesOverlappingLlMuxProviderHandlesPreferringOpenaiDialect() {
        val models = listOf(
            LlmModel(id = "lmstudio/MiniMax-M3", name = "MiniMax-M3", handle = "lmstudio/MiniMax-M3", providerType = "lmstudio"),
            LlmModel(id = "lc-openai/MiniMax-M3", name = "MiniMax-M3", handle = "lc-openai/MiniMax-M3", providerType = "lc-openai"),
            LlmModel(id = "lc-anthropic/MiniMax-M3", name = "MiniMax-M3", handle = "lc-anthropic/MiniMax-M3", providerType = "lc-anthropic"),
        )
        val normalized = ModelCatalogNormalizer.normalize(models)
        assertEquals(1, normalized.size)
        assertEquals("lc-openai/MiniMax-M3", normalized.single().handle)
        assertEquals(200_000, normalized.single().contextWindow)
        assertEquals(16_384, normalized.single().maxOutputTokens)
    }

    @Test
    fun enrichesGrokLimitsWhenCatalogOmitsThem() {
        val model = LlmModel(
            id = "cursor-grok-4.5-high-fast",
            name = "cursor-grok-4.5-high-fast",
            handle = "openai/cursor-grok-4.5-high-fast",
            providerType = "openai",
        )
        val enriched = ModelCatalogNormalizer.enrichLimits(model)
        assertEquals(131_072, enriched.contextWindow)
        assertEquals(8_192, enriched.maxOutputTokens)
    }

    @Test
    fun preservesAuthoritativeContextWindowOverKnownDefaults() {
        val model = LlmModel(
            id = "MiniMax-M3",
            handle = "openai/MiniMax-M3",
            name = "MiniMax-M3",
            providerType = "openai",
            contextWindow = 180_000,
            maxOutputTokens = 4_096,
        )
        val enriched = ModelCatalogNormalizer.enrichLimits(model)
        assertEquals(180_000, enriched.contextWindow)
        assertEquals(4_096, enriched.maxOutputTokens)
    }

    @Test
    fun prefersEntryWithMetadataWhenProviderRanksTie() {
        val bare = LlmModel(id = "x", handle = "openai/deepseek-v4-pro", name = "deepseek", providerType = "openai")
        val rich = LlmModel(
            id = "y",
            handle = "lc-openai/deepseek-v4-pro",
            name = "deepseek",
            providerType = "lc-openai",
            contextWindow = 1_000_000,
            maxOutputTokens = 8_192,
        )
        val winner = ModelCatalogNormalizer.normalize(listOf(bare, rich)).single()
        assertEquals("lc-openai/deepseek-v4-pro", winner.handle)
        assertEquals(1_000_000, winner.contextWindow)
    }
}

class AppServerListModelsAdapterNormalizationTest {
    @Test
    fun collapsesDuplicatePresentationHandlesAndWritesUpdateArgsLimits() {
        val entries = JsonArray(
            listOf(
                buildJsonObject {
                    put("id", "lmstudio/MiniMax-M3")
                    put("handle", "lmstudio/MiniMax-M3")
                    put("label", "MiniMax M3")
                },
                buildJsonObject {
                    put("id", "openai/MiniMax-M3")
                    put("handle", "openai/MiniMax-M3")
                    put("label", "MiniMax M3")
                },
            ),
        )
        val models = AppServerListModelsAdapter.toLlmModels(entries)
        assertEquals(1, models.size)
        assertEquals("openai/MiniMax-M3", models.single().handle)
        assertEquals(200_000, models.single().contextWindow)

        val adapted = AppServerListModelsAdapter.toLlmModelArray(entries).single().jsonObject
        assertEquals("200000", adapted["context_window"]!!.jsonPrimitive.content)
        val updateArgs = adapted["updateArgs"]!!.jsonObject
        assertEquals("200000", updateArgs["context_window_limit"]!!.jsonPrimitive.content)
        assertEquals("16384", updateArgs["max_output_tokens"]!!.jsonPrimitive.content)
    }

    @Test
    fun preservesExplicitCatalogLimitsFromUpdateArgs() {
        val entries = JsonArray(
            listOf(
                buildJsonObject {
                    put("id", "model-1")
                    put("handle", "openai/deepseek-v4-flash")
                    put("label", "DeepSeek Flash")
                    put(
                        "updateArgs",
                        buildJsonObject {
                            put("handle", "openai/deepseek-v4-flash")
                            put("context_window_limit", 1_000_000)
                            put("max_output_tokens", 8_192)
                        },
                    )
                },
            ),
        )
        val model = AppServerListModelsAdapter.toLlmModels(entries).single()
        assertEquals(1_000_000, model.contextWindow)
        assertEquals(8_192, model.maxOutputTokens)
    }

    @Test
    fun claudePresentationWithoutLimitsStaysUnset() {
        val entries = JsonArray(
            listOf(
                buildJsonObject {
                    put("id", "model-1")
                    put("handle", "anthropic/claude-fable-5")
                    put("label", "Claude Fable 5")
                },
            ),
        )
        val model = AppServerListModelsAdapter.toLlmModels(entries).single()
        assertNull(model.contextWindow)
        assertNull(model.maxOutputTokens)
        assertNotNull(model.handle)
        assertTrue(model.displayName.contains("Fable"))
    }
}
