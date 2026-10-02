package com.letta.mobile.data.repository.modelcontrol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

@OptIn(ExperimentalCoroutinesApi::class)
class ProviderSettingsTest {
    private fun controller(scope: CoroutineScope, invoker: RecordingInvoker) =
        ModelControlSession(invoker).managementController(scope)

    private fun ProviderManagementController.provider(id: String) = state.value.providers.single { it.id == id }

    @Test
    fun providersSortIntoAccountsKeysAndEndpoints() = runTest(UnconfinedTestDispatcher()) {
        val controller = controller(backgroundScope, ProviderControlFixtures.invoker())
        controller.refresh()

        val lists = controller.state.value.settingsLists

        assertEquals(listOf("anthropic-oauth"), lists.accounts.map { it.id })
        assertEquals(listOf("lmstudio", "openai", "openai-codex-oauth"), lists.connected.map { it.id })
        assertEquals(listOf("amazon-bedrock", "anthropic", "openai-compatible"), lists.other.map { it.id })
        assertEquals(listOf("amazon-bedrock", "anthropic", "openai"), lists.apiKeys.map { it.id })
        assertEquals(listOf("lmstudio", "openai-compatible"), lists.endpoints.map { it.id })
    }

    @Test
    fun connectMethodsFollowWhatTheHostAccepts() = runTest(UnconfinedTestDispatcher()) {
        val controller = controller(backgroundScope, ProviderControlFixtures.invoker())
        controller.refresh()

        assertEquals(ProviderConnectMethod.TERMINAL_SIGN_IN, controller.provider("openai-codex-oauth").connectMethod)
        assertEquals(ProviderConnectMethod.API_KEY, controller.provider("anthropic").connectMethod)
        assertEquals(ProviderConnectMethod.ENDPOINT, controller.provider("openai-compatible").connectMethod)
        assertEquals("http://127.0.0.1:8082/v1", controller.provider("lmstudio").connectedBaseUrl)
    }

    @Test
    fun aTerminalSignInIsCheckedUntilTheAccountShowsUp() = runTest(UnconfinedTestDispatcher()) {
        var signedIn = false
        val invoker = ProviderControlFixtures.invoker(
            providers = {
                if (signedIn) ProviderControlFixtures.providersConnecting("anthropic-oauth") else ProviderControlFixtures.providerList
            },
        )
        val controller = controller(backgroundScope, invoker)
        controller.refresh()
        controller.openSignIn(controller.provider("anthropic-oauth"))

        controller.checkSignIn()
        assertNotNull(controller.state.value.signIn, "still waiting for the terminal")
        assertTrue(controller.state.value.message.orEmpty().contains("isn't connected yet"))

        signedIn = true
        invoker.calls.clear()
        controller.checkSignIn()

        assertNull(controller.state.value.signIn)
        assertEquals("Anthropic (Claude Pro/Max) connected", controller.state.value.message)
        val modelList = invoker.calls.single { it.first == "model.list" }.second
        assertEquals(true, (modelList["force"] as JsonPrimitive).booleanOrNull, "its models are re-queried")
        assertTrue(controller.state.value.settingsLists.connected.any { it.id == "anthropic-oauth" })
    }

    @Test
    fun aCustomEndpointIsAddedWithItsBaseUrlAndKey() = runTest(UnconfinedTestDispatcher()) {
        val invoker = ProviderControlFixtures.invoker()
        val controller = controller(backgroundScope, invoker)
        controller.refresh()
        controller.openConnect(controller.provider("openai-compatible"))

        assertEquals(false, controller.state.value.form?.canSubmit, "the base URL is required")
        controller.updateForm(
            controller.state.value.form!!.withValue("baseUrl", " https://llm.example/v1 ").withValue("apiKey", "sk-test"),
        )
        invoker.calls.clear()
        controller.submitConnect()

        val params = invoker.calls.first { it.first == "provider.connect" }.second
        assertEquals("openai-compatible", (params["provider_id"] as JsonPrimitive).contentOrNull)
        val fields = params["fields"] as JsonObject
        assertEquals("https://llm.example/v1", (fields["baseUrl"] as JsonPrimitive).contentOrNull)
        assertEquals("sk-test", (fields["apiKey"] as JsonPrimitive).contentOrNull)
        assertNull(params["provider_name"], "the host refuses a named endpoint without OAuth tokens")
        assertNull(controller.state.value.form)
        assertTrue(invoker.calls.any { it.first == "model.list" }, "the catalog is re-read after a connect")
    }

    @Test
    fun editingAnEndpointKeepsItsBaseUrlButNeverAKey() = runTest(UnconfinedTestDispatcher()) {
        val controller = controller(backgroundScope, ProviderControlFixtures.invoker())
        controller.refresh()

        controller.openEdit(controller.provider("lmstudio"))

        val form = assertNotNull(controller.state.value.form)
        assertEquals("http://127.0.0.1:8082/v1", form.values["baseUrl"])
        assertNull(form.values["apiKey"])
    }

    @Test
    fun removingAnEndpointDisconnectsItsConnectedAlias() = runTest(UnconfinedTestDispatcher()) {
        val invoker = ProviderControlFixtures.invoker()
        val controller = controller(backgroundScope, invoker)
        controller.refresh()

        controller.requestDisconnect(controller.provider("lmstudio"))
        controller.confirmDisconnect()

        val params = invoker.calls.single { it.first == "provider.disconnect" }.second
        assertEquals("lmstudio", (params["provider_id"] as JsonPrimitive).contentOrNull)
        assertEquals("lc-lmstudio", (params["provider_name"] as JsonPrimitive).contentOrNull)
        assertEquals("LM Studio (local) disconnected", controller.state.value.message)
    }

    @Test
    fun aRejectedKeyKeepsTheFormOpenWithTheHostsReason() = runTest(UnconfinedTestDispatcher()) {
        val invoker = ProviderControlFixtures.invoker(onOther = { error("Invalid Anthropic API key") })
        val controller = controller(backgroundScope, invoker)
        controller.refresh()
        controller.openConnect(controller.provider("anthropic"))
        controller.updateForm(controller.state.value.form!!.withValue("apiKey", "sk-bad"))

        controller.submitConnect()

        assertNotNull(controller.state.value.form)
        assertEquals("Couldn't connect Anthropic: Invalid Anthropic API key", controller.state.value.error)
        assertEquals(false, controller.state.value.busy)
    }
}
