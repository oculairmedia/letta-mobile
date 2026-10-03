package com.letta.mobile.plugin.api

/** Which way an [LcpMethod] travels. */
public enum class LcpDirection {
    /** The host calls the plugin. */
    HOST_TO_PLUGIN,

    /** The plugin calls the host. */
    PLUGIN_TO_HOST,

    /** Either side sends it: protocol plumbing, no SPI member ([LcpMethod.CANCEL]). */
    EITHER,
}

/**
 * The LCP wire v1 method set (plan section 5) and the SPI member each one is. It is the one
 * registry the jar driver, the wire codec and the conformance kit share, so a jvm plugin and a
 * process or service plugin meet the same contract: [wire] is the JSON-RPC method name,
 * [spiMember] the `Interface.member` it maps to, [notification] whether it expects no answer, and
 * [deadlineMillis] how long the host waits for the answer (null: the caller's own bound).
 *
 * `host.putAsset` is one SPI call and three wire messages (begin, chunk, end), because the wire
 * moves assets in ≤ 1 MiB base64 chunks. The [PluginHost] members that are no wire method are
 * [JVM_ONLY_HOST_MEMBERS] (a process plugin gets secrets through its environment and does its own
 * networking) and [INITIALIZE_PARAMS] (sent once, as `plugin.initialize`'s parameters).
 */
public enum class LcpMethod(
    public val wire: String,
    public val direction: LcpDirection,
    public val spiMember: String,
    public val notification: Boolean = false,
    public val deadlineMillis: Long? = null,
) {
    INITIALIZE("plugin.initialize", LcpDirection.HOST_TO_PLUGIN, "CanvasPlugin.initialize", deadlineMillis = 10_000),
    ACTIVATE("plugin.activate", LcpDirection.HOST_TO_PLUGIN, "CanvasPlugin.activate", deadlineMillis = 30_000),
    DEACTIVATE("plugin.deactivate", LcpDirection.HOST_TO_PLUGIN, "CanvasPlugin.deactivate", deadlineMillis = 10_000),
    HEALTH("plugin.health", LcpDirection.HOST_TO_PLUGIN, "CanvasPlugin.health", deadlineMillis = 5_000),
    INVOKE("action.invoke", LcpDirection.HOST_TO_PLUGIN, "CanvasPlugin.invoke", deadlineMillis = 100_000),
    ELEMENT_EVENT("element.event", LcpDirection.HOST_TO_PLUGIN, "CanvasPlugin.onElementEvent", notification = true),
    SETTINGS_CHANGED("host.settingsChanged", LcpDirection.HOST_TO_PLUGIN, "CanvasPlugin.onSettingsChanged", notification = true),
    EMIT("host.emit", LcpDirection.PLUGIN_TO_HOST, "PluginHost.emit"),
    PUT_ASSET_BEGIN("host.putAsset.begin", LcpDirection.PLUGIN_TO_HOST, "PluginHost.putAsset"),
    PUT_ASSET_CHUNK("host.putAsset.chunk", LcpDirection.PLUGIN_TO_HOST, "PluginHost.putAsset"),
    PUT_ASSET_END("host.putAsset.end", LcpDirection.PLUGIN_TO_HOST, "PluginHost.putAsset"),
    READ_ELEMENTS("host.readElements", LcpDirection.PLUGIN_TO_HOST, "PluginHost.readElements"),
    LOG("host.log", LcpDirection.PLUGIN_TO_HOST, "PluginHost.log", notification = true),

    /**
     * `$/cancel {id}`, sent by either side: the caller gave up on its request [id] (its own deadline
     * or its own cancellation); the receiver cancels the work and answers the request with error
     * `-32800`. On the SPI side it is coroutine cancellation, not a member.
     */
    CANCEL("\$/cancel", LcpDirection.EITHER, "(coroutine cancellation)", notification = true),
    ;

    public companion object {
        /** [PluginHost] members a jvm plugin has that no wire method carries. */
        public val JVM_ONLY_HOST_MEMBERS: Set<String> = setOf("secret", "scope", "httpClient")

        /** [PluginHost] properties the wire sends once, as the parameters of `plugin.initialize`. */
        public val INITIALIZE_PARAMS: Set<String> = setOf("pluginId", "contractVersion", "hostInfo", "settings")

        /** The largest wire message, in bytes. */
        public const val MAX_MESSAGE_BYTES: Int = 4 * 1024 * 1024

        /** The largest decoded `host.putAsset.chunk`, in bytes. */
        public const val MAX_ASSET_CHUNK_BYTES: Int = 1024 * 1024

        /** The method named [wire], or null for a name outside LCP v1. */
        public fun byWire(wire: String): LcpMethod? = entries.firstOrNull { it.wire == wire }
    }
}
