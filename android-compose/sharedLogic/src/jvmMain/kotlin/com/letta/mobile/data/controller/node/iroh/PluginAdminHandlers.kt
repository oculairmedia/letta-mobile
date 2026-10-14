package com.letta.mobile.data.controller.node.iroh

import com.letta.mobile.data.plugin.InstalledPlugin
import com.letta.mobile.data.plugin.PluginCapability
import com.letta.mobile.data.plugin.PluginHealth
import com.letta.mobile.data.plugin.PluginPackageRef
import com.letta.mobile.data.plugin.PluginRuntime
import com.letta.mobile.data.plugin.PluginState
import com.letta.mobile.data.plugin.catalog.PluginAdminRpc
import com.letta.mobile.data.plugin.catalog.PluginInspectWire
import com.letta.mobile.data.plugin.catalog.PluginPageWire
import com.letta.mobile.data.plugin.catalog.PluginPluginsWire
import com.letta.mobile.data.plugin.catalog.PluginRequestWire
import com.letta.mobile.data.plugin.catalog.PluginSummaryWire
import com.letta.mobile.data.plugin.catalog.PluginViewActionWire
import com.letta.mobile.data.plugin.catalog.PluginViewAnswerWire
import com.letta.mobile.data.plugin.host.PluginDriverState
import com.letta.mobile.data.plugin.host.PluginHostResult
import com.letta.mobile.data.plugin.host.PluginHostRuntime
import com.letta.mobile.data.plugin.host.PluginViewCalls
import com.letta.mobile.data.plugin.host.PluginViewRefusal
import com.letta.mobile.data.plugin.view.PluginViewActionCall
import com.letta.mobile.data.plugin.view.PluginViewActionOutcome
import com.letta.mobile.data.storage.AssetStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.io.encoding.Base64

/**
 * letta-mobile-cdgpr.7: the host's `plugins.*` admin RPCs over its [PluginHostRuntime]. A package
 * to install arrives as an asset already uploaded to this host's [assets] (the canvas relay's
 * store) and is pinned by the sha256 the client computed; every management write answers with the
 * registry after it, so a client's list stays current without a second call. Live views read pages
 * and run actions through [PluginViewCalls], which holds them to the manifest.
 *
 * Registered only when the host runs plugins, and only then is [PluginAdminRpc.CAPABILITY]
 * advertised; otherwise [AdminRpcRegistry] answers these methods `capability_unavailable`.
 */
class PluginAdminHandlers(private val runtime: PluginHostRuntime, private val assets: AssetStore) : AdminRpcRoutes {
    private val views = PluginViewCalls(runtime)

    override fun register(router: AdminRpcRouter) {
        router.register(PluginAdminRpc.LIST) { listed() }
        router.register(PluginAdminRpc.INSPECT) { params -> inspected(pluginId(params)) }
        router.register(PluginAdminRpc.INSTALL) { params -> install(params) }
        router.register(PluginAdminRpc.CONSENT) { params ->
            written { runtime.consent(pluginId(params), capabilities(params), strings(params, "origins").toSet()) }
        }
        router.register(PluginAdminRpc.ENABLE) { params -> written { runtime.enable(pluginId(params)) } }
        router.register(PluginAdminRpc.DISABLE) { params -> written { runtime.disable(pluginId(params)) } }
        router.register(PluginAdminRpc.REMOVE) { params -> written { runtime.remove(pluginId(params)) } }
        router.register(PluginAdminRpc.DISABLE_ALL) {
            runtime.disableAll()
            listed()
        }
        router.register(PluginAdminRpc.PAGE_READ) { params -> page(params) }
        router.register(PluginAdminRpc.VIEW_ACTION) { params -> viewAction(params) }
        router.featureCapabilities += PluginAdminRpc.CAPABILITY
    }

    private suspend fun install(params: JsonObject?): JsonElement {
        val ref = params.requireParam(AdminParamKey(PluginAdminRpc.ASSET_REF))
        val pin = PluginPackageRef.of(params.requireParam(AdminParamKey(PluginAdminRpc.SHA256)))
            ?: adminError("sha256 must be 64 lowercase hex characters")
        val bytes = withContext(Dispatchers.IO) { assets.get(ref) }
            ?: adminError("package_missing: this host holds no asset $ref; upload the package first")
        val before = runtime.state.value.plugins.map { it.id }.toSet()
        return when (val result = runtime.install(bytes, pin)) {
            is PluginHostResult.Refused -> adminError("install_refused: ${result.message}")
            is PluginHostResult.Done -> requireNotNull(result.plugin).id.let { id -> inspected(id, newlyInstalled = id !in before) }
        }
    }

