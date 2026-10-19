package com.letta.mobile.data.plugin.remote

import com.letta.mobile.data.model.PluginId
import com.letta.mobile.data.plugin.PluginHealth
import com.letta.mobile.data.plugin.catalog.PluginAdminRpc
import com.letta.mobile.data.plugin.catalog.HostPluginCatalog
import com.letta.mobile.data.plugin.catalog.PluginCatalogResult
import com.letta.mobile.data.plugin.catalog.PluginGrants
import com.letta.mobile.data.plugin.catalog.PluginInspectWire
import com.letta.mobile.data.plugin.catalog.PluginInstallRequest
import com.letta.mobile.data.plugin.catalog.PluginPluginsWire
import com.letta.mobile.data.plugin.catalog.PluginSummary
import com.letta.mobile.data.plugin.catalog.PluginSummaryWire
import com.letta.mobile.data.repository.IrohAdminRpcClient
import com.letta.mobile.data.serialization.LettaJson
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.concurrent.ConcurrentHashMap

/**
 * The Plugins page's [PluginCatalog] over the host's `plugins.*` admin RPCs.
 * Every management call answers with the host's registry, which becomes [plugins]. Installing
 * uploads the package to the host's asset store ([uploader]) and asks the host to install it from
 * there, pinned by the sha256 the page showed; the host's answer is what the manifest asks for.
 * A plugin this catalog newly installed is removed again if its consent is cancelled; an update
 * awaiting consent stays (disabled) instead.
 *
 * On a host that does not advertise `plugins_v1` ([supported] false) nothing is sent: every call
 * answers [PLUGINS_UNSUPPORTED] and the page should not be offered.
 */
class AdminRpcPluginCatalog(private val link: PluginAdminLink, private val uploader: PluginPackageUploader) : HostPluginCatalog {
    constructor(client: IrohAdminRpcClient, uploader: PluginPackageUploader) : this(client.asPluginAdminLink(), uploader)

    private val state = MutableStateFlow<List<PluginSummary>>(emptyList())
    private val staged: MutableSet<String> = ConcurrentHashMap.newKeySet()

    override val plugins: StateFlow<List<PluginSummary>> = state.asStateFlow()

    /** Whether the connected host runs plugins; read it after connecting (capabilities arrive with auth). */
    override val supported: Boolean get() = link.supportsPlugins

    /** Reloads [plugins] from the host. */
    override suspend fun refresh(): PluginCatalogResult<Unit> = listed(link.call(PluginAdminRpc.LIST))

    override suspend fun install(fileBytes: ByteArray, sha256: String): PluginCatalogResult<PluginInstallRequest> {
        if (!supported) return PluginCatalogResult.Refused(PLUGINS_UNSUPPORTED)
        val ref = try {
            uploader.upload(fileBytes, sha256)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return PluginCatalogResult.Refused("The package could not be uploaded to the host: ${e.message}")
        }
        val params = buildJsonObject {
            put(PluginAdminRpc.ASSET_REF, ref)
            put(PluginAdminRpc.SHA256, sha256)
        }
        return link.call(PluginAdminRpc.INSTALL, params).read(::inspected).read { answer ->
            if (answer.newlyInstalled) staged += answer.plugin.id
            answer.request.toRequest()
        }
    }

    override suspend fun consent(id: PluginId, grants: PluginGrants): PluginCatalogResult<Unit> {
        val params = buildJsonObject {
            put(PluginAdminRpc.PLUGIN_ID, id.value)
            put("capabilities", JsonArray(grants.capabilities.map { JsonPrimitive(it.wire) }))
            put("origins", JsonArray(grants.origins.map(::JsonPrimitive)))
        }
        return listed(link.call(PluginAdminRpc.CONSENT, params)).also { if (it is PluginCatalogResult.Ok) staged -= id.value }
    }

    override suspend fun cancelInstall(id: PluginId): PluginCatalogResult<Unit> =
        if (staged.remove(id.value)) remove(id) else PluginCatalogResult.Ok(Unit)

    override suspend fun enable(id: PluginId) = listed(link.call(PluginAdminRpc.ENABLE, idParams(id)))

    override suspend fun disable(id: PluginId) = listed(link.call(PluginAdminRpc.DISABLE, idParams(id)))

    override suspend fun remove(id: PluginId) = listed(link.call(PluginAdminRpc.REMOVE, idParams(id)))

    override suspend fun disableAll() = listed(link.call(PluginAdminRpc.DISABLE_ALL))

    override suspend fun health(id: PluginId): PluginCatalogResult<PluginHealth> =
        link.call(PluginAdminRpc.INSPECT, idParams(id)).read(::inspected).read { it.plugin.health }

    /** The registry in [answer] becomes [plugins]. */
    private fun listed(answer: PluginCatalogResult<JsonElement>): PluginCatalogResult<Unit> = answer.read { result ->
        publish(LettaJson.wire.decodeFromJsonElement(PluginPluginsWire.serializer(), result).plugins)
    }

    private fun inspected(result: JsonElement): PluginInspectWire =
        LettaJson.wire.decodeFromJsonElement(PluginInspectWire.serializer(), result).also { publish(it.plugins) }

    private fun publish(plugins: List<PluginSummaryWire>) {
        state.value = plugins.map(PluginSummaryWire::toSummary)
    }

    private fun idParams(id: PluginId) = buildJsonObject { put(PluginAdminRpc.PLUGIN_ID, id.value) }
}
