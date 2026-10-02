package com.letta.mobile.data.repository.modelcontrol

import com.letta.mobile.data.model.LlmModel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

@OptIn(ExperimentalCoroutinesApi::class)
class ModelPickerControllerTest {
    private class Host(scope: CoroutineScope, val invoker: RecordingInvoker) {
        val session = ModelControlSession(invoker)
        val picker = ModelPickerController(scope, session.pickerSource())
        val management = session.managementController(scope)
    }

    private fun host(scope: CoroutineScope, invoker: RecordingInvoker = ProviderControlFixtures.invoker()) = Host(scope, invoker)

    @Test
    fun opensOnAnEmptyCatalogByLoadingItWithoutForcing() = runTest(UnconfinedTestDispatcher()) {
        val host = host(backgroundScope)

        host.picker.ensureLoaded()

        val modelList = host.invoker.calls.single { it.first == "model.list" }.second
        assertNull(modelList["force"])
        assertEquals(false, host.picker.state.value.loading)
        assertTrue(host.picker.state.value.groups.isNotEmpty())
    }

    @Test
    fun groupsOnlyExposedModelsUnderTheirProvidersName() = runTest(UnconfinedTestDispatcher()) {
        val host = host(backgroundScope)
        host.picker.ensureLoaded()

        val groups = host.picker.state.value.groups

        assertEquals(listOf("lmstudio", "openai", "chatgpt-plus-pro", "anthropic"), groups.map { it.key })
        assertEquals(listOf("LM Studio (local)", "OpenAI", "OpenAI Codex", "Anthropic"), groups.map { it.title })
        val handles = groups.flatMap { g -> g.entries.map { it.handle.value } }
        assertFalse("lmstudio/minimax-m3" in handles, "hidden models stay out of the picker")
        assertFalse("chatgpt-plus-pro/gpt-5.5-mini" in handles)
        assertEquals(5, host.picker.state.value.modelCount)
    }

    @Test
    fun aRowCarriesItsReasoningTierAndVariants() = runTest(UnconfinedTestDispatcher()) {
        val host = host(backgroundScope)
        host.picker.ensureLoaded()

        val codex = host.picker.state.value.groups.single { it.key == "chatgpt-plus-pro" }.entries.single()

        assertEquals("GPT-5.5", codex.displayName)
        assertEquals(ReasoningTier.MEDIUM, codex.tier)
        assertEquals(listOf("low", "medium", "high"), codex.efforts)
        val sol = host.picker.state.value.groups.single { it.key == "openai" }.entries.single()
        assertNull(sol.tier, "no tag when the host does not say")
    }

    @Test
    fun theCurrentModelIsMarkedFromItsHandleOrAlias() = runTest(UnconfinedTestDispatcher()) {
        val host = host(backgroundScope)
        host.picker.ensureLoaded()

        host.picker.setSelected("lmstudio/gpt-5.6-sol")

        val selected = host.picker.state.value.selected
        assertEquals("lmstudio/gpt-5.6-sol", selected?.handle?.value)
        assertEquals("lmstudio/gpt-5.6-sol", selected?.value)
        assertEquals(1, host.picker.state.value.groups.sumOf { g -> g.entries.count { it.selected } })

        host.picker.setSelected(null)
        assertNull(host.picker.state.value.selected)
    }

    @Test
    fun aSearchMatchesNamesHandlesAndProviderTitlesAndUnfoldsGroups() = runTest(UnconfinedTestDispatcher()) {
        val host = host(backgroundScope)
        host.picker.ensureLoaded()
        host.picker.toggleGroup("openai")
        assertTrue(host.picker.state.value.isCollapsed(host.picker.state.value.groups.single { it.key == "openai" }))

        host.picker.setQuery("sol")
        val bySol = host.picker.state.value.visible
        assertEquals(listOf("lmstudio", "openai"), bySol.map { it.key })
        assertEquals(listOf("lmstudio/gpt-5.6-sol"), bySol.first().entries.map { it.handle.value })
        assertTrue(bySol.none { host.picker.state.value.isCollapsed(it) }, "a search unfolds every match")

        host.picker.setQuery("codex")
        assertEquals(listOf("chatgpt-plus-pro"), host.picker.state.value.visible.map { it.key })

        host.picker.setQuery("nothing-like-this")
        assertTrue(host.picker.state.value.visible.isEmpty())
    }

    @Test
    fun foldingAGroupIsRememberedOutsideASearch() = runTest(UnconfinedTestDispatcher()) {
        val host = host(backgroundScope)
        host.picker.ensureLoaded()
        val lmstudio = { host.picker.state.value.groups.single { it.key == "lmstudio" } }

        host.picker.toggleGroup("lmstudio")
        assertTrue(host.picker.state.value.isCollapsed(lmstudio()))
        host.picker.toggleGroup("lmstudio")
        assertFalse(host.picker.state.value.isCollapsed(lmstudio()))
    }

