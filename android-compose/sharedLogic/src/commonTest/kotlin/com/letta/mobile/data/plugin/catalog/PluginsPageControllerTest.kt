package com.letta.mobile.data.plugin.catalog

import com.letta.mobile.data.plugin.PluginCapability
import com.letta.mobile.data.plugin.PluginHealth
import com.letta.mobile.data.plugin.PluginState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** letta-mobile-cdgpr.9: the Plugins page's calls over a catalog - install with consent, one call at a time, refusals. */
@OptIn(ExperimentalCoroutinesApi::class)
class PluginsPageControllerTest {
    private val bytes = "package".encodeToByteArray()
    private val sha = pluginPackageSha256(bytes)
    private val request = PluginInstallRequest(
        id = PluginId("letta.chart"), name = "Chart", version = "1.0.0", publisher = "Letta", sha256 = sha, runtimeKind = "process",
        capabilities = listOf(PluginCapability.CANVAS_PLACE),
    )
    private val chart = PluginId("letta.chart")
    private val canvas = PluginConsentItem.Capability(PluginCapability.CANVAS_PLACE)

    private fun TestScope.controller(catalog: PluginCatalog, liveViews: Boolean = false) =
        PluginsPageController(catalog, backgroundScope, inMemoryLiveViewsSetting(liveViews), hashing = EmptyCoroutineContext)

    private fun TestScope.staged(catalog: RecordingCatalog): PluginsPageController {
        val controller = controller(catalog)
        controller.pick(PluginPackageFile("chart.zip", bytes))
        runCurrent()
        controller.uploadPicked()
        runCurrent()
        return controller
    }

    @Test
    fun aPickedFileIsHashedAndNotUploadedUntilAsked() = runTest {
        val catalog = RecordingCatalog(request)
        val controller = controller(catalog)
        controller.pick(PluginPackageFile("chart.zip", bytes))
        runCurrent()
        assertEquals(PickedPluginPackage("chart.zip", bytes.size, sha), controller.state.value.picked)
        assertTrue(catalog.calls.isEmpty())
        controller.uploadPicked()
        runCurrent()
        assertEquals(listOf("install:$sha"), catalog.calls)
        assertEquals(request, controller.state.value.consent?.request)
        assertNull(controller.state.value.picked)
    }

    @Test
    fun aDenyNeverReachesTheHostAndDropsTheStagedPackage() = runTest {
        val catalog = RecordingCatalog(request)
        val controller = staged(catalog)
        controller.decide(canvas, allow = false)
        controller.installConsented()
        runCurrent()
        assertEquals(listOf("install:$sha", "cancel:letta.chart"), catalog.calls)
        assertNull(controller.state.value.consent)
        assertTrue(controller.state.value.error!!.startsWith("Install refused: you denied canvas:place"))
    }

    @Test
    fun allowingEverythingSendsExactlyTheGrants() = runTest {
        val catalog = RecordingCatalog(request)
        val controller = staged(catalog)
        controller.installConsented()
        runCurrent()
        assertEquals(listOf("install:$sha"), catalog.calls, "install waits for every decision")
        controller.decide(canvas, allow = true)
        controller.installConsented()
        runCurrent()
        assertEquals(PluginGrants(setOf(PluginCapability.CANVAS_PLACE), emptySet(), emptySet()), catalog.grants)
        assertNull(controller.state.value.consent)
        assertNull(controller.state.value.error)
    }

    @Test
    fun theHostsConsentRefusalIsShown() = runTest {
        val catalog = RecordingCatalog(request, consentRefusal = "consent hash mismatch")
        val controller = staged(catalog)
        controller.decide(canvas, allow = true)
        controller.installConsented()
        runCurrent()
        assertEquals("consent hash mismatch", controller.state.value.error)
        assertNull(controller.state.value.consent)
    }

    @Test
    fun aPackageTheHostHashedDifferentlyIsRefused() = runTest {
        val catalog = RecordingCatalog(request.copy(sha256 = "f".repeat(64)))
        val controller = staged(catalog)
        assertNull(controller.state.value.consent)
        assertTrue("cancel:letta.chart" in catalog.calls)
        assertTrue(controller.state.value.error!!.contains("different sha256"))
    }

    @Test
    fun cancellingTheSheetDropsTheStagedPackage() = runTest {
        val catalog = RecordingCatalog(request)
        val controller = staged(catalog)
        controller.cancelConsent()
        runCurrent()
        assertNull(controller.state.value.consent)
        assertEquals("cancel:letta.chart", catalog.calls.last())
    }

