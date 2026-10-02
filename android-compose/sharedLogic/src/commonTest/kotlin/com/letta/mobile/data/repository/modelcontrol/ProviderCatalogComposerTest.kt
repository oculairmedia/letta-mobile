package com.letta.mobile.data.repository.modelcontrol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProviderCatalogComposerTest {
    private val providers = ModelControlWire.providers(ProviderCatalogFixtures.providerList)
    private val models = ModelControlWire.catalog(ProviderCatalogFixtures.modelList)
    private val sections = ProviderCatalogComposer.compose(providers, models)

    private fun section(key: String) = sections.first { it.key == key }

    @Test
    fun connectedSectionsComeFirstLargestFirstThenTheRestByName() {
        assertEquals(listOf("lmstudio", "openai", "anthropic", "amazon-bedrock", "anthropic-oauth"), sections.map { it.key })
    }

    @Test
    fun aConnectedAliasMatchesTheRouteKey() {
        // Models route as `lmstudio/...`; the row is connected under the alias `lc-lmstudio`.
        val lmstudio = section("lmstudio")
        assertEquals("LM Studio (local)", lmstudio.displayName)
        assertTrue(lmstudio.isConnected)
        assertEquals(3, lmstudio.models.size)
    }

    @Test
    fun anExactIdWinsWhenTwoRowsShareAnAlias() {
        // `anthropic` and `anthropic-oauth` both answer to "anthropic"; the row whose id IS the key serves the models.
        assertEquals("anthropic", section("anthropic").provider?.id)
        // The OAuth row stays connectable as its own, model-less section under its own id.
        val oauth = section("anthropic-oauth")
        assertEquals("anthropic-oauth", oauth.provider?.id)
        assertTrue(oauth.models.isEmpty())
    }

    @Test
    fun aProviderWithoutModelsStillGetsASection() {
        val bedrock = section("amazon-bedrock")
        assertNotNull(bedrock.provider)
        assertTrue(bedrock.models.isEmpty())
        assertEquals(false, bedrock.isConnected)
    }

    @Test
    fun alsoViaNamesTheOtherRoutesOfTheSameModel() {
        val viaLmStudio = section("lmstudio").models.first { it.identity == "claude-fable-5-1" }
        assertEquals(listOf("anthropic"), viaLmStudio.alsoVia)
        val viaOpenAi = section("openai").models.single()
        assertEquals(listOf("lmstudio"), viaOpenAi.alsoVia)
        val onlyHere = section("lmstudio").models.first { it.identity == "minimax-m3" }
        assertTrue(onlyHere.alsoVia.isEmpty())
    }

    @Test
    fun countsFollowExposure() {
        val lmstudio = section("lmstudio")
        assertEquals(2, lmstudio.exposedCount)
        assertEquals(1, lmstudio.hiddenCount)
        assertEquals(false, lmstudio.allExposed)
        assertEquals(false, lmstudio.noneExposed)
        assertTrue(section("openai").allExposed)
    }

    @Test
    fun rowsAreSortedByDisplayName() {
        assertEquals(listOf("claude-fable-5-1", "gpt-5.6-sol", "minimax-m3"), section("lmstudio").models.map { it.identity })
    }

    @Test
    fun aRouteNoProviderRowDescribesStillLists() {
        val orphanOnly = ProviderCatalogComposer.compose(emptyList(), models)
        val anthropic = orphanOnly.first { it.key == "anthropic" }
        assertNull(anthropic.provider)
        assertEquals("anthropic", anthropic.displayName)
        assertTrue(anthropic.isConnected, "serving models counts as connected when no row says otherwise")
    }
}
