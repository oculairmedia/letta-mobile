package com.letta.mobile.data.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ModelCatalogCredentialFilterTest {
    @Test
    fun filterByCredentialedProvidersKeepsMatchingHandlePrefixesAndDropsOthers() {
        val models = listOf(
            LlmModel(id = "a", name = "gpt-4o", handle = "openai/gpt-4o", providerType = ""),
            LlmModel(id = "b", name = "claude", handle = "anthropic/claude", providerType = "anthropic"),
            LlmModel(id = "c", name = "gemini", handle = "google/gemini", providerType = "google"),
            LlmModel(id = "d", name = "mistral", handle = "mistral/mixtral", providerType = "mistral"),
        )
        val filtered = ModelCatalogNormalizer.filterByCredentialedProviders(
            models,
            setOf("openai", "anthropic"),
        )
        assertEquals(setOf("openai/gpt-4o", "anthropic/claude"), filtered.map { it.handle }.toSet())
    }

    @Test
    fun filterByCredentialedProvidersMatchesProviderTypeAloneWhenHandleHasNoSlash() {
        val models = listOf(
            LlmModel(id = "a", name = "gpt-4o", handle = "gpt-4o", providerType = "openai"),
            LlmModel(id = "b", name = "gemini", handle = "gemini", providerType = "google"),
        )
        val filtered = ModelCatalogNormalizer.filterByCredentialedProviders(models, setOf("openai"))
        assertEquals(listOf("gpt-4o"), filtered.map { it.handle })
    }

    @Test
    fun filterByCredentialedProvidersKeepsModelWhenSelectionAliasProviderIsCredentialed() {
        val model = LlmModel(
            id = "openai/gpt-4o",
            name = "gpt-4o",
            handle = "openai/gpt-4o",
            providerType = "openai",
            selectionAliases = setOf("lmstudio/llama-3.1-8b"),
        )
        val filtered = ModelCatalogNormalizer.filterByCredentialedProviders(listOf(model), setOf("lmstudio"))
        assertEquals(listOf(model), filtered)
    }

    @Test
    fun filterByCredentialedProvidersWithEmptyCredentialedTypesReturnsModelsUnchanged() {
        val models = listOf(
            LlmModel(id = "a", name = "gpt-4o", handle = "openai/gpt-4o", providerType = "openai"),
            LlmModel(id = "b", name = "gemini", handle = "google/gemini", providerType = "google"),
        )
        assertEquals(models, ModelCatalogNormalizer.filterByCredentialedProviders(models, emptySet()))
    }

    @Test
    fun filterByCredentialedProvidersMatchesCredentialedTypesCaseInsensitively() {
        val model = LlmModel(id = "a", name = "gpt-4o", handle = "gpt-4o", providerType = "openai")
        val filtered = ModelCatalogNormalizer.filterByCredentialedProviders(listOf(model), setOf("OPENAI"))
        assertEquals(listOf(model), filtered)
    }

    @Test
    fun filterByCredentialedProvidersDropsModelWithBlankIdentitiesWhenTypesAreNonEmpty() {
        val model = LlmModel(id = "a", name = "mystery", handle = "mystery", providerType = "")
        val filtered = ModelCatalogNormalizer.filterByCredentialedProviders(listOf(model), setOf("openai"))
        assertTrue(filtered.isEmpty())
    }

    @Test
    fun filterByCredentialedProvidersReturnsEmptyListWhenNoModelMatches() {
        val models = listOf(
            LlmModel(id = "a", name = "gpt-4o", handle = "openai/gpt-4o", providerType = "openai"),
            LlmModel(id = "b", name = "gemini", handle = "google/gemini", providerType = "google"),
        )
        val filtered = ModelCatalogNormalizer.filterByCredentialedProviders(models, setOf("azure"))
        assertTrue(filtered.isEmpty())
    }
}
