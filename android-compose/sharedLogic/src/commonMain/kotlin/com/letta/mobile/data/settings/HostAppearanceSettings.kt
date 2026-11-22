package com.letta.mobile.data.settings

import com.letta.mobile.data.model.AppTheme
import com.letta.mobile.data.model.appThemeNamed
import com.letta.mobile.data.model.ThemePreset
import com.letta.mobile.data.model.themePresetNamed
import com.letta.mobile.data.shellstate.AppearanceValues
import com.letta.mobile.data.shellstate.HostAppearance
import com.letta.mobile.data.shellstate.ShellStateEditOutcome
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * The Appearance rows of a client whose appearance is the host's ([HostAppearance]): theme and colour preset, and dynamic
 * colour only where [dynamicColorSupported] (wallpaper colours exist on Android 12 and later; elsewhere the row is not
 * offered and nothing draws it). What the rows show is what the host holds, and a change is a proposal to it: when the host
 * cannot take it, the rows keep showing what it holds and [AppearanceSettings.notice] says why. Nothing is queued.
 *
 * A colour preset other than Default turns the host's dynamic colour off (it would otherwise replace the preset on a client
 * that has it), on every client, whether or not this one offers the row; Default leaves it as the host holds it. [scope] runs
 * the proposals and shares the shown values for as long as a row is on screen.
 */
fun hostAppearanceSettings(
    appearance: HostAppearance,
    scope: CoroutineScope,
    dynamicColorSupported: Boolean = false,
): AppearanceSettings {
    val notice = MutableStateFlow<SettingsStatus?>(null)
    val edits = AppearanceEdits(appearance, notice)
    val shown = appearance.current
    return AppearanceSettings(
        theme = SettingValue(appearance.shown(scope, appThemeNamed(shown.theme)) { appThemeNamed(it.theme) }) {
            edits.launchIn(scope) { setTheme(it) }
        },
        themePreset = SettingValue(appearance.shown(scope, themePresetNamed(shown.themePreset)) { themePresetNamed(it.themePreset) }) {
            edits.launchIn(scope) { setPreset(it) }
        },
        dynamicColor = SettingValue(appearance.shown(scope, shown.dynamicColor == true) { it.dynamicColor == true }) {
            edits.launchIn(scope) { setDynamicColor(it) }
        }.takeIf { dynamicColorSupported },
        notice = notice.asStateFlow(),
    )
}

private fun <T> HostAppearance.shown(scope: CoroutineScope, initial: T, pick: (AppearanceValues) -> T): StateFlow<T> =
    values.map(pick).stateIn(scope, SharingStarted.WhileSubscribed(SHARING_STOP_TIMEOUT_MS), initial)

/** What an edit of the three choices says to the person, and in what order a preset change is proposed. */
private class AppearanceEdits(private val appearance: HostAppearance, private val notice: MutableStateFlow<SettingsStatus?>) {
    fun launchIn(scope: CoroutineScope, edit: suspend AppearanceEdits.() -> Unit) {
        scope.launch { edit() }
    }

    suspend fun setTheme(theme: AppTheme) {
        report(appearance.setTheme(theme.name))
    }

    suspend fun setDynamicColor(enabled: Boolean) {
        report(appearance.setDynamicColor(enabled))
    }

    /** One proposal: the host takes the preset and dynamic colour turned off together or neither, and no device sees one alone. */
    suspend fun setPreset(preset: ThemePreset) {
        report(appearance.selectThemePreset(preset.name, dynamicColor = false.takeIf { preset != ThemePreset.DEFAULT }))
    }

    private fun report(outcome: ShellStateEditOutcome): Boolean {
        notice.value = outcome.toSettingsStatus()
        return outcome == ShellStateEditOutcome.APPLIED
    }
}

private const val SHARING_STOP_TIMEOUT_MS = 5_000L
