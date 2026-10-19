package com.letta.mobile.data.plugin.catalog

import com.letta.mobile.data.repository.api.FeatureFlag
import com.letta.mobile.data.repository.api.ISettingsRepository
import com.letta.mobile.data.settings.SettingValue
import com.letta.mobile.data.settings.SettingsContribution
import com.letta.mobile.data.settings.SettingsGroup
import com.letta.mobile.data.settings.SettingsRow
import com.letta.mobile.data.settings.SettingsSectionModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * The Settings page's way into the Plugins page, the same on every client:
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

/**
 * The Plugins entry, or none when [onOpen] is null. A host that does not
 * advertise `plugins_v1` gets no entry.
 */
fun pluginsSettingsContributionOrNull(onOpen: (() -> Unit)?): SettingsContribution? = onOpen?.let(::pluginsSettingsContribution)

const val PLUGINS_SECTION_KEY = "plugins"

/**
 * A "Live plugin views" switch kept in memory, for tests and previews. The apps use the persisted
 * one ([liveViewsSetting]).
 */
fun inMemoryLiveViewsSetting(initial: Boolean = false): SettingValue<Boolean> {
    val value = MutableStateFlow(initial)
    return SettingValue(value) { value.value = it }
}

/**
 * This device's "Live plugin views" switch, persisted in [settings] under
 * [FeatureFlag.LIVE_PLUGIN_VIEWS]. On Android that is a DataStore preference. A write applies at
 * once and is saved on [scope].
 */
fun liveViewsSetting(settings: ISettingsRepository, scope: CoroutineScope): SettingValue<Boolean> {
    val flag = FeatureFlag.LIVE_PLUGIN_VIEWS
    val value = MutableStateFlow(flag.defaultEnabled)
    scope.launch { settings.getFeatureFlag(flag).collect { value.value = it } }
    return SettingValue(value.asStateFlow()) { on ->
        value.value = on
        scope.launch { settings.setFeatureFlag(flag, on) }
    }
}
