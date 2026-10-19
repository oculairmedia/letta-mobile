package com.letta.mobile.plugin.api

import kotlinx.coroutines.CoroutineScope
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * Host-service SPI supplied at [CanvasPlugin.initialize]. The properties are
 * the `plugin.initialize` parameters on the wire; the functions are the plugin-to-host methods
 * (`host.emit`, `host.putAsset.*`, `host.readElements`, `host.log`).
 *
 * An SPI implementation must enforce consented capabilities and reject unavailable operations.
 * The production process host uses LCP handlers rather than this Kotlin object; it does not
 * implement every declared optional service. The conformance testkit supplies an SPI host.
 */
public interface PluginHost {
    /** The manifest `id`, `<publisher>.<name>`. */
    public val pluginId: String

    /** The contract major this host speaks to the plugin ([PluginApi.CONTRACT_VERSION] for this SPI). */
    public val contractVersion: Int

    /** Who is hosting the plugin. */
    public val hostInfo: HostInfo

    /** Resolved settings contract. The current production launcher supplies manifest defaults only. */
    public val settings: JsonObject

    /**
     * A manifest-declared secret, or null when unset. Implementations must guard known secret
     * values in outputs; plugins must never log, emit or return them or put them in a URL.
     * The current production launcher injects no secrets; this SPI does not supply a secret store.
     */
    public fun secret(name: String): String?

    /** Places, updates or removes owned elements; needs `canvas:place`. Entry refusals use a receipt; whole-call violations fail. */
    public suspend fun emit(emit: PluginEmit): EmitReceipt

    /** Stores [bytes] as an asset and answers its `sha256:<hex>` ref; needs `assets:write`. */
    public suspend fun putAsset(mediaType: String, bytes: ByteArray): String

    /** Declared read service. The production v1 host refuses every call because it does not grant `canvas:read`. */
    public suspend fun readElements(query: ElementQuery): List<PluginElementView>

    /** Writes to the host's telemetry; [fields] are flat strings. Never a secret. */
    public fun log(level: LogLevel, message: String, fields: Map<String, String> = emptyMap())

    /** Where the plugin's background work runs: a bounded dispatcher, cancelled after [CanvasPlugin.deactivate]. */
    public val scope: CoroutineScope

    /** Allowlisted HTTP service contract for SPI implementations; it does not sandbox a plugin's own networking. */
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
 * HTTP service contract: implementations must check the manifest's `net.connect` origins before
 * sending a request and throw [PluginHostException] with [PluginHostException.ORIGIN_DENIED] for
 * a disallowed origin. The conformance testkit exercises this contract; the production process
 * driver does not provide this Kotlin client or a network sandbox.
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