    private suspend fun written(block: suspend () -> PluginHostResult): JsonElement = when (val result = block()) {
        is PluginHostResult.Refused -> adminError("refused: ${result.message}")
        is PluginHostResult.Done -> listed()
    }

    private fun listed(): JsonElement = encode(PluginPluginsWire.serializer(), PluginPluginsWire(summaries()))

    private fun inspected(pluginId: String, newlyInstalled: Boolean = false): JsonElement {
        val plugin = runtime.state.value[pluginId] ?: adminError("not_found: $pluginId is not installed")
        val all = summaries()
        return encode(PluginInspectWire.serializer(), PluginInspectWire(all.first { it.id == pluginId }, request(plugin), all, newlyInstalled))
    }

    private suspend fun page(params: JsonObject?): JsonElement {
        val document = refusalsAsErrors {
            views.page(pluginId(params), params.requireParam(AdminParamKey(PluginAdminRpc.PAGE_ID)), param(params, AdminParamKey(PluginAdminRpc.VERSION)))
        }
        val wire = PluginPageWire(
            document.plugin.id, document.plugin.version, document.pageId,
            Base64.encode(document.bytes), document.page, document.viewActions,
        )
        return encode(PluginPageWire.serializer(), wire)
    }

    private suspend fun viewAction(params: JsonObject?): JsonElement {
        val wire = try {
            PluginAdminRpc.json.decodeFromJsonElement(PluginViewActionWire.serializer(), params ?: JsonObject(emptyMap()))
        } catch (e: SerializationException) {
            adminError("invalid_params: ${e.message}")
        }
        val outcome = refusalsAsErrors { views.action(PluginViewActionCall(wire.pluginId, wire.canvasId, wire.elementId, wire.action, wire.input)) }
        val answer = when (outcome) {
            is PluginViewActionOutcome.Done -> PluginViewAnswerWire(PluginViewAnswerWire.DONE, text = outcome.text, structured = outcome.structured)
            is PluginViewActionOutcome.Failed -> PluginViewAnswerWire(PluginViewAnswerWire.FAILED, message = outcome.message)
            is PluginViewActionOutcome.Unavailable -> PluginViewAnswerWire(PluginViewAnswerWire.UNAVAILABLE, message = outcome.reason)
        }
        return encode(PluginViewAnswerWire.serializer(), answer)
    }

    private fun summaries(): List<PluginSummaryWire> {
        val drivers = runtime.health().associate { it.pluginId to it.driver }
        return runtime.state.value.plugins.map { plugin ->
            PluginSummaryWire(plugin.id, plugin.manifest.name, plugin.version, plugin.state, healthOf(plugin, drivers[plugin.id]), plugin.consented, plugin.lastFault)
        }
    }

    private companion object {
        fun pluginId(params: JsonObject?): String = params.requireParam(AdminParamKey(PluginAdminRpc.PLUGIN_ID))

        /** The health to show: the driver's view when one runs, else the registry's. */
        fun healthOf(plugin: InstalledPlugin, driver: PluginDriverState?): PluginHealth = when (driver) {
            is PluginDriverState.Running -> plugin.health.takeUnless { it == PluginHealth.Unknown } ?: PluginHealth.Ok
            is PluginDriverState.Restarting -> PluginHealth.Degraded(driver.reason)
            is PluginDriverState.Faulted -> PluginHealth.Failed(driver.reason)
            else -> (plugin.state as? PluginState.Faulted)?.let { PluginHealth.Failed(it.reason) } ?: plugin.health
        }

        fun request(plugin: InstalledPlugin): PluginRequestWire = with(plugin.manifest) {
            PluginRequestWire(
                id, name, version, publisher, plugin.packageSha256, PluginRuntime.kindOf(runtime),
                capabilities, net.connect, pages.keys.toList(),
            )
        }

        fun strings(params: JsonObject?, key: String): List<String> =
            (params?.get(key) as? JsonArray).orEmpty().map { (it as? JsonPrimitive)?.content ?: adminError("$key must be a list of strings") }

        fun capabilities(params: JsonObject?): Set<PluginCapability> = strings(params, "capabilities").mapTo(mutableSetOf()) { wire ->
            PluginCapability.entries.firstOrNull { it.wire == wire } ?: adminError("unknown capability '$wire'")
        }

        inline fun <T> refusalsAsErrors(block: () -> T): T = try {
            block()
        } catch (e: PluginViewRefusal) {
            adminError("refused: ${e.message}")
        }

        fun <T> encode(serializer: KSerializer<T>, value: T): JsonElement = PluginAdminRpc.json.encodeToJsonElement(serializer, value)
    }
}
