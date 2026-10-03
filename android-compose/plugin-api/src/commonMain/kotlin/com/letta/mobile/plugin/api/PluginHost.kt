package com.letta.mobile.plugin.api

import kotlinx.coroutines.CoroutineScope
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * What the host gives a plugin at [CanvasPlugin.initialize] (plan section 3.2). The properties are
 * the `plugin.initialize` parameters on the wire; the functions are the plugin-to-host methods
 * (`host.emit`, `host.putAsset.*`, `host.readElements`, `host.log`).
 *
 * Capability checks (plan section 3.3) are the host's: a call the manifest's `capabilities` do not
 * cover throws [PluginHostException] with [PluginHostException.CAPABILITY_DENIED].
 */
public interface PluginHost {
    /** The manifest `id`, `<publisher>.<name>`. */
    public val pluginId: String

    /** The contract major this host speaks to the plugin ([PluginApi.CONTRACT_VERSION] for this SPI). */
    public val contractVersion: Int

    /** Who is hosting the plugin. */
    public val hostInfo: HostInfo

    /** The owner's settings, resolved (defaults filled in) and valid against the manifest's `settings`. */
    public val settings: JsonObject

    /**
     * The value of the manifest-declared secret [name], or null when the owner has not set it. The
     * host scrubs every plugin output (results, logs, emits) for secret values and fails closed, so
     * a plugin never logs, emits or returns one, and never puts one in a URL.
     */
    public fun secret(name: String): String?

    /** Places, updates or removes the plugin's own elements; needs `canvas:place`. Refusals come back in the receipt. */
    public suspend fun emit(emit: PluginEmit): EmitReceipt

    /** Stores [bytes] as an asset and answers its `sha256:<hex>` ref; needs `assets:write`. */
    public suspend fun putAsset(mediaType: String, bytes: ByteArray): String

    /** Reads elements on the boards the plugin is on; needs `canvas:read` (own elements in full, others as ids, types and frames). */
    public suspend fun readElements(query: ElementQuery): List<PluginElementView>

    /** Writes to the host's telemetry; [fields] are flat strings. Never a secret. */
    public fun log(level: LogLevel, message: String, fields: Map<String, String> = emptyMap())

    /** Where the plugin's background work runs: a bounded dispatcher, cancelled after [CanvasPlugin.deactivate]. */
    public val scope: CoroutineScope

    /** The only network a jvm plugin may use: the host's client, held to the manifest's `net.connect` origins. */
    public val httpClient: PluginHttpClient
}

/** The host a plugin runs in, sent with `plugin.initialize`. */
@Serializable
public data class HostInfo(
    /** The host program, e.g. `letta-iroh-wrapper` or `letta-desktop`. */
    public val name: String,
    /** The host program's version. */
    public val version: String,
    /** The host's platform, e.g. `linux-x64`. */
    public val platform: String? = null,
)

/** The severity of a [PluginHost.log] line. */
@Serializable
public enum class LogLevel {
    @SerialName("debug") DEBUG,
    @SerialName("info") INFO,
    @SerialName("warn") WARN,
    @SerialName("error") ERROR,
}

/**
 * The host-provided HTTP client (plan section 3.3, `net:connect`). Every request is checked against
 * the manifest's `net.connect` origins before it leaves; one outside them throws
 * [PluginHostException] with [PluginHostException.ORIGIN_DENIED]. A jar that brings its own client
 * is a policy violation surfaced by review.
 */
public interface PluginHttpClient {
    /** Sends a GET to [url] with [headers]. */
    public suspend fun get(url: String, headers: Map<String, String> = emptyMap()): PluginHttpResponse

    /** Sends a POST of [body] typed [contentType] to [url] with [headers]. */
    public suspend fun post(
        url: String,
        body: ByteArray,
        contentType: String,
        headers: Map<String, String> = emptyMap(),
    ): PluginHttpResponse
}

/** An HTTP answer: [status], response [headers] (lowercase names) and the [body] bytes. */
public class PluginHttpResponse(
    public val status: Int,
    public val headers: Map<String, String>,
    public val body: ByteArray,
)

/**
 * The host refused a plugin's call: [code] is one of the constants below (or a host's own), and
 * the message says why in words a plugin author can act on.
 */
public class PluginHostException(public val code: String, message: String) : RuntimeException(message) {
    public companion object {
        /** The manifest's `capabilities` do not cover the call. */
        public const val CAPABILITY_DENIED: String = "capability_denied"

        /** The URL's origin is not one of the manifest's `net.connect` origins. */
        public const val ORIGIN_DENIED: String = "origin_denied"

        /** The plugin used the host for work (emit, assets, reads) before [CanvasPlugin.activate]. */
        public const val NOT_ACTIVE: String = "not_active"

        /** The plugin used the host after [CanvasPlugin.deactivate]. */
        public const val DEACTIVATED: String = "deactivated"

        /** The call broke a limit (message size, asset size, write rate). */
        public const val LIMIT: String = "limit"
    }
}
