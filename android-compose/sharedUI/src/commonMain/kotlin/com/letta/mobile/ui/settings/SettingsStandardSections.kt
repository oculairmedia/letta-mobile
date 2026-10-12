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
    if (settings.theme == null && settings.themePreset == null && settings.dynamicColor == null && settings.haptics == null) return
    val dynamicColorOn = settings.dynamicColor?.current?.collectAsState()?.value == true
    SettingsSection(key = "appearance", title = "Appearance", modifier = modifier) {
        settings.theme?.let { theme -> EnumChoice("appearance-theme", "Theme", theme, AppTheme.entries, ::themeLabel) }
        settings.dynamicColor?.let { ToggleSetting("appearance-dynamic-color", "Dynamic colour", it, "Use the colours of your wallpaper.") }
        settings.themePreset?.let { preset ->
            EnumChoice(
                key = "appearance-theme-preset",
                title = "Colour preset",
                value = preset,
                entries = ThemePreset.entries,
                label = ::presetLabel,
                supporting = if (dynamicColorOn) "Dynamic colour overrides the preset." else null,
            )
        }
        settings.haptics?.let { ToggleSetting("appearance-haptics", "Haptics", it, "Vibrate on streaming and tool activity.") }
    }
}

/** The shared chat page switch, canvas-first conversations and the default permission mode. */
@Composable
fun ChatSettingsSection(settings: ChatSettings, modifier: Modifier = Modifier) {
    if (settings.sharedChatPage == null && settings.openChatsOnCanvas == null && settings.permissionMode == null) return
    SettingsSection(key = "chat", title = "Chat", modifier = modifier) {
        settings.sharedChatPage?.let { flag ->
            val locked = settings.sharedChatPageLockedReason
            val on by flag.current.collectAsState()
            ToggleRow(
                key = "chat-shared-page",
                title = "Shared chat page",
                checked = on || locked != null,
                onChange = flag::set,
                supporting = locked ?: "Draw conversations with the chat page every platform shares.",
                enabled = locked == null,
            )
        }
        settings.openChatsOnCanvas?.let {
            ToggleSetting("chat-open-on-canvas", "Open conversations on the canvas", it, "Turn off to open the full-screen chat instead.")
        }
        settings.permissionMode?.let { PermissionModeRows(it) }
    }
}

/** The app-wide approval notification switch. */
@Composable
fun NotificationSettingsSection(settings: NotificationSettings, modifier: Modifier = Modifier) {
    val attention = settings.attention ?: return
    val prefs by attention.prefs.collectAsState()
    SettingsSection(key = "notifications", title = "Notifications", modifier = modifier) {
        ToggleRow(
            key = "notifications-approvals",
            title = AgentNotificationsStrings.APPROVALS_SWITCH,
            checked = prefs.approvalNotificationsEnabled,
            onChange = attention::setApprovalNotificationsEnabled,
            supporting = "Finished replies notify you either way; mute an agent from its own settings.",
        )
    }
}

/** The way to the provider accounts, keys and models. */
@Composable
fun ProvidersSettingsSection(onOpenProviders: () -> Unit, modifier: Modifier = Modifier) {
    SettingsSection(
        key = "providers",
        title = "Providers",
        supporting = "Provider accounts, API keys and endpoints, and the models the picker offers.",
        modifier = modifier,
    ) {
        ActionRow("providers-actions", listOf(ActionButton("providers-open", "Manage providers", onOpenProviders, primary = true)))
    }
}

@Composable
private fun SettingsScope.PermissionModeRows(settings: PermissionModeSettings) {
    val current by settings.defaultMode.collectAsState()
    var saveFailed by remember { mutableStateOf(false) }
    ChoiceRow(
        key = "chat-permission-mode",
        title = "Default permission mode",
        options = AppServerPermissionMode.entries.map { SettingsOption(it.name, it.displayName(), it.description()) },
        selected = current.name,
        onSelect = { picked -> saveFailed = !settings.setDefaultMode(AppServerPermissionMode.valueOf(picked)) },
        supporting = "How tool calls are approved in conversations that have not picked a mode of their own. " +
            "Running conversations keep theirs; Iroh nodes decide their own.",
    )
    if (saveFailed) NoteRow("chat-permission-mode-failed", SettingsStatus("Could not save the default; it is unchanged.", SettingsTone.Error))
}

@Composable
private fun SettingsScope.ToggleSetting(key: String, title: String, setting: SettingValue<Boolean>, supporting: String) {
    val on by setting.current.collectAsState()
    ToggleRow(key = key, title = title, checked = on, onChange = setting::set, supporting = supporting)
}

@Composable
private fun <T : Enum<T>> SettingsScope.EnumChoice(
    key: String,
    title: String,
    value: SettingValue<T>,
    entries: List<T>,
    label: (T) -> String,
    supporting: String? = null,
) {
    val current by value.current.collectAsState()
    ChoiceRow(
        key = key,
        title = title,
        options = entries.map { SettingsOption(it.name, label(it)) },
        selected = current.name,
        onSelect = { picked -> entries.firstOrNull { it.name == picked }?.let(value::set) },
        supporting = supporting,
    )
}

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
