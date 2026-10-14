package com.letta.mobile.data.repository.api

/**
 * A preview switch the user flips in Settings: its persisted preference key and its value until
 * flipped. Read and written through [ISettingsRepository.getFeatureFlag] / [ISettingsRepository.setFeatureFlag],
 * so a new preview is one entry here rather than another getter/setter pair on every repository.
 *
 * by4vm.26 deleted this enum when its last entry went; letta-mobile-cdgpr.8 restores it for
 * [LIVE_PLUGIN_VIEWS], the only flag. Do not re-add the legacy drawer entry.
 */
enum class FeatureFlag(val preferenceKey: String, val defaultEnabled: Boolean) {
    /**
     * letta-mobile-cdgpr.8: this device's "Live plugin views" switch, set on the Plugins page. Off by
     * default, so plugin elements draw their fallback cards until the owner turns it on.
     */
    LIVE_PLUGIN_VIEWS("plugins.live_views", defaultEnabled = false),
}
