package com.letta.mobile.plugin.testkit

import com.letta.mobile.plugin.api.ElementQuery
import com.letta.mobile.plugin.api.EmitReceipt
import com.letta.mobile.plugin.api.EmitRefusal
import com.letta.mobile.plugin.api.HostInfo
import com.letta.mobile.plugin.api.LogLevel
import com.letta.mobile.plugin.api.PlaceElement
import com.letta.mobile.plugin.api.PluginApi
import com.letta.mobile.plugin.api.PluginElementView
import com.letta.mobile.plugin.api.PluginEmit
import com.letta.mobile.plugin.api.PluginHost
import com.letta.mobile.plugin.api.PluginHostException
import com.letta.mobile.plugin.api.PluginHttpClient
import com.letta.mobile.plugin.api.PluginHttpResponse
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancelChildren
import kotlinx.serialization.json.JsonObject
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/** One [PluginHost.log] line as the host recorded it. */
public data class LogLine(public val level: LogLevel, public val message: String, public val fields: Map<String, String>)

/** The HTTP methods [PluginHost.httpClient] offers. */
public enum class HttpMethod { GET, POST }

/** One request a plugin sent through [PluginHost.httpClient]. */
public data class PluginHttpRequest(
    public val method: HttpMethod,
    public val url: String,
    public val headers: Map<String, String>,
    public val contentType: String? = null,
)

/**
 * A [PluginHost] that records everything a plugin does and holds it to its manifest the way the
 * real host does: capability checks, the `net.connect` allowlist, emits against the element kinds,
 * and no work before `activate` or after `deactivate`. Each breach is kept in [violations] (and the
 * call refused, as the host would); [PluginConformance] reports them.
 *
 * Usable on its own in a plugin's unit tests: it starts active, and [httpHandler] answers the
 * plugin's allowed requests (an empty 200 by default). [scope] is the caller's, the lifecycle owner
 * of the plugin's background work (a test's `backgroundScope`, say): [close] cancels its children.
 */
