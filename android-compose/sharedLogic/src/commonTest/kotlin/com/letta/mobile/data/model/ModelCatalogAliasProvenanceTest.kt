package com.letta.mobile.data.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

class ModelCatalogAliasProvenanceTest {
    @Test
    fun normalizationRecordsDiscardedSelectionAliases() {
        val normalized = ModelCatalogNormalizer.normalize(
            listOf(
                LlmModel(
                    id = "lmstudio/MiniMax-M3",
                    name = "MiniMax-M3",
                    handle = "lmstudio/MiniMax-M3",
                    providerType = "lmstudio",
                ),
                LlmModel(
                    id = "lc-openai/MiniMax-M3",
                    name = "MiniMax-M3",
                    handle = "lc-openai/MiniMax-M3",
                    providerType = "lc-openai",
                ),
                LlmModel(
                    id = "lc-anthropic/MiniMax-M3",
                    name = "MiniMax-M3",
                    handle = "lc-anthropic/MiniMax-M3",
                    providerType = "lc-anthropic",
                ),
            ),
        )

        assertEquals(
            setOf("lmstudio/MiniMax-M3", "lc-anthropic/MiniMax-M3"),
            normalized.single().selectionAliases,
        )
    }

    @Test
    fun adapterRoundTripPreservesSelectionAliases() {
        val entries = JsonArray(
            listOf(
                modelEntry("lmstudio/MiniMax-M3"),
                modelEntry("openai/MiniMax-M3"),
            ),
        )

        val adapted = AppServerListModelsAdapter.toLlmModelArray(entries).single().jsonObject
        val roundTrip = AppServerListModelsAdapter.toLlmModels(JsonArray(listOf(adapted))).single()

        assertEquals(setOf("lmstudio/MiniMax-M3"), roundTrip.selectionAliases)
    }

    @Test
    fun adapterPrefersSelectionRoutingOverPresentationRouting() {
        val entries = JsonArray(
            listOf("east", "west").map { route ->
                buildJsonObject {
                    put("id", route)
                    put("handle", "openai/gpt-4o")
                    put("provider_type", "presentation-provider")
                    put("provider_name", "presentation-shared")
                    put("model_endpoint", "https://presentation.example/v1")
                    put(
                        "updateArgs",
                        buildJsonObject {
                            put("handle", "openai/gpt-4o")
                            put("provider_type", "selection-$route")
                            put("provider_name", "byok-$route")
                            put("model_endpoint", "https://$route.example/v1")
                        },
                    )
                }
            },
        )

        val models = AppServerListModelsAdapter.toLlmModels(entries)

        assertEquals(2, models.size)
        assertEquals(setOf("selection-east", "selection-west"), models.map { it.providerType }.toSet())
        assertEquals(setOf("byok-east", "byok-west"), models.map { it.providerName }.toSet())
        assertEquals(
            setOf("https://east.example/v1", "https://west.example/v1"),
            models.map { it.modelEndpoint }.toSet(),
        )
        val westSelection = ModelCatalog.selectionValue(models, models.single { it.id == "west" })
        val createParams = AgentCreateParams(
            name = "West route",
            model = westSelection,
        ).withCatalogModelRouting(models)
        assertEquals("openai/gpt-4o", createParams.model)
        assertEquals("selection-west", createParams.modelSettings?.providerType)
        assertEquals("byok-west", createParams.modelSettings?.providerName)
        assertEquals("https://west.example/v1", createParams.llmConfig?.modelEndpoint)
    }

    @Test
    fun adapterPreservesSelectionSpecificModelId() {
        val entry = buildJsonObject {
            put("id", "openrouter-nemotron")
            put("handle", "openrouter/nvidia/nemotron-nano-9b-v2:free")
            put(
                "updateArgs",
                buildJsonObject {
                    put("handle", "openrouter/nvidia/nemotron-nano-9b-v2:free")
                    put("model", "nvidia/nemotron-nano-9b-v2:free")
                },
            )
        }

        val model = AppServerListModelsAdapter.toLlmModels(JsonArray(listOf(entry))).single()

        assertEquals("nvidia/nemotron-nano-9b-v2:free", model.model)
        assertEquals("nvidia/nemotron-nano-9b-v2:free", model.toAgentCreateLlmConfig()?.model)
    }

    private fun modelEntry(handle: String) = buildJsonObject {
        put("id", handle)
        put("handle", handle)
        put("label", "MiniMax M3")
    }
}
