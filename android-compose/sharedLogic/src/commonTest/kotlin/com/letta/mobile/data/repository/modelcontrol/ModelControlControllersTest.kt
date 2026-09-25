package com.letta.mobile.data.repository.modelcontrol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonNull

@OptIn(ExperimentalCoroutinesApi::class)
class ModelControlControllersTest {
    @Test
    fun providerControllerListsConnectedFirstAndConnectsThroughTheForm() = runTest(UnconfinedTestDispatcher()) {
        val invoker = RecordingInvoker { method, _ ->
            if (method == "provider.list") ModelControlFixtures.providerList else ModelControlFixtures.mutation(true)
        }
        val controller = ProviderAdminController(backgroundScope, ProviderConnectionRepository(invoker))

        controller.refresh()
        assertEquals("lmstudio", controller.state.value.sortedProviders.first().id)

        val bedrock = controller.state.value.providers.first { it.id == "amazon-bedrock" }
        controller.openConnect(bedrock)
        controller.updateForm(
            controller.state.value.form!!
                .withValue("accessKey", "AKIA")
                .withValue("apiKey", "fixture-secret")
                .withValue("region", "us-east-1"),
        )
        controller.submitConnect()

        assertEquals("provider.connect", invoker.calls.last().first)
        assertNull(controller.state.value.form)
        assertEquals("Amazon Bedrock connected", controller.state.value.message)
    }

    @Test
    fun providerControllerDisconnectsOnlyAfterConfirmation() = runTest(UnconfinedTestDispatcher()) {
        val invoker = RecordingInvoker { method, _ ->
            if (method == "provider.list") ModelControlFixtures.providerList else ModelControlFixtures.mutation(true)
        }
        val controller = ProviderAdminController(backgroundScope, ProviderConnectionRepository(invoker))
        controller.refresh()
        val lmstudio = controller.state.value.providers.first { it.id == "lmstudio" }

        controller.requestDisconnect(lmstudio)
        assertEquals(1, invoker.calls.size)
        controller.confirmDisconnect()

        assertEquals("provider.disconnect", invoker.calls.last().first)
        assertNull(controller.state.value.pendingDisconnect)
    }

    @Test
    fun providerControllerSurfacesFailures() = runTest(UnconfinedTestDispatcher()) {
        val controller = ProviderAdminController(
            backgroundScope,
            ProviderConnectionRepository(RecordingInvoker { _, _ -> throw ModelControlException("offline") }),
        )

        controller.refresh()

        assertTrue(controller.state.value.error!!.contains("offline"))
    }

    @Test
    fun exposureControllerSplitsExposedAndHidden() = runTest(UnconfinedTestDispatcher()) {
        val invoker = RecordingInvoker { method, _ -> if (method == "model.list") ModelControlFixtures.modelList else JsonNull }
        val controller = ModelExposureController(backgroundScope, ModelCatalogRepository(invoker))

        controller.refresh()
        assertEquals(listOf("openai/gpt-sol"), controller.state.value.exposed.map { it.handle.value })
        assertEquals(listOf("lmstudio/minimax-m3"), controller.state.value.hidden.map { it.handle.value })

        controller.setExposed(ExposureChange(ModelHandle("lmstudio/minimax-m3"), true))
        assertTrue(controller.state.value.hidden.isEmpty())
    }
}
