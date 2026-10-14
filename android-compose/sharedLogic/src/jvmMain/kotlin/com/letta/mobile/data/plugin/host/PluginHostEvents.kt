package com.letta.mobile.data.plugin.host

import com.letta.mobile.util.Telemetry

/** A plugin host lifecycle event: `plugin.<wire>` in telemetry. */
enum class PluginHostEvent(val wire: String) {
    INSTALL("install"),
    UPDATE("update"),
    CONSENT("consent"),
    ENABLE("enable"),
    DISABLE("disable"),
    REMOVE("remove"),
    ACTIVE("active"),
    FAULT("fault"),
    RESTART("restart"),
    STDERR("stderr"),
    REGISTRY_UNREADABLE("registry_unreadable"),
}

/**
 * The plugin host's lifecycle telemetry (letta-mobile-cdgpr.6): `plugin.<event>` under [TAG] for
 * install, update, consent, enable, disable, remove, fault and restart, each with the plugin id. A
 * refusal is the same event with `refused` set. letta-mobile-cdgpr.10 extends these.
 */
object PluginHostEvents {
    const val TAG: String = "PluginHost"

    fun record(event: PluginHostEvent, pluginId: String, vararg attrs: Pair<String, String>) {
        Telemetry.event(TAG, "plugin.${event.wire}", "plugin" to pluginId, *attrs)
    }

    fun refused(event: PluginHostEvent, pluginId: String, refusal: PluginHostResult.Refused) {
        Telemetry.event(TAG, "plugin.${event.wire}", "plugin" to pluginId, "refused" to refusal.message, level = Telemetry.Level.WARN)
    }
}
