package com.letta.mobile.data.plugin.catalog

import com.letta.mobile.data.settings.SettingValue
import com.letta.mobile.data.settings.SettingsContribution
import com.letta.mobile.data.settings.SettingsGroup
import com.letta.mobile.data.settings.SettingsRow
import com.letta.mobile.data.settings.SettingsSectionModel
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * letta-mobile-cdgpr.9: the Settings page's way into the Plugins page, the same on every client:
 * a section in the Providers group (beside what agents may reach) whose row opens [onOpen].
 */
fun pluginsSettingsContribution(onOpen: () -> Unit): SettingsContribution = SettingsContribution(
    SettingsGroup.Providers,
    SettingsSectionModel(
        key = PLUGINS_SECTION_KEY,
        title = "Plugins",
        supporting = "Install plugins from a file, review what each may do, and switch them off.",
        rows = listOf(SettingsRow.Link(key = "plugins-open", title = "Manage plugins", onClick = onOpen)),
    ),
)

const val PLUGINS_SECTION_KEY = "plugins"

/**
 * This device's "Live plugin views" switch, kept in memory. letta-mobile-cdgpr.8 (C8) replaces it
 * with a persisted per-device setting wired to the plugin view host.
 */
fun inMemoryLiveViewsSetting(initial: Boolean = false): SettingValue<Boolean> {
    val value = MutableStateFlow(initial)
    return SettingValue(value) { value.value = it }
}
