package com.letta.mobile.data.repository.modelcontrol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

@OptIn(ExperimentalCoroutinesApi::class)
class ProviderManagementControllerTest {
    private fun controller(scope: kotlinx.coroutines.CoroutineScope, invoker: RecordingInvoker) =
        ProviderManagementController(scope, ProviderConnectionRepository(invoker), ModelCatalogRepository(invoker))

    @Test
    fun refreshLoadsBothListingsAndComposesSections() = runTest(UnconfinedTestDispatcher()) {
        val invoker = ProviderCatalogFixtures.invoker()
        val controller = controller(backgroundScope, invoker)

        controller.refresh()

        assertEquals(setOf("provider.list", "model.list"), invoker.calls.map { it.first }.toSet())
        val state = controller.state.value
        assertEquals(listOf("lmstudio", "openai", "anthropic", "amazon-bedrock", "anthropic-oauth"), state.sections.map { it.key })
        assertEquals(5, state.totalModels)
        assertEquals(4, state.shownModels)
        assertEquals(false, state.loading)
        assertNull(state.error)
    }

    @Test
    fun aSearchNarrowsRowsDropsSilentSectionsAndOpensTheRest() = runTest(UnconfinedTestDispatcher()) {
        val controller = controller(backgroundScope, ProviderCatalogFixtures.invoker())
        controller.refresh()

        controller.setQuery("sol")

        val visible = controller.state.value.visible
        assertEquals(listOf("lmstudio", "openai"), visible.map { it.section.key })
        assertEquals(listOf("lmstudio/gpt-5.6-sol"), visible[0].rows.map { it.handle.value })
        assertTrue(visible.all { controller.state.value.isExpanded(it.section) })
    }

    @Test
    fun aSearchKeepsAProviderWhoseNameMatches() = runTest(UnconfinedTestDispatcher()) {
        val controller = controller(backgroundScope, ProviderCatalogFixtures.invoker())
        controller.refresh()

        controller.setQuery("bedrock")

        val visible = controller.state.value.visible
        assertEquals(listOf("amazon-bedrock"), visible.map { it.section.key })
        assertTrue(visible.single().rows.isEmpty())
    }

    @Test
    fun theHiddenFilterListsOnlyHiddenRows() = runTest(UnconfinedTestDispatcher()) {
        val controller = controller(backgroundScope, ProviderCatalogFixtures.invoker())
        controller.refresh()

        controller.setFilter(ModelVisibilityFilter.HIDDEN)

        val visible = controller.state.value.visible
        assertEquals(listOf("lmstudio"), visible.map { it.section.key })
        assertEquals(listOf("lmstudio/minimax-m3"), visible.single().rows.map { it.handle.value })
    }

    @Test
    fun expansionTogglesPerProviderOutsideASearch() = runTest(UnconfinedTestDispatcher()) {
        val controller = controller(backgroundScope, ProviderCatalogFixtures.invoker())
        controller.refresh()
        val lmstudio = controller.state.value.sections.first()

        assertEquals(false, controller.state.value.isExpanded(lmstudio))
        controller.toggleExpanded("lmstudio")
        assertTrue(controller.state.value.isExpanded(lmstudio))
        controller.toggleExpanded("lmstudio")
        assertEquals(false, controller.state.value.isExpanded(lmstudio))
    }

    @Test
    fun aProviderWideHideBatchesOnlyTheRowsThatChange() = runTest(UnconfinedTestDispatcher()) {
        val invoker = ProviderCatalogFixtures.invoker()
        val controller = controller(backgroundScope, invoker)
        controller.refresh()

        controller.setProviderExposed("lmstudio", exposed = false)

        val (method, params) = invoker.calls.last()
        assertEquals("model.exposure.set", method)
        val batch = params["models"] as JsonObject
        // minimax-m3 was already hidden, so it is not resent.
        assertEquals(setOf("lmstudio/claude-fable-5-1", "lmstudio/gpt-5.6-sol"), batch.keys)
        assertTrue(batch.values.all { (it as JsonPrimitive).booleanOrNull == false })
        val lmstudio = controller.state.value.sections.first { it.key == "lmstudio" }
        assertTrue(lmstudio.noneExposed)
        assertEquals("LM Studio (local): 2 models hidden", controller.state.value.message)
    }

    @Test
    fun aProviderWideToggleThatChangesNothingSendsNothing() = runTest(UnconfinedTestDispatcher()) {
        val invoker = ProviderCatalogFixtures.invoker()
        val controller = controller(backgroundScope, invoker)
        controller.refresh()
        val before = invoker.calls.size

        controller.setProviderExposed("openai", exposed = true)

        assertEquals(before, invoker.calls.size)
    }

    @Test
    fun aRejectedToggleRollsBackAndReportsWithoutBlockingThePane() = runTest(UnconfinedTestDispatcher()) {
        val invoker = ProviderCatalogFixtures.invoker { method ->
            if (method == "model.exposure.set") throw ModelControlException("denied") else null
        }
        val controller = controller(backgroundScope, invoker)
        controller.refresh()

        controller.setExposed(ExposureChange(ModelHandle("openai/gpt-5.6-sol"), exposed = false))

        val state = controller.state.value
        assertTrue(state.sections.first { it.key == "openai" }.models.single().exposed)
        assertEquals("Couldn't update openai/gpt-5.6-sol: denied", state.error)
        assertEquals(false, state.busy)
    }

    @Test
    fun connectingRefreshesTheCatalogWhenModelsMayHaveChanged() = runTest(UnconfinedTestDispatcher()) {
        val invoker = ProviderCatalogFixtures.invoker()
        val controller = controller(backgroundScope, invoker)
        controller.refresh()
        val bedrock = controller.state.value.sections.first { it.key == "amazon-bedrock" }.provider
        assertNotNull(bedrock)

        controller.openConnect(bedrock)
        controller.updateForm(controller.state.value.form!!.withValue("apiKey", "fixture-secret"))
        controller.submitConnect()

        val tail = invoker.calls.map { it.first }.takeLast(2)
        assertEquals(listOf("provider.connect", "model.list"), tail)
        assertEquals(JsonPrimitive(true), invoker.calls.last().second["force"])
        assertNull(controller.state.value.form)
        assertEquals("Amazon Bedrock connected", controller.state.value.message)
    }

    @Test
    fun disconnectWaitsForConfirmationAndPassesTheAlias() = runTest(UnconfinedTestDispatcher()) {
        val invoker = ProviderCatalogFixtures.invoker()
        val controller = controller(backgroundScope, invoker)
        controller.refresh()
        val lmstudio = controller.state.value.sections.first { it.key == "lmstudio" }.provider!!

        controller.requestDisconnect(lmstudio)
        val before = invoker.calls.size
        controller.confirmDisconnect()

        assertTrue(invoker.calls.size > before)
        val disconnect = invoker.calls.first { it.first == "provider.disconnect" }.second
        assertEquals(JsonPrimitive("lc-lmstudio"), disconnect["provider_name"])
        assertNull(controller.state.value.pendingDisconnect)
    }

    @Test
    fun aFailedListingSurfacesAnError() = runTest(UnconfinedTestDispatcher()) {
        val controller = controller(backgroundScope, RecordingInvoker { _, _ -> throw ModelControlException("offline") })

        controller.refresh()

        assertEquals("Couldn't load providers: offline", controller.state.value.error)
        assertEquals(false, controller.state.value.loading)
    }
}
