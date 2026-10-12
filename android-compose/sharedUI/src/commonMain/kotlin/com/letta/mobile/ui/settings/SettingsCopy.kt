package com.letta.mobile.ui.settings

import com.letta.mobile.ui.notify.AgentNotificationsStrings

/** A standard row's key, its label (1-3 words, sentence case) and its one-line helper (the effect, ~60 chars). */
data class SettingsLabel(val key: String, val title: String, val helper: String? = null)

/**
 * letta-mobile-ckmub.3.12.1: the words of the Settings page's standard rows, in one place so the rows
 * and the page search read the same text. Nouns for settings, verbs for actions, no Enable/Disable.
 */
object SettingsCopy {
    val MODE = SettingsLabel(BackendSettingsTags.MODE, "Runtime")
    val STATUS = SettingsLabel(BackendSettingsTags.STATUS, "Status")
    val ADDRESS = SettingsLabel(BackendSettingsTags.SERVER_URL, "Host address")
    val TOKEN = SettingsLabel(BackendSettingsTags.TOKEN, "Access token", "Only if the host asks for one.")
    val TEST = SettingsLabel(BackendSettingsTags.TEST, "Test connection", "Checks the address before you save it")
    val SCAN = SettingsLabel(BackendSettingsTags.SCAN, "Scan to connect", "Read the host's pairing QR code")

    val CANVAS = SettingsLabel("chat-open-on-canvas", "Open on the canvas", "Off opens conversations full screen")
    val PERMISSION_MODE = SettingsLabel("chat-permission-mode", "Permission mode", "For new conversations; Iroh hosts set their own")

    val THEME = SettingsLabel("appearance-theme", "Theme")
    val DYNAMIC_COLOR = SettingsLabel("appearance-dynamic-color", "Dynamic colour", "Use your wallpaper's colours")
    val PRESET = SettingsLabel("appearance-theme-preset", "Colour preset")
    val HAPTICS = SettingsLabel("appearance-haptics", "Haptics", "Vibrate while replies and tools run")

    val APPROVALS = SettingsLabel(
        "notifications-approvals",
        AgentNotificationsStrings.APPROVALS_SWITCH,
        "Replies notify either way; mute agents in their settings",
    )

    val PROVIDERS = SettingsLabel("providers-open", "Manage providers", "Accounts, API keys and the models offered")

    val CLEAR_TOKEN = SettingsLabel(BackendSettingsTags.CLEAR_TOKEN, "Remove access token", "Deletes the saved token from this device")
    val RESET_IDENTITY = SettingsLabel(BackendSettingsTags.RESET_IDENTITY, "Reset device identity", "Paired hosts must pair this device again")

    const val SEARCH = "Search settings"

    fun noMatch(query: String): String = "No settings match \"$query\""
}
