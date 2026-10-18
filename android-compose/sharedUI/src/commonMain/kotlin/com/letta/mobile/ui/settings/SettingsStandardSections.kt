package com.letta.mobile.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.letta.mobile.data.model.AppTheme
import com.letta.mobile.data.model.ThemePreset
import com.letta.mobile.data.runtime.PermissionModeSettings
import com.letta.mobile.data.settings.AppearanceSettings
import com.letta.mobile.data.settings.ChatSettings
import com.letta.mobile.data.settings.NotificationSettings
import com.letta.mobile.data.settings.SettingValue
import com.letta.mobile.data.settings.SettingsOption
import com.letta.mobile.data.settings.SettingsRow
import com.letta.mobile.data.settings.SettingsStatus
import com.letta.mobile.data.settings.SettingsTone
import com.letta.mobile.data.transport.appserver.AppServerPermissionMode
import com.letta.mobile.ui.chat.surface.composer.description
import com.letta.mobile.ui.chat.surface.composer.displayName
import com.letta.mobile.ui.theme.LocalAccessibilityPolicy
import com.letta.mobile.ui.theme.LocalHighContrastChoice

// letta-mobile-ckmub.3.12 (S10) / ckmub.3.12.1: the Settings page's standard sections, drawn the same
// on every platform from the ports in com.letta.mobile.data.settings. A null port leaves its rows
// out; the group header above each card names it, so the cards carry no title of their own.

/** Theme, preset, dynamic colour and haptics; nothing when the platform binds none of them. */
@Composable
fun AppearanceSettingsSection(settings: AppearanceSettings, modifier: Modifier = Modifier) {
    if (settings.isEmpty) return
    SettingsSection(SettingsHeader("appearance", "Appearance", showTitle = false), modifier) {
        settings.theme?.let { EnumChoice(it, THEME_CHOICE) }
        settings.dynamicColor?.let { ToggleSetting(it, SettingsCopy.DYNAMIC_COLOR) }
        settings.themePreset?.let { preset ->
            val dynamicOn = settings.dynamicColor?.current?.collectAsState()?.value == true
            EnumChoice(preset, PRESET_CHOICE.copy(supporting = if (dynamicOn) "Dynamic colour overrides the preset" else null))
        }
        settings.haptics?.let { ToggleSetting(it, SettingsCopy.HAPTICS) }
    }
}

/** OS reduced-motion fact and the high-contrast toggle that actually switches colour tokens. */
@Composable
fun AccessibilitySettingsSection(modifier: Modifier = Modifier) {
    val policy = LocalAccessibilityPolicy.current
    val setHighContrast = LocalHighContrastChoice.current
    SettingsSection(SettingsHeader("accessibility", "Accessibility", showTitle = false), modifier) {
        InfoRow(
            SettingsRow.Info(
                key = SettingsCopy.REDUCED_MOTION.key,
                title = SettingsCopy.REDUCED_MOTION.title,
                value = if (policy.reducedMotion) "On" else "Off",
                supporting = SettingsCopy.REDUCED_MOTION.helper,
            ),
        )
        ToggleRow(
            SettingsRow.Toggle(
                key = SettingsCopy.HIGH_CONTRAST.key,
                title = SettingsCopy.HIGH_CONTRAST.title,
                checked = policy.highContrast,
                supporting = SettingsCopy.HIGH_CONTRAST.helper,
                onChange = setHighContrast,
            ),
        )
    }
}

/** Canvas-first conversations and the default permission mode. */
@Composable
fun ChatSettingsSection(settings: ChatSettings, modifier: Modifier = Modifier) {
    if (settings.isEmpty) return
    SettingsSection(SettingsHeader("chat", "Chat", showTitle = false), modifier) {
        settings.openChatsOnCanvas?.let { ToggleSetting(it, SettingsCopy.CANVAS) }
        settings.permissionMode?.let { PermissionModeRows(it) }
    }
}