    @Test
    fun oneCallAtATime() = runTest {
        val gate = CompletableDeferred<Unit>()
        val catalog = RecordingCatalog(request, gate = gate)
        val controller = controller(catalog)
        controller.setEnabled(chart, on = true)
        runCurrent()
        assertTrue(controller.state.value.busy)
        controller.confirmDisableAll()
        gate.complete(Unit)
        runCurrent()
        assertEquals(listOf("enable:letta.chart"), catalog.calls)
        assertFalse(controller.state.value.busy)
        assertEquals(PluginState.Enabled, controller.state.value.plugins.single().state)
    }

    @Test
    fun disableAllAndRemoveRunOnlyOnTheirConfirmation() = runTest {
        val catalog = RecordingCatalog(request)
        val controller = controller(catalog)
        controller.confirmRemove()
        controller.askDisableAll(true)
        assertTrue(controller.state.value.confirmDisableAll)
        runCurrent()
        assertTrue(catalog.calls.isEmpty(), "no remove without a pending one")
        controller.confirmDisableAll()
        runCurrent()
        controller.askRemove(chart)
        controller.confirmRemove()
        runCurrent()
        assertEquals(listOf("disableAll", "remove:letta.chart"), catalog.calls)
        assertFalse(controller.state.value.confirmDisableAll)
        assertNull(controller.state.value.pendingRemove)
    }

    @Test
    fun aThrowingCatalogReadsAsARefusal() = runTest {
        val controller = controller(RecordingCatalog(request, throwing = IllegalStateException("socket closed")))
        controller.checkHealth(chart)
        runCurrent()
        assertEquals("socket closed", controller.state.value.error)
        controller.dismissError()
        assertNull(controller.state.value.error)
    }

    @Test
    fun anOversizedPackageIsRefusedBeforeHashing() = runTest {
        val controller = controller(RecordingCatalog(request))
        controller.pick(PluginPackageFile("huge.zip", ByteArray(16 * 1024 * 1024 + 1)))
        runCurrent()
        assertNull(controller.state.value.picked)
        assertNotNull(controller.state.value.error)
    }

    @Test
    fun theLiveViewsSwitchWritesThroughToTheSetting() = runTest {
        val setting = inMemoryLiveViewsSetting(false)
        val controller = PluginsPageController(RecordingCatalog(request), backgroundScope, setting, EmptyCoroutineContext)
        controller.setLiveViews(true)
        runCurrent()
        assertTrue(setting.current.value)
        assertTrue(controller.state.value.liveViews)
    }

    @Test
    fun theListFollowsTheCatalog() = runTest {
        val catalog = RecordingCatalog(request)
        val controller = controller(catalog)
        runCurrent()
        catalog.list.update { it + PluginSummary(PluginId("letta.clock"), "Clock", "0.1.0", PluginState.Faulted("boom")) }
        runCurrent()
        assertEquals(listOf("letta.chart", "letta.clock"), controller.state.value.plugins.map { it.id.value })
    }

    private companion object {
        val chartId = PluginId("letta.chart")
    }

    /** Records every call; [gate] holds each answer until it completes. */
    private class RecordingCatalog(
        private val staged: PluginInstallRequest,
        private val consentRefusal: String? = null,
        private val gate: CompletableDeferred<Unit>? = null,
        private val throwing: Throwable? = null,
    ) : PluginCatalog {
        val list = MutableStateFlow(listOf(PluginSummary(chartId, "Chart", "1.0.0", PluginState.Installed)))
        override val plugins = list
        val calls = mutableListOf<String>()
        var grants: PluginGrants? = null

        private suspend fun <T> answer(call: String, value: () -> T): PluginCatalogResult<T> {
            calls += call
            gate?.await()
            throwing?.let { throw it }
            return PluginCatalogResult.Ok(value())
        }

        override suspend fun install(fileBytes: ByteArray, sha256: String) = answer("install:$sha256") { staged }

        override suspend fun consent(id: PluginId, grants: PluginGrants): PluginCatalogResult<Unit> {
            this.grants = grants
            return consentRefusal?.let { PluginCatalogResult.Refused(it) } ?: answer("consent:$id") {}
        }

        override suspend fun cancelInstall(id: PluginId) = answer("cancel:$id") {}

        override suspend fun enable(id: PluginId) = answer("enable:$id") { set(id, PluginState.Enabled) }

        override suspend fun disable(id: PluginId) = answer("disable:$id") { set(id, PluginState.Installed) }

        override suspend fun remove(id: PluginId) = answer("remove:$id") {}

        override suspend fun disableAll() = answer("disableAll") {}

        override suspend fun health(id: PluginId) = answer("health:$id") { PluginHealth.Ok }

        private fun set(id: PluginId, state: PluginState) = list.update { all -> all.map { if (it.id == id) it.copy(state = state) else it } }
    }
}
