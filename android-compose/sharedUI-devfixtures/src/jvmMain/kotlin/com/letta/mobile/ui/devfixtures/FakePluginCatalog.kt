package com.letta.mobile.ui.devfixtures

import com.letta.mobile.data.plugin.PluginCapability
import com.letta.mobile.data.plugin.PluginHealth
import com.letta.mobile.data.plugin.PluginState
import com.letta.mobile.data.plugin.catalog.PluginCatalog
import com.letta.mobile.data.plugin.catalog.PluginCatalogResult
import com.letta.mobile.data.plugin.catalog.PluginGrants
import com.letta.mobile.data.plugin.catalog.PluginId
import com.letta.mobile.data.plugin.catalog.PluginInstallRequest
import com.letta.mobile.data.plugin.catalog.PluginSummary
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/**
 * letta-mobile-cdgpr.9: an in-memory [PluginCatalog] for the Plugins page's tests and previews until
 * C7's admin_rpc catalog lands. Every call answers at once (deterministic) and is recorded in [calls].
 * An upload stages [staged] under the uploaded hash; consent installs it only when the grants cover
 * everything it asks for, as the host does.
 */
class FakePluginCatalog(
    installed: List<PluginSummary> = emptyList(),
    var staged: PluginInstallRequest = ChartPluginRequest,
    var refuse: String? = null,
) : PluginCatalog {
    private val list = MutableStateFlow(installed)
    override val plugins: StateFlow<List<PluginSummary>> = list
    val calls = mutableListOf<String>()

    override suspend fun install(fileBytes: ByteArray, sha256: String): PluginCatalogResult<PluginInstallRequest> =
        answer("install:$sha256") { staged.copy(sha256 = sha256) }

    override suspend fun consent(id: PluginId, grants: PluginGrants): PluginCatalogResult<Unit> {
        calls += "consent:$id"
        val request = staged
        val covers = grants.capabilities.containsAll(request.capabilities) && grants.origins.containsAll(request.origins)
        if (!covers) return PluginCatalogResult.Refused("consent to what $id asks for before installing it")
        list.update { it + PluginSummary(request.id, request.name, request.version, PluginState.Installed) }
        return PluginCatalogResult.Ok(Unit)
    }

    override suspend fun cancelInstall(id: PluginId) = answer("cancel:$id") {}

    override suspend fun enable(id: PluginId) = answer("enable:$id") { set(id, PluginState.Enabled) }

    override suspend fun disable(id: PluginId) = answer("disable:$id") { set(id, PluginState.Installed) }

    override suspend fun remove(id: PluginId) = answer("remove:$id") { list.update { all -> all.filterNot { it.id == id } } }

    override suspend fun disableAll() = answer("disableAll") {
        list.update { all -> all.map { it.copy(state = PluginState.Installed) } }
    }

    override suspend fun health(id: PluginId) = answer("health:$id") { list.value.first { it.id == id }.health }

    private fun set(id: PluginId, state: PluginState) = list.update { all -> all.map { if (it.id == id) it.copy(state = state) else it } }

    private inline fun <T> answer(call: String, block: () -> T): PluginCatalogResult<T> {
        calls += call
        return refuse?.let { PluginCatalogResult.Refused(it) } ?: PluginCatalogResult.Ok(block())
    }

    companion object {
        /** What the reference chart plugin (letta.chart) asks for. */
        val ChartPluginRequest = PluginInstallRequest(
            id = PluginId("letta.chart"),
            name = "Chart",
            version = "1.0.0",
            publisher = "Letta",
            sha256 = "",
            runtimeKind = "process",
            capabilities = listOf(PluginCapability.CANVAS_PLACE, PluginCapability.ASSETS_WRITE, PluginCapability.UI_PAGES),
            pages = listOf("pages/chart.html"),
        )

        /** A faulted and an enabled plugin. */
        val SamplePlugins = listOf(
            PluginSummary(PluginId("letta.chart"), "Chart", "1.0.0", PluginState.Enabled, PluginHealth.Ok),
            PluginSummary(PluginId("letta.clock"), "Clock", "0.2.0", PluginState.Faulted("crashed 3 times"), PluginHealth.Failed("exit 1")),
        )
    }
}
