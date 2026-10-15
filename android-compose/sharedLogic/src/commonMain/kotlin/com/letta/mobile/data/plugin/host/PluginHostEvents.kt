package com.letta.mobile.data.plugin.host

import com.letta.mobile.data.model.PluginId
import com.letta.mobile.util.Telemetry

/**
 * Every plugin event the host reports: `plugin.<wire>` in telemetry under [PluginHostEvents.TAG], at
 * [level] unless the call says otherwise. The set is closed: `PluginTelemetryTest` drives a path to
 * each one, so an event nothing emits fails the build.
 */
enum class PluginHostEvent(val wire: String, val level: Telemetry.Level = Telemetry.Level.INFO) {
    /** A package was installed (`version`, `sha256`), or refused. */
    INSTALL("install"),

    /** An installed plugin moved to a new version (`version`, `needsConsent`). */
    UPDATE("update"),

    /** The owner's consent was recorded for the installed version, or refused. */
    CONSENT("consent"),
    ENABLE("enable"),
    DISABLE("disable"),
    REMOVE("remove"),

    /** The plugin's driver reached Running. */
    ACTIVE("active"),

    /** The driver gave up (crash budget spent, or a launch it refused to make): `reason`. */
    FAULT("fault", Telemetry.Level.WARN),

    /** A crashed plugin is being restarted: `reason`, `attempt`. */
    RESTART("restart", Telemetry.Level.WARN),

    /** One action call: `action`, `outcome` (`ok`, `error:<code>`, `failed:<lcp code>`) and its latency. */
    INVOKE("invoke"),

    /** The host refused something the plugin asked of it: `reason` is the refusal's wire name. */
    REFUSED("refused", Telemetry.Level.WARN),

    /** An asset the plugin stored: `bytes` charged (0 when the store already had them), `mediaType`. */
    ASSET("asset"),

    /** A `host.log` line, already scrubbed, at the plugin's own level. */
    LOG("log"),

    /** A stderr line, already scrubbed of the plugin's secrets. */
    STDERR("stderr", Telemetry.Level.WARN),

    /** A plugin page finished its `lcp-view/1` handshake: `page`. */
    VIEW_OPEN("view_open"),

    /** A plugin page's handshake was refused: `page`, `reason`. */
    VIEW_FAIL("view_fail", Telemetry.Level.WARN),

    /** A kill switch was thrown: `scope` (`runtime` for disableAll, `global` for `--plugins=off`), `plugins` stopped. */
    KILL_SWITCH("kill_switch", Telemetry.Level.WARN),

    /** `plugins.json` did not decode and was moved aside. */
    REGISTRY_UNREADABLE("registry_unreadable", Telemetry.Level.WARN),

    /** `asset-quota.json` did not load (`step` load; the window starts empty) or save (`step` save): `error`. */
    QUOTA_STORE_FAILED("quota_store_failed", Telemetry.Level.WARN),
    ;

    /** The telemetry event name. */
    val eventName: String get() = "plugin.$wire"
}

/**
 * The plugin host's telemetry (letta-mobile-cdgpr.6, completed by letta-mobile-cdgpr.10): one typed
 * [PluginHostEvent] per call, always with the plugin id (`*` for host-wide events). Callers pass
 * values already scrubbed of secrets; nothing here sees a secret.
 */
object PluginHostEvents {
    const val TAG: String = "PluginHost"

    /** The plugin id of an event about every plugin (a kill switch, the registry file). */
    val ALL: PluginId = PluginId("*")

    fun record(event: PluginHostEvent, pluginId: PluginId, vararg attrs: Pair<String, String>) =
        emit(event, event.level, null, arrayOf("plugin" to pluginId.value, *attrs))

    /** [event] with its latency, for a timed call ([PluginHostEvent.INVOKE]). */
    fun timed(event: PluginHostEvent, pluginId: PluginId, durationMs: Long, vararg attrs: Pair<String, String>) =
        emit(event, event.level, durationMs, arrayOf("plugin" to pluginId.value, *attrs))

    /** A `host.log` line at the plugin's own [level]. */
    fun log(pluginId: PluginId, level: Telemetry.Level, vararg attrs: Pair<String, String>) =
        emit(PluginHostEvent.LOG, level, null, arrayOf("plugin" to pluginId.value, *attrs))

    /** [event] refused: the same event with `refused` set, at WARN. */
    fun refused(event: PluginHostEvent, pluginId: PluginId, message: String) =
        emit(event, Telemetry.Level.WARN, null, arrayOf("plugin" to pluginId.value, "refused" to message))

    private fun emit(event: PluginHostEvent, level: Telemetry.Level, durationMs: Long?, attrs: Array<Pair<String, String>>) {
        Telemetry.event(TAG, event.eventName, *attrs, durationMs = durationMs, level = level)
    }
}