/** The app-wide approval notification switch. */
@Composable
fun NotificationSettingsSection(settings: NotificationSettings, modifier: Modifier = Modifier) {
    val attention = settings.attention ?: return
    val prefs by attention.prefs.collectAsState()
    val label = SettingsCopy.APPROVALS
    SettingsSection(SettingsHeader("notifications", "Notifications", showTitle = false), modifier) {
        ToggleRow(
            SettingsRow.Toggle(
                key = label.key,
                title = label.title,
                checked = prefs.approvalNotificationsEnabled,
                supporting = label.helper,
                onChange = attention::setApprovalNotificationsEnabled,
            ),
        )
    }
}

/** The way to the provider accounts, keys and models; it needs a connected host. */
@Composable
fun ProvidersSettingsSection(onOpenProviders: () -> Unit, modifier: Modifier = Modifier) {
    val label = SettingsCopy.PROVIDERS
    SettingsSection(SettingsHeader("providers", "Providers", showTitle = false), modifier) {
        LinkRow(SettingsRow.Link(key = label.key, title = label.title, supporting = label.helper, onClick = onOpenProviders))
    }
}

@Composable
private fun SettingsScope.PermissionModeRows(settings: PermissionModeSettings) {
    val current by settings.defaultMode.collectAsState()
    var saveFailed by remember { mutableStateOf(false) }
    val label = SettingsCopy.PERMISSION_MODE
    ChoiceRow(
        SettingsRow.Choice(
            key = label.key,
            title = label.title,
            options = AppServerPermissionMode.entries.map { SettingsOption(it.name, it.displayName(), it.description()) },
            selected = current.name,
            supporting = label.helper,
            onSelect = { picked -> saveFailed = !settings.setDefaultMode(AppServerPermissionMode.valueOf(picked)) },
        ),
    )
    if (saveFailed) {
        NoteRow(SettingsRow.Note("chat-permission-mode-failed", SettingsStatus("Couldn't save the default; it is unchanged.", SettingsTone.Error)))
    }
}

@Composable
private fun SettingsScope.ToggleSetting(setting: SettingValue<Boolean>, label: SettingsLabel) {
    val on by setting.current.collectAsState()
    ToggleRow(SettingsRow.Toggle(key = label.key, title = label.title, checked = on, supporting = label.helper, onChange = setting::set))
}

/** A choice over an enum's values with their labels. */
private data class EnumChoiceSpec<T : Enum<T>>(
    val label: SettingsLabel,
    val entries: List<T>,
    val optionLabel: (T) -> String,
    val supporting: String? = null,
)

@Composable
private fun <T : Enum<T>> SettingsScope.EnumChoice(value: SettingValue<T>, spec: EnumChoiceSpec<T>) {
    val current by value.current.collectAsState()
    ChoiceRow(
        SettingsRow.Choice(
            key = spec.label.key,
            title = spec.label.title,
            options = spec.entries.map { SettingsOption(it.name, spec.optionLabel(it)) },
            selected = current.name,
            supporting = spec.supporting ?: spec.label.helper,
            onSelect = { picked -> spec.entries.firstOrNull { it.name == picked }?.let(value::set) },
        ),
    )
}

private val THEME_CHOICE = EnumChoiceSpec(SettingsCopy.THEME, AppTheme.entries, ::themeLabel)

private val PRESET_CHOICE = EnumChoiceSpec(SettingsCopy.PRESET, ThemePreset.entries, ::presetLabel)

private fun themeLabel(theme: AppTheme): String = when (theme) {
    AppTheme.SYSTEM -> "System"
    AppTheme.LIGHT -> "Light"
    AppTheme.DARK -> "Dark"
}

private fun presetLabel(preset: ThemePreset): String = when (preset) {
    ThemePreset.DEFAULT -> "Default"
    ThemePreset.OCEAN -> "Ocean"
    ThemePreset.AMOLED_BLACK -> "AMOLED black"
    ThemePreset.SAKURA -> "Sakura"
    ThemePreset.AUTUMN -> "Autumn"
    ThemePreset.SPRING -> "Spring"
}
