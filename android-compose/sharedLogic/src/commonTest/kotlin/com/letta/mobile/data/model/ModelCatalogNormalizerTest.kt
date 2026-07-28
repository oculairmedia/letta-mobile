package com.letta.mobile.data.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonArray
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
    fun doesNotCollapseDistinctProviderRoutesWithSameModelSuffix() {
        val models = listOf(
            LlmModel(id = "openai/gpt-4o", name = "gpt-4o", handle = "openai/gpt-4o", providerType = "openai"),
            LlmModel(id = "azure/gpt-4o", name = "gpt-4o", handle = "azure/gpt-4o", providerType = "azure"),
        )
        val normalized = ModelCatalogNormalizer.normalize(models)
        assertEquals(2, normalized.size)
        assertEquals(
            setOf("openai/gpt-4o", "azure/gpt-4o"),
            normalized.map { it.handle }.toSet(),
        )
    }

    @Test
    fun doesNotCollapseSameHandleAcrossDistinctModelEndpoints() {
        fun assertKeptApart(vararg pairs: Pair<String, String>) {
            val normalized = ModelCatalogNormalizer.normalize(modelsSharingHandleWithEndpoints(*pairs))
            assertEquals(pairs.size, normalized.size)
            assertEquals(pairs.map { it.second }.toSet(), normalized.map { it.modelEndpoint }.toSet())
        }
        assertKeptApart(
            "azure/gpt-4o" to "https://azure-east.example/v1",
            "azure/gpt-4o" to "https://azure-west.example/v1",
        )
        assertKeptApart(
            "openai/gpt-4o" to "https://llmux.example/v1",
            "openai/gpt-4o" to "https://byok.example/v1",
        )
    }

    @Test
    fun adapterPreservesModelEndpointIntoNormalization() {
        val entries = kotlinx.serialization.json.JsonArray(
            listOf(
                buildJsonObject {
                    put("id", "a")
                    put("handle", "openai/gpt-4o")
                    put("model_endpoint", "https://llmux.example/v1")
                },
                buildJsonObject {
                    put("id", "b")
                    put("handle", "openai/gpt-4o")
                    put("model_endpoint", "https://byok.example/v1")
                },
            ),
        )
        val models = AppServerListModelsAdapter.toLlmModels(entries)
        assertEquals(2, models.size)
        assertEquals(
            setOf("https://llmux.example/v1", "https://byok.example/v1"),
            models.map { it.modelEndpoint }.toSet(),
        )
    }

    @Test
    fun doesNotCollapseOpenaiAliasesWithDistinctProviderNames() {
        val models = listOf(
            LlmModel(
                id = "a",
                name = "gpt-4o",
                handle = "openai/gpt-4o",
                providerType = "openai",
                providerName = "llmux-openai",
            ),
            LlmModel(
                id = "b",
                name = "gpt-4o",
                handle = "lc-openai/gpt-4o",
                providerType = "lc-openai",
                providerName = "byok-openai",
            ),
        )
        val normalized = ModelCatalogNormalizer.normalize(models)
        assertEquals(2, normalized.size)
    }

    @Test
    fun collapsesLlMuxAliasesThatShareEndpointProvenance() {
        val normalized = ModelCatalogNormalizer.normalize(
            modelsSharingHandleWithEndpoints(
                "lmstudio/MiniMax-M3" to "https://llmux.example/v1",
                "openai/MiniMax-M3" to "https://llmux.example/v1",
            ),
        )
        assertEquals(1, normalized.size)
        assertEquals("openai/MiniMax-M3", normalized.single().handle)
    }

    private fun modelsSharingHandleWithEndpoints(
        vararg handleToEndpoint: Pair<String, String>,
    ): List<LlmModel> =
        handleToEndpoint.mapIndexed { index, (handle, endpoint) ->
            val slash = handle.indexOf('/')
            val provider = if (slash > 0) handle.substring(0, slash) else ""
            val name = if (slash >= 0) handle.substring(slash + 1) else handle
            LlmModel(
                id = "row-$index",
                name = name,
                handle = handle,
                providerType = provider,
                modelEndpoint = endpoint,
            )
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
    fun doesNotApplyCursorGrokDefaultsToUnrelatedGrokFamilyIds() {
        val model = LlmModel(
            id = "grok-code-fast-1",
            name = "grok-code-fast-1",
            handle = "openai/grok-code-fast-1",
            providerType = "openai",
        )
        val enriched = ModelCatalogNormalizer.enrichLimits(model)
        assertNull(enriched.contextWindow)
        assertNull(enriched.maxOutputTokens)
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
    fun prefersHigherProviderRankEvenWhenLowerRankHasRicherMetadata() {
        val bareOpenai = LlmModel(
            id = "x",
            handle = "openai/deepseek-v4-pro",
            name = "deepseek",
            providerType = "openai",
        )
        val richLcOpenai = LlmModel(
            id = "y",
            handle = "lc-openai/deepseek-v4-pro",
            name = "deepseek",
            providerType = "lc-openai",
            contextWindow = 1_000_000,
            maxOutputTokens = 8_192,
        )
        val winner = ModelCatalogNormalizer.normalize(listOf(bareOpenai, richLcOpenai)).single()
        assertEquals("openai/deepseek-v4-pro", winner.handle)
    }

    @Test
    fun prefersRicherMetadataWhenProviderRanksTie() {
        val bare = LlmModel(
            id = "x",
            handle = "lc-openai/deepseek-v4-pro",
            name = "deepseek",
            providerType = "lc-openai",
        )
        val rich = LlmModel(
            id = "y",
            handle = "lc-openai/deepseek-v4-pro",
            name = "deepseek",
            providerType = "lc-openai",
            contextWindow = 1_000_000,
            maxOutputTokens = 8_192,
            displayNameOverride = "DeepSeek Pro",
        )
        val winner = ModelCatalogNormalizer.normalize(listOf(bare, rich)).single()
        assertEquals(1_000_000, winner.contextWindow)
        assertEquals("DeepSeek Pro", winner.displayNameOverride)
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
    fun preservesServerUpdateArgsFieldsBeyondHandleAndLimits() {
        val entries = JsonArray(
            listOf(
                buildJsonObject {
                    put("id", "presentation-1")
                    put("handle", "openai/MiniMax-M3")
                    put("label", "MiniMax M3")
                    put(
                        "updateArgs",
                        buildJsonObject {
                            put("handle", "openai/MiniMax-M3")
                            put("model", "MiniMax-M3")
                            put("model_handle", "openai/MiniMax-M3")
                            put("id", "catalog-row-9")
                        },
                    )
                },
            ),
        )
        val adapted = AppServerListModelsAdapter.toLlmModelArray(entries).single().jsonObject
        val updateArgs = adapted["updateArgs"]!!.jsonObject
        assertEquals("MiniMax-M3", updateArgs["model"]!!.jsonPrimitive.content)
        assertEquals("catalog-row-9", updateArgs["id"]!!.jsonPrimitive.content)
        assertEquals("openai/MiniMax-M3", updateArgs["handle"]!!.jsonPrimitive.content)
        assertEquals("200000", updateArgs["context_window_limit"]!!.jsonPrimitive.content)
        assertEquals("openai/MiniMax-M3", adapted["selection_handle"]!!.jsonPrimitive.content)
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

    @Test
    fun preservesPresentationDescriptionOnAdaptedModelObject() {
        val entries = JsonArray(
            listOf(
                buildJsonObject {
                    put("id", "model-1")
                    put("handle", "anthropic/claude-fable-5")
                    put("label", "Claude Fable 5")
                    put("description", "presentation only")
                },
            ),
        )
        val adapted = AppServerListModelsAdapter.toLlmModelArray(entries).single().jsonObject
        assertEquals("presentation only", adapted["description"]!!.jsonPrimitive.content)
    }

    @Test
    fun prefersSelectionHandleOverPresentationAliasWhenDecoding() {
        val entries = JsonArray(
            listOf(
                buildJsonObject {
                    put("id", "presentation-1")
                    put("handle", "Friendly Alias")
                    put("label", "Friendly")
                    put("selection_handle", "openai/real-model")
                    put(
                        "updateArgs",
                        buildJsonObject {
                            put("handle", "openai/real-model")
                        },
                    )
                },
            ),
        )
        val model = AppServerListModelsAdapter.toLlmModels(entries).single()
        assertEquals("openai/real-model", model.handle)
        assertEquals("Friendly", model.displayName)
        assertEquals("openai", model.providerType)
    }

    @Test
    fun bareSelectionTargetProviderTypeUsesExplicitOrHandleDialect() {
        val explicit = AppServerListModelsAdapter.toLlmModels(
            bareSelectionTargetEntries(providerType = "llmux-openai"),
        ).single()
        assertEquals("gpt-4o", explicit.handle)
        assertEquals("llmux-openai", explicit.providerType)

        val derived = AppServerListModelsAdapter.toLlmModels(
            bareSelectionTargetEntries(),
        ).single()
        assertEquals("gpt-4o", derived.handle)
        assertEquals("openai", derived.providerType)
    }

    private fun bareSelectionTargetEntries(providerType: String? = null): JsonArray =
        JsonArray(
            listOf(
                buildJsonObject {
                    put("id", "presentation-1")
                    put("handle", "openai/gpt-4o")
                    providerType?.let { put("provider_type", it) }
                    put(
                        "updateArgs",
                        buildJsonObject {
                            put("model", "gpt-4o")
                        },
                    )
                },
            ),
        )

    @Test
    fun preservesExplicitUpdateArgsCapsOverLargerCatalogFlags() {
        val entries = JsonArray(
            listOf(
                buildJsonObject {
                    put("id", "model-1")
                    put("handle", "openai/deepseek-v4-flash")
                    put("label", "DeepSeek Flash")
                    put(
                        "flags",
                        buildJsonObject {
                            put("context_window", 200_000)
                        },
                    )
                    put(
                        "updateArgs",
                        buildJsonObject {
                            put("handle", "openai/deepseek-v4-flash")
                            put("context_window_limit", 128_000)
                            put("max_output_tokens", 4_096)
                        },
                    )
                },
            ),
        )
        val adapted = AppServerListModelsAdapter.toLlmModelArray(entries).single().jsonObject
        val updateArgs = adapted["updateArgs"]!!.jsonObject
        assertEquals("128000", updateArgs["context_window_limit"]!!.jsonPrimitive.content)
        assertEquals("4096", updateArgs["max_output_tokens"]!!.jsonPrimitive.content)
        val model = AppServerListModelsAdapter.toLlmModels(entries).single()
        assertEquals(128_000, model.contextWindow)
        assertEquals(4_096, model.maxOutputTokens)
    }

    @Test
    fun keepsWinningModelPairedWithItsSourceUpdateArgs() {
        val entries = JsonArray(
            listOf(
                buildJsonObject {
                    put("id", "rich-first")
                    put("handle", "openai/same-model")
                    put("label", "Same")
                    put("description", "richer row")
                    put(
                        "updateArgs",
                        buildJsonObject {
                            put("handle", "openai/same-model")
                            put("model", "from-rich")
                            put("id", "rich-catalog")
                        },
                    )
                    put("context_window", 200_000)
                },
                buildJsonObject {
                    put("id", "poor-last")
                    put("handle", "openai/same-model")
                    put("label", "Same")
                    put("description", "poorer row")
                    put(
                        "updateArgs",
                        buildJsonObject {
                            put("handle", "openai/same-model")
                            put("model", "from-poor")
                            put("id", "poor-catalog")
                        },
                    )
                },
            ),
        )
        val adapted = AppServerListModelsAdapter.toLlmModelArray(entries).single().jsonObject
        assertEquals("richer row", adapted["description"]!!.jsonPrimitive.content)
        val updateArgs = adapted["updateArgs"]!!.jsonObject
        assertEquals("from-rich", updateArgs["model"]!!.jsonPrimitive.content)
        assertEquals("rich-catalog", updateArgs["id"]!!.jsonPrimitive.content)
        assertEquals("200000", adapted["context_window"]!!.jsonPrimitive.content)
    }
}
