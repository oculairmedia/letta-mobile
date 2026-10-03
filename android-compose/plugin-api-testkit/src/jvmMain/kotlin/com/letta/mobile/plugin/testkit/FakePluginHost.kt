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
import com.letta.mobile.plugin.testkit.ConformanceManifest.Companion.ASSETS_WRITE
import com.letta.mobile.plugin.testkit.ConformanceManifest.Companion.CANVAS_READ
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.serialization.json.JsonObject
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/** One [PluginHost.log] line as the host recorded it. */
public data class LogLine(public val level: LogLevel, public val message: String, public val fields: Map<String, String>)

/** One request a plugin sent through [PluginHost.httpClient]. */
public data class PluginHttpRequest(
    public val method: String,
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
 * plugin's allowed requests (an empty 200 by default).
 */
public class FakePluginHost(
    public val manifest: ConformanceManifest,
    override val settings: JsonObject = manifest.defaultSettings(),
    private val secrets: Map<String, String> = emptyMap(),
    override val hostInfo: HostInfo = HostInfo(name = "letta-conformance-kit", version = PluginApi.VERSION),
    httpHandler: suspend (PluginHttpRequest) -> PluginHttpResponse = { PluginHttpResponse(200, emptyMap(), ByteArray(0)) },
) : PluginHost {
    override val pluginId: String get() = manifest.id
    override val contractVersion: Int = PluginApi.CONTRACT_VERSION
    override val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default.limitedParallelism(SCOPE_THREADS))

    private val recordedEmits = CopyOnWriteArrayList<PluginEmit>()
    private val recordedLogs = CopyOnWriteArrayList<LogLine>()
    private val recordedRequests = CopyOnWriteArrayList<PluginHttpRequest>()
    private val recordedViolations = CopyOnWriteArrayList<ConformanceFinding>()
    private val storedAssets = ConcurrentHashMap<String, ByteArray>()
    private val placed = CopyOnWriteArrayList<PluginElementView>()

    @Volatile
    internal var phase: HostPhase = HostPhase.ACTIVE

    override val httpClient: PluginHttpClient = AllowlistHttpClient(manifest, ::guardWork, ::violation, recordedRequests, httpHandler)

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
        guardUse("secret")
        return secrets[name]
    }

    override suspend fun emit(emit: PluginEmit): EmitReceipt {
        guardWork("emit")
        return apply(emit)
    }

    /** Applies [emit] as the host does for both [emit] and an action result's emit. */
    internal fun apply(emit: PluginEmit): EmitReceipt {
        recordedEmits += emit
        val problems = EmitRules(manifest, placed.associate { it.id to it.type.substringAfter('/') }).problems(emit)
        problems.forEach { violation(it.rule, "emit entry ${it.index}: ${it.reason}") }
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
        guardWork("putAsset")
        requireCapability(ASSETS_WRITE, "putAsset")
        val ref = "sha256:" + MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        storedAssets[ref] = bytes
        return ref
    }

    override suspend fun readElements(query: ElementQuery): List<PluginElementView> {
        guardWork("readElements")
        requireCapability(CANVAS_READ, "readElements")
        return placed.filter { element -> query.matches(element) }
    }

    private fun ElementQuery.matches(element: PluginElementView): Boolean =
        (canvasId == null || canvasId == element.canvasId) &&
            (elementIds.isEmpty() || element.id in elementIds) &&
            (kinds.isEmpty() || element.type.substringAfter('/') in kinds)

    override fun log(level: LogLevel, message: String, fields: Map<String, String>) {
        guardUse("log")
        recordedLogs += LogLine(level, message, fields)
    }

    /** Ends the plugin's lifetime here: its scope is cancelled and any later use is a violation. */
    public fun close() {
        phase = HostPhase.DEACTIVATED
        scope.cancel()
    }

    private fun requireCapability(capability: String, call: String) {
        if (manifest.has(capability)) return
        violation(ConformanceRule.CAPABILITY, "host.$call needs the $capability capability")
        throw PluginHostException(PluginHostException.CAPABILITY_DENIED, "host.$call needs the $capability capability")
    }

    private fun guardUse(call: String) {
        if (phase != HostPhase.DEACTIVATED) return
        violation(ConformanceRule.LIFECYCLE, "used host.$call after deactivate")
        throw PluginHostException(PluginHostException.DEACTIVATED, "host.$call after deactivate")
    }

    private fun guardWork(call: String) {
        guardUse(call)
        if (phase == HostPhase.ACTIVE) return
        violation(ConformanceRule.LIFECYCLE, "used host.$call before activate")
        throw PluginHostException(PluginHostException.NOT_ACTIVE, "host.$call before activate")
    }

    private fun violation(rule: ConformanceRule, message: String) {
        recordedViolations += ConformanceFinding(rule, message)
    }

    internal companion object {
        const val CANVAS_ID: String = "conformance-canvas"
        private const val SCOPE_THREADS = 4
    }
}

/** Where a plugin is in its lifetime, as the host sees it. */
internal enum class HostPhase { INITIALIZING, ACTIVE, DEACTIVATED }