    @Test
    fun refreshForcesTheHostToRequeryItsProviders() = runTest(UnconfinedTestDispatcher()) {
        val host = host(backgroundScope)
        host.picker.ensureLoaded()
        host.invoker.calls.clear()

        host.picker.refresh()

        val modelList = host.invoker.calls.single { it.first == "model.list" }.second
        assertEquals(true, (modelList["force"] as JsonPrimitive).booleanOrNull)
        assertEquals(true, (modelList["include_hidden"] as JsonPrimitive).booleanOrNull)
        assertTrue(host.invoker.calls.any { it.first == "provider.list" })
        assertFalse(host.picker.state.value.refreshing)
        assertNull(host.picker.state.value.error)
    }

    @Test
    fun aFailedRefreshReportsTheErrorAndKeepsTheList() = runTest(UnconfinedTestDispatcher()) {
        var failModels = false
        val invoker = ProviderControlFixtures.invoker(
            models = { if (failModels) error("upstream timed out") else ProviderControlFixtures.modelList },
        )
        val host = host(backgroundScope, invoker)
        host.picker.ensureLoaded()
        failModels = true

        host.picker.refresh()

        val state = host.picker.state.value
        assertEquals("Couldn't refresh models: upstream timed out", state.error)
        assertFalse(state.refreshing)
        assertEquals(5, state.modelCount)

        host.picker.clearError()
        assertNull(host.picker.state.value.error)
    }

    @Test
    fun aFailedProviderListingStillRefreshesTheModels() = runTest(UnconfinedTestDispatcher()) {
        val invoker = ProviderControlFixtures.invoker(providers = { error("provider.list unavailable") })
        val host = host(backgroundScope, invoker)

        host.picker.refresh()

        assertNull(host.picker.state.value.error)
        assertEquals(5, host.picker.state.value.modelCount)
        assertTrue("lmstudio" in host.picker.state.value.groups.map { it.title }, "groups fall back to route keys")
    }

    @Test
    fun exposureTogglesInTheModelsSheetShowInThePickerAtOnce() = runTest(UnconfinedTestDispatcher()) {
        val host = host(backgroundScope)
        host.picker.ensureLoaded()
        host.management.refresh()

        host.management.setExposed(ExposureChange(ModelHandle("lmstudio/minimax-m3"), exposed = true))
        val lmstudio = host.picker.state.value.groups.single { it.key == "lmstudio" }
        assertTrue("lmstudio/minimax-m3" in lmstudio.entries.map { it.handle.value })

        host.management.setExposed(ExposureChange(ModelHandle("chatgpt-plus-pro/gpt-5.5"), exposed = false))
        assertFalse(
            "chatgpt-plus-pro" in host.picker.state.value.groups.map { it.key },
            "a provider with nothing shown leaves the picker",
        )
    }

    @Test
    fun aBackendWithoutTheCatalogListsEveryModelAndCannotEdit() = runTest(UnconfinedTestDispatcher()) {
        val models = MutableStateFlow(
            listOf(
                LlmModel(id = "anthropic/claude", name = "Claude", handle = "anthropic/claude", providerType = "anthropic"),
                LlmModel(id = "openai/gpt", name = "GPT", handle = "openai/gpt", providerType = "openai"),
            ),
        )
        val reloads = mutableListOf<Boolean>()
        val picker = ModelPickerController(backgroundScope, ModelPickerSource.of(models) { reloads += it })

        picker.refresh()

        assertEquals(listOf(true), reloads)
        assertFalse(picker.state.value.canEditModels)
        assertEquals(listOf("anthropic", "openai"), picker.state.value.groups.map { it.key }.sorted())
    }

    @Test
    fun aHostWithoutTheAdminCatalogFallsBackToTheChatsModels() = runTest(UnconfinedTestDispatcher()) {
        var adminWorks = false
        val invoker = ProviderControlFixtures.invoker(
            models = { if (adminWorks) ProviderControlFixtures.modelList else error("Not connected to a host") },
        )
        val chatModels = MutableStateFlow(listOf(LlmModel(id = "letta/letta-free", name = "letta-free", handle = "letta/letta-free")))
        val session = ModelControlSession(invoker)
        val source = ModelPickerSource.withFallback(session.pickerSource(), ModelPickerSource.of(chatModels) { })
        val picker = ModelPickerController(backgroundScope, source)

        picker.ensureLoaded()
        assertEquals(listOf("letta/letta-free"), picker.state.value.groups.flatMap { g -> g.entries.map { it.handle.value } })
        assertFalse(picker.state.value.canEditModels)
        assertNull(picker.state.value.error, "the fallback answered")

        adminWorks = true
        picker.refresh()
        assertEquals(5, picker.state.value.modelCount)
        assertTrue(picker.state.value.canEditModels)
    }

    @Test
    fun tiersReadUpstreamEffortLiterals() {
        assertEquals(ReasoningTier.XHIGH, ReasoningTier.of("xhigh"))
        assertEquals(ReasoningTier.MEDIUM, ReasoningTier.of(" Medium "))
        assertNull(ReasoningTier.of("turbo"))
        assertNull(ReasoningTier.of(null))
    }
}