public class FakePluginHost(
    public val manifest: ConformanceManifest,
    override val scope: CoroutineScope,
    override val settings: JsonObject = manifest.defaultSettings(),
    private val secrets: Map<String, String> = emptyMap(),
    override val hostInfo: HostInfo = HostInfo(name = "letta-conformance-kit", version = PluginApi.VERSION),
    httpHandler: suspend (PluginHttpRequest) -> PluginHttpResponse = { PluginHttpResponse(200, emptyMap(), ByteArray(0)) },
) : PluginHost {
    override val pluginId: String get() = manifest.id
    override val contractVersion: Int = PluginApi.CONTRACT_VERSION

    private val recordedEmits = CopyOnWriteArrayList<PluginEmit>()
    private val recordedLogs = CopyOnWriteArrayList<LogLine>()
    private val recordedRequests = CopyOnWriteArrayList<PluginHttpRequest>()
    private val recordedViolations = CopyOnWriteArrayList<ConformanceFinding>()
    private val storedAssets = ConcurrentHashMap<String, ByteArray>()
    private val placed = CopyOnWriteArrayList<PluginElementView>()

    @Volatile
    internal var phase: HostPhase = HostPhase.ACTIVE

    override val httpClient: PluginHttpClient = AllowlistHttpClient(
        manifest = manifest,
        admit = { request ->
            guardWork(HostCall.HTTP)
            recordedRequests += request
        },
        refuse = { recordedViolations += it },
        handler = httpHandler,
    )

    /** Every emit, from [emit] and from action results, in order. */
    public val emits: List<PluginEmit> get() = recordedEmits.toList()

    /** Every log line. */
    public val logs: List<LogLine> get() = recordedLogs.toList()

    /** Every HTTP request the plugin sent, allowed or not. */
    public val httpRequests: List<PluginHttpRequest> get() = recordedRequests.toList()

    /** The stored assets by ref. */
    public val assets: Map<String, ByteArray> get() = storedAssets.toMap()

    /** The elements the plugin placed (and has not removed). */
    public val elements: List<PluginElementView> get() = placed.toList()

    /** Where the plugin broke the contract while using the host. */
    public val violations: List<ConformanceFinding> get() = recordedViolations.toList()

    override fun secret(name: String): String? {
        guardUse(HostCall.SECRET)
        return secrets[name]
    }

    override suspend fun emit(emit: PluginEmit): EmitReceipt {
        guardWork(HostCall.EMIT)
        return apply(emit)
    }

    /** Applies [emit] as the host does for both [emit] and an action result's emit. */
    internal fun apply(emit: PluginEmit): EmitReceipt {
        recordedEmits += emit
        val problems = EmitRules(manifest, placed.associate { it.id to it.type.substringAfter('/') }).problems(emit)
        problems.forEach { violation(ConformanceFinding(it.rule, "emit entry ${it.index}: ${it.reason}")) }
        val refusedIndexes = problems.map { it.index }.toSet()
        val placedIds = emit.place.withIndex().filter { it.index !in refusedIndexes }.map { place(it.value) }
        placed.removeAll { it.id in emit.remove }
        return EmitReceipt(placed = placedIds, refused = problems.map { EmitRefusal(it.index, it.reason) })
    }

    private fun place(element: PlaceElement): String {
        val id = "element-${placed.size + 1}"
        placed += PluginElementView(
            id = id,
            type = "ext:${manifest.id}/${element.kind}",
            canvasId = element.canvasId ?: CANVAS_ID,
            v = element.v,
            frame = element.frame,
            props = element.props,
            ref = element.ref,
            fallback = element.fallback,
        )
        return id
    }

    override suspend fun putAsset(mediaType: String, bytes: ByteArray): String {
        guardWork(HostCall.PUT_ASSET)
        requireCapability(ConformanceCapability.ASSETS_WRITE, HostCall.PUT_ASSET)
        val ref = "sha256:" + MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        storedAssets[ref] = bytes
        return ref
    }

    override suspend fun readElements(query: ElementQuery): List<PluginElementView> {
        guardWork(HostCall.READ_ELEMENTS)
        requireCapability(ConformanceCapability.CANVAS_READ, HostCall.READ_ELEMENTS)
        return placed.filter { element -> query.matches(element) }
    }

    private fun ElementQuery.matches(element: PluginElementView): Boolean =
        (canvasId == null || canvasId == element.canvasId) &&
            (elementIds.isEmpty() || element.id in elementIds) &&
            (kinds.isEmpty() || element.type.substringAfter('/') in kinds)

    override fun log(level: LogLevel, message: String, fields: Map<String, String>) {
        guardUse(HostCall.LOG)
        recordedLogs += LogLine(level, message, fields)
    }

    /** Ends the plugin's lifetime here: its background work is cancelled and any later use is a violation. */
    public fun close() {
        phase = HostPhase.DEACTIVATED
        scope.coroutineContext.cancelChildren()
    }

    private fun requireCapability(capability: ConformanceCapability, call: HostCall) {
        if (manifest.has(capability)) return
        refuse(ConformanceRule.CAPABILITY, PluginHostException(PluginHostException.CAPABILITY_DENIED, "$call needs the $capability capability"))
    }

    /** Any use of the host after deactivate is refused. */
    private fun guardUse(call: HostCall) {
        if (phase != HostPhase.DEACTIVATED) return
        refuse(ConformanceRule.LIFECYCLE, PluginHostException(PluginHostException.DEACTIVATED, "used $call after deactivate"))
    }

    /** Work (emits, assets, reads, the network) is refused outside the active phase. */
    private fun guardWork(call: HostCall) {
        guardUse(call)
        if (phase == HostPhase.ACTIVE) return
        refuse(ConformanceRule.LIFECYCLE, PluginHostException(PluginHostException.NOT_ACTIVE, "used $call before activate"))
    }

    private fun refuse(rule: ConformanceRule, refusal: PluginHostException): Nothing {
        violation(ConformanceFinding(rule, refusal.message.orEmpty()))
        throw refusal
    }

    private fun violation(finding: ConformanceFinding) {
        recordedViolations += finding
    }

    internal companion object {
        const val CANVAS_ID: String = "conformance-canvas"
    }
}

/** The host members a plugin calls, as findings name them. */
internal enum class HostCall(private val label: String) {
    SECRET("host.secret"),
    EMIT("host.emit"),
    PUT_ASSET("host.putAsset"),
    READ_ELEMENTS("host.readElements"),
    LOG("host.log"),
    HTTP("host.httpClient"),
    ;

    override fun toString(): String = label
}

/** Where a plugin is in its lifetime, as the host sees it. */
internal enum class HostPhase { INITIALIZING, ACTIVE, DEACTIVATED }
