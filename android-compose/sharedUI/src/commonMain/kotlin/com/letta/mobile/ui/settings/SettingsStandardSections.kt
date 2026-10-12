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
import com.letta.mobile.ui.notify.AgentNotificationsStrings

// letta-mobile-ckmub.3.12 (S10): the Settings page's standard sections, drawn the same on every
// platform from the ports in com.letta.mobile.data.settings. A null port leaves its rows out.

/** Theme, preset, dynamic colour and haptics; nothing when the platform binds none of them. */
@Composable
fun AppearanceSettingsSection(settings: AppearanceSettings, modifier: Modifier = Modifier) {
    if (settings.isEmpty) return
    SettingsSection(SettingsHeader("appearance", "Appearance"), modifier) {
        settings.theme?.let { EnumChoice(it, THEME_CHOICE) }
        settings.dynamicColor?.let { ToggleSetting(it, ToggleText("appearance-dynamic-color", "Dynamic colour", "Use the colours of your wallpaper.")) }
        settings.themePreset?.let { preset ->
            val dynamicOn = settings.dynamicColor?.current?.collectAsState()?.value == true
            EnumChoice(preset, PRESET_CHOICE.copy(supporting = if (dynamicOn) "Dynamic colour overrides the preset." else null))
        }
        settings.haptics?.let { ToggleSetting(it, ToggleText("appearance-haptics", "Haptics", "Vibrate on streaming and tool activity.")) }
    }
}

/** Canvas-first conversations and the default permission mode. */
@Composable
fun ChatSettingsSection(settings: ChatSettings, modifier: Modifier = Modifier) {
    if (settings.isEmpty) return
    SettingsSection(SettingsHeader("chat", "Chat"), modifier) {
        settings.openChatsOnCanvas?.let {
            ToggleSetting(it, ToggleText("chat-open-on-canvas", "Open conversations on the canvas", "Turn off to open the full-screen chat instead."))
        }
        settings.permissionMode?.let { PermissionModeRows(it) }
    }
}

/** The app-wide approval notification switch. */
@Composable
fun NotificationSettingsSection(settings: NotificationSettings, modifier: Modifier = Modifier) {
    val attention = settings.attention ?: return
    val prefs by attention.prefs.collectAsState()
    SettingsSection(SettingsHeader("notifications", "Notifications"), modifier) {
        ToggleRow(
            SettingsRow.Toggle(
                key = "notifications-approvals",
                title = AgentNotificationsStrings.APPROVALS_SWITCH,
                checked = prefs.approvalNotificationsEnabled,
                supporting = "Finished replies notify you either way; mute an agent from its own settings.",
                onChange = attention::setApprovalNotificationsEnabled,
            ),
        )
    }
}

/** The way to the provider accounts, keys and models. */
@Composable
fun ProvidersSettingsSection(onOpenProviders: () -> Unit, modifier: Modifier = Modifier) {
    val header = SettingsHeader("providers", "Providers", "Provider accounts, API keys and endpoints, and the models the picker offers.")
    SettingsSection(header, modifier) {
        ActionRow("providers-actions", listOf(ActionButton("providers-open", "Manage providers", onOpenProviders, primary = true)))
    }
}

@Composable
private fun SettingsScope.PermissionModeRows(settings: PermissionModeSettings) {
    val current by settings.defaultMode.collectAsState()
    var saveFailed by remember { mutableStateOf(false) }
    ChoiceRow(
        SettingsRow.Choice(
            key = "chat-permission-mode",
            title = "Default permission mode",
            options = AppServerPermissionMode.entries.map { SettingsOption(it.name, it.displayName(), it.description()) },
            selected = current.name,
            supporting = "How tool calls are approved in conversations that have not picked a mode of their own. " +
                "Running conversations keep theirs; Iroh nodes decide their own.",
            onSelect = { picked -> saveFailed = !settings.setDefaultMode(AppServerPermissionMode.valueOf(picked)) },
        ),
    )
    if (saveFailed) {
        NoteRow(SettingsRow.Note("chat-permission-mode-failed", SettingsStatus("Could not save the default; it is unchanged.", SettingsTone.Error)))
    }
}

/** A switch's key, title and explanation. */
private class ToggleText(val key: String, val title: String, val supporting: String)

@Composable
private fun SettingsScope.ToggleSetting(setting: SettingValue<Boolean>, text: ToggleText) {
    val on by setting.current.collectAsState()
    ToggleRow(SettingsRow.Toggle(key = text.key, title = text.title, checked = on, supporting = text.supporting, onChange = setting::set))
}

/** A choice over an enum's values with their labels. */
private data class EnumChoiceSpec<T : Enum<T>>(
    val key: String,
    val title: String,
    val entries: List<T>,
    val label: (T) -> String,
    val supporting: String? = null,
)

@Composable
private fun <T : Enum<T>> SettingsScope.EnumChoice(value: SettingValue<T>, spec: EnumChoiceSpec<T>) {
    val current by value.current.collectAsState()
    ChoiceRow(
        SettingsRow.Choice(
            key = spec.key,
            title = spec.title,
            options = spec.entries.map { SettingsOption(it.name, spec.label(it)) },
            selected = current.name,
            supporting = spec.supporting,
            onSelect = { picked -> spec.entries.firstOrNull { it.name == picked }?.let(value::set) },
        ),
    )
}

private val THEME_CHOICE = EnumChoiceSpec("appearance-theme", "Theme", AppTheme.entries, ::themeLabel)

private val PRESET_CHOICE = EnumChoiceSpec("appearance-theme-preset", "Colour preset", ThemePreset.entries, ::presetLabel)

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
