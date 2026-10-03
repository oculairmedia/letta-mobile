package com.letta.mobile.plugin.testkit

import com.letta.mobile.plugin.api.CanvasPlugin
import com.letta.mobile.plugin.api.LcpMethod
import com.letta.mobile.plugin.api.PluginHttpResponse
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.supervisorScope
import kotlinx.serialization.json.JsonObject

/**
 * How [PluginConformance.run] exercises a plugin.
 *
 * - [settings]: the resolved settings (the manifest's defaults when null).
 * - [secrets]: secret values by name; by default every declared secret gets a unique sentinel, so
 *   a leak of it anywhere is found.
 * - [inputs]: an input per action; an action without one gets a sample built from its schema (the
 *   required fields only). Each input must be valid against the action's schema.
 * - [deadlines]: per-method deadlines in milliseconds, overriding [LcpMethod.deadlineMillis].
 * - [settleMillis]: how long the kit watches the host after `deactivate` for late use.
 * - [httpHandler]: answers the plugin's allowed HTTP requests.
 */
public data class ConformanceOptions(
    public val settings: JsonObject? = null,
    public val secrets: Map<String, String>? = null,
    public val inputs: Map<String, JsonObject> = emptyMap(),
    public val deadlines: Map<LcpMethod, Long> = emptyMap(),
    public val settleMillis: Long = 100,
    public val httpHandler: suspend (PluginHttpRequest) -> PluginHttpResponse = { PluginHttpResponse(200, emptyMap(), ByteArray(0)) },
) {
    internal fun deadline(method: LcpMethod): Long = deadlines[method] ?: method.deadlineMillis ?: DEFAULT_DEADLINE_MILLIS

    internal fun secretsFor(manifest: ConformanceManifest): Map<String, String> =
        secrets ?: manifest.secrets.associate { it.name to "lcp-conformance-secret-${it.name}-$SENTINEL" }

    private companion object {
        const val DEFAULT_DEADLINE_MILLIS = 10_000L
        const val SENTINEL = "5f0c2a9e"
    }
}

/**
 * The LCP v1 conformance kit for jvm plugins (letta-mobile-s416w.26). A plugin author runs it from
 * their own tests against their plugin and its `letta-plugin.json`:
 *
 * ```kotlin
 * @Test fun `keeps the contract`() {
 *     val manifest = ConformanceManifest.parse(File("letta-plugin.json").readText())
 *     PluginConformance.run(MyPlugin(), manifest).assertPassed()
 * }
 * ```
 *
 * It drives the plugin through its whole lifetime on a [FakePluginHost]: initialize, activate,
 * health, every declared action with a schema-valid input, an undeclared action, element events
 * for what it placed, a settings change, deactivate twice, and then scans everything the plugin
 * said for its secrets. Each rule it checks is a [ConformanceRule].
 */
public object PluginConformance {
    /** Runs the suite against a fresh [plugin] (it is deactivated at the end) and reports what broke the contract. */
    public fun run(
        plugin: CanvasPlugin,
        manifest: ConformanceManifest,
        options: ConformanceOptions = ConformanceOptions(),
    ): ConformanceReport = runBlocking {
        // The run owns the plugin's scope: its children are the plugin's background work, cancelled at deactivate.
        supervisorScope { ConformanceRun(plugin, manifest, options, scope = this).execute() }
    }
}
