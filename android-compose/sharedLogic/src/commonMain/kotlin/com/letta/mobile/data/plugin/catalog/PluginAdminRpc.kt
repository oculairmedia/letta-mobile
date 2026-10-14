package com.letta.mobile.data.plugin.catalog

import com.letta.mobile.data.plugin.PluginCapability
import com.letta.mobile.data.plugin.PluginHealth
import com.letta.mobile.data.plugin.PluginManifest
import com.letta.mobile.data.plugin.PluginPage
import com.letta.mobile.data.plugin.PluginState
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * letta-mobile-cdgpr.7: the host's `plugins.*` admin RPCs, as clients call them and the host's
 * handlers read them. Management (list, inspect, install, consent, enable, disable, remove,
 * disableAll) is admin-only; `plugins.page.read` and `plugins.view.action` are what a live view on
 * the canvas needs. A host with a plugin runtime advertises [CAPABILITY]; a client never sends
 * these to a host that does not.
 */
object PluginAdminRpc {
    const val CAPABILITY = "plugins_v1"
    const val PATH = "/v1/plugins"

    const val LIST = "plugins.list"
    const val INSPECT = "plugins.inspect"
    const val INSTALL = "plugins.install"
    const val CONSENT = "plugins.consent"
    const val ENABLE = "plugins.enable"
    const val DISABLE = "plugins.disable"
    const val REMOVE = "plugins.remove"
    const val DISABLE_ALL = "plugins.disableAll"
    const val PAGE_READ = "plugins.page.read"
    const val VIEW_ACTION = "plugins.view.action"

    val MANAGEMENT: Set<String> = setOf(LIST, INSPECT, INSTALL, CONSENT, ENABLE, DISABLE, REMOVE, DISABLE_ALL)
    val METHODS: Set<String> = MANAGEMENT + PAGE_READ + VIEW_ACTION

    const val PLUGIN_ID = "plugin_id"
    const val ASSET_REF = "asset_ref"
    const val SHA256 = "sha256"
    const val PAGE_ID = "page_id"
    const val VERSION = "version"

    /** The wire's JSON: plugin states and health as `{"kind": ...}`, nulls and unknown keys tolerated. */
    val json: Json = Json {
        classDiscriminator = "kind"
        encodeDefaults = true
        explicitNulls = false
        ignoreUnknownKeys = true
    }
}

/** One installed plugin as the host reports it. */
@Serializable
data class PluginSummaryWire(
    val id: String,
    val name: String,
    val version: String,
    val state: PluginState,
    val health: PluginHealth = PluginHealth.Unknown,
    val consented: Boolean = false,
    @SerialName("last_fault") val lastFault: String? = null,
    /** The installed version's manifest, so a client knows its element kinds and pages (letta-mobile-cdgpr.8). */
    val manifest: PluginManifest? = null,
) {
    fun toSummary(): PluginSummary = PluginSummary(PluginId(id), name, version, state, health, lastFault, manifest)
}

/** What an installed version asks the owner to consent to, from its manifest. */
@Serializable
data class PluginRequestWire(
    val id: String,
    val name: String,
    val version: String,
    val publisher: String,
    val sha256: String,
    @SerialName("runtime_kind") val runtimeKind: String,
    val capabilities: List<PluginCapability> = emptyList(),
    val origins: List<String> = emptyList(),
    val pages: List<String> = emptyList(),
) {
    fun toRequest(): PluginInstallRequest =
        PluginInstallRequest(PluginId(id), name, version, publisher, sha256, runtimeKind, capabilities, origins, pages)
}

/** `plugins.list` (and every management write): the registry after the call. */
@Serializable
data class PluginPluginsWire(val plugins: List<PluginSummaryWire>)

/**
 * `plugins.install` / `plugins.inspect`: one plugin, what it asks for, and the registry.
 * [newlyInstalled] is true when an install added the plugin (rather than updating one), so a
 * cancelled consent may remove it again.
 */
@Serializable
data class PluginInspectWire(
    val plugin: PluginSummaryWire,
    val request: PluginRequestWire,
    val plugins: List<PluginSummaryWire> = emptyList(),
    @SerialName("newly_installed") val newlyInstalled: Boolean = false,
)

/** `plugins.page.read`: the page's document (base64) with its manifest entry and the view's actions. */
@Serializable
data class PluginPageWire(
    @SerialName("plugin_id") val pluginId: String,
    val version: String,
    @SerialName("page_id") val pageId: String,
    val document: String,
    val page: PluginPage,
    @SerialName("view_actions") val viewActions: Map<String, JsonObject> = emptyMap(),
)

/** `plugins.view.action`: the view's call, then the plugin's answer. */
@Serializable
data class PluginViewActionWire(
    @SerialName("plugin_id") val pluginId: String,
    @SerialName("canvas_id") val canvasId: String,
    @SerialName("element_id") val elementId: String,
    val action: String,
    val input: JsonObject = JsonObject(emptyMap()),
)

@Serializable
data class PluginViewAnswerWire(
    val status: String,
    val text: String? = null,
    val structured: JsonElement? = null,
    val message: String? = null,
) {
    companion object {
        const val DONE = "done"
        const val FAILED = "failed"
        const val UNAVAILABLE = "unavailable"
    }
}
