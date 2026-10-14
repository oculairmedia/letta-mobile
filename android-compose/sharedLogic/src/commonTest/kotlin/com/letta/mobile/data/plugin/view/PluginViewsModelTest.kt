package com.letta.mobile.data.plugin.view

import com.letta.mobile.data.plugin.PluginContract
import com.letta.mobile.data.plugin.PluginElementKind
import com.letta.mobile.data.plugin.PluginHealth
import com.letta.mobile.data.plugin.PluginManifest
import com.letta.mobile.data.plugin.PluginPage
import com.letta.mobile.data.plugin.PluginRuntime
import com.letta.mobile.data.plugin.PluginState
import com.letta.mobile.data.plugin.catalog.HostPluginCatalog
import com.letta.mobile.data.plugin.catalog.PluginCatalog
import com.letta.mobile.data.plugin.catalog.PluginCatalogResult
import com.letta.mobile.data.plugin.catalog.PluginId
import com.letta.mobile.data.plugin.catalog.PluginSummary
import com.letta.mobile.data.plugin.catalog.UnavailablePluginCatalog
import com.letta.mobile.data.plugin.catalog.inMemoryLiveViewsSetting
import com.letta.mobile.data.plugin.catalog.pluginsSettingsContributionOrNull
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** letta-mobile-cdgpr.8: what [PluginViewsModel] hands the shells from the host's catalog. */
class PluginViewsModelTest {
    @Test
    fun onlyEnabledHealthyPluginsWithAManifestRenderLive() = runTest {
        val host = FakeHost(
            listOf(
                summary("letta.chart", PluginState.Active, PluginHealth.Ok),
                summary("letta.clock", PluginState.Enabled),
                summary("letta.off", PluginState.Installed, PluginHealth.Ok),
                summary("letta.sick", PluginState.Active, PluginHealth.Failed("exit 1")),
                summary("letta.slow", PluginState.Active, PluginHealth.Degraded("restarting")),
                summary("letta.broke", PluginState.Faulted("crashed 3 times")),
                summary("letta.bare", PluginState.Active, PluginHealth.Ok).copy(manifest = null),
            ),
        )
        val model = model(host)

        assertTrue(model.refresh())
        runCurrent()

        assertEquals(listOf("letta.chart", "letta.clock"), model.plugins.value.map { it.manifest.id })
        assertEquals(mapOf("chart" to "chart"), model.plugins.value.first().livePages)
        assertSame(host, model.catalog)
        assertNotNull(model.transport)
    }

    @Test
    fun theKindsOfTheLivePluginsAreKnownAndNoOthers() = runTest {
        val host = FakeHost(listOf(summary("letta.chart", PluginState.Active), summary("letta.off", PluginState.Installed)))
        val model = model(host)
        assertNull(model.kinds.schemaFor("ext:letta.chart/chart", 1))

        model.refresh()
        runCurrent()

        assertEquals(CHART_PROPS, model.kinds.schemaFor("ext:letta.chart/chart", 1))
        assertTrue(model.kinds.knows("letta.chart"))
        assertFalse(model.kinds.knows("letta.off"))
        assertNull(model.kinds.schemaFor("ext:letta.off/chart", 1))

        host.list.value = emptyList()
        runCurrent()
        assertFalse(model.kinds.knows("letta.chart"), "the kinds follow the host's list")
    }

    @Test
    fun liveViewsOffMeansNoViewHostSoEveryElementDrawsItsCard() = runTest {
        val model = model(FakeHost(emptyList()), liveViews = true)
        val viewHost = Any()
        assertSame(viewHost, PluginViewsModel.viewHost(viewHost, model.liveViewsEnabled.value))

        model.setLiveViewsEnabled(false)

        assertFalse(model.liveViewsEnabled.value)
        assertNull(PluginViewsModel.viewHost(viewHost, model.liveViewsEnabled.value))
    }

    @Test
    fun aHostWithoutPluginsHidesThePageAndOffersTheUnavailableCatalog() = runTest {
        val host = FakeHost(listOf(summary("letta.chart", PluginState.Active)), runs = false)
        val model = model(host)

        assertFalse(model.refresh())
        runCurrent()

        assertFalse(model.supported.value)
        assertIs<UnavailablePluginCatalog>(model.catalog)
        assertNull(model.transport)
        assertTrue(model.plugins.value.isEmpty())
        assertEquals(0, host.refreshes, "nothing is asked of a host that does not run plugins")
        val entry = { }.takeIf { model.supported.value }
        assertNull(pluginsSettingsContributionOrNull(entry), "no Settings entry")
        assertNotNull(pluginsSettingsContributionOrNull { })
    }

    @Test
    fun noHostAtAllIsUnsupported() = runTest {
        val model = PluginViewsModel(null, null, inMemoryLiveViewsSetting(), backgroundScope)

        assertFalse(model.refresh())
        assertIs<UnavailablePluginCatalog>(model.catalog)
        assertTrue(model.plugins.value.isEmpty())
    }

    @Test
    fun aHostChangeRefreshesAndALostHostDropsItsPlugins() = runTest {
        val host = FakeHost(listOf(summary("letta.chart", PluginState.Active)))
        val changes = MutableSharedFlow<Any?>()
        val model = model(host, changes = changes)
        runCurrent()

        changes.emit("connected")
        runCurrent()
        assertTrue(model.supported.value)
        assertEquals(1, model.plugins.value.size)

        host.runs = false
        changes.emit("reconnected to a host without plugins")
        runCurrent()
        assertFalse(model.supported.value)
        assertTrue(model.plugins.value.isEmpty())
    }

    private fun TestScope.model(host: FakeHost, liveViews: Boolean = false, changes: MutableSharedFlow<Any?> = MutableSharedFlow()) =
        PluginViewsModel(host, PluginViewTransport.Unavailable, inMemoryLiveViewsSetting(liveViews), backgroundScope, changes)

    private class FakeHost(
        private val listed: List<PluginSummary>,
        var runs: Boolean = true,
    ) : HostPluginCatalog, PluginCatalog by UnavailablePluginCatalog() {
        val list = MutableStateFlow<List<PluginSummary>>(emptyList())
        var refreshes = 0
        override val plugins: StateFlow<List<PluginSummary>> = list
        override val supported: Boolean get() = runs

        override suspend fun refresh(): PluginCatalogResult<Unit> {
            refreshes++
            list.value = listed
            return PluginCatalogResult.Ok(Unit)
        }
    }

    private companion object {
        val CHART_PROPS: JsonObject = Json.parseToJsonElement(
            """{"type":"object","additionalProperties":false,"properties":{"title":{"type":"string"}}}""",
        ) as JsonObject

        fun manifest(id: String) = PluginManifest(
            manifestVersion = 1,
            id = id,
            name = id,
            version = "1.0.0",
            publisher = "letta",
            contract = PluginContract(1),
            runtime = PluginRuntime.Process("node", listOf("server.mjs")),
            elements = mapOf("chart" to PluginElementKind(schemaVersion = 1, props = CHART_PROPS, page = "chart")),
            pages = mapOf("chart" to PluginPage(html = "pages/chart.html")),
        )

        fun summary(id: String, state: PluginState, health: PluginHealth = PluginHealth.Unknown) =
            PluginSummary(PluginId(id), id, "1.0.0", state, health, manifest = manifest(id))
    }
}
