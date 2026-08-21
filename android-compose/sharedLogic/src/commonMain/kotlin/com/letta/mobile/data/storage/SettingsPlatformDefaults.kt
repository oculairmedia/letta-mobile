package com.letta.mobile.data.storage

/**
 * Host-supplied defaults that previously lived in Android [android.os.Build] /
 * [com.letta.mobile.core.BuildConfig] checks inside [SettingsRepository].
 */
data class SettingsPlatformDefaults(
    val defaultResumeRecentConversation: Boolean = false,
    val defaultDynamicColorWhenPresetUnset: Boolean = false,
)
