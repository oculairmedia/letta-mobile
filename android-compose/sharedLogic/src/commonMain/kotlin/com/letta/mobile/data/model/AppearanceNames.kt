package com.letta.mobile.data.model

/**
 * The theme called [name] on the host (the name its shell state document stores), or [AppTheme.SYSTEM] when none was
 * recorded or this build does not know the name: an older or newer client's choice never fails a draw.
 */
fun appThemeNamed(name: String?): AppTheme = AppTheme.entries.firstOrNull { it.name == name } ?: AppTheme.SYSTEM

/** The colour preset called [name] on the host, or [ThemePreset.DEFAULT] when none was recorded or this build does not know it. */
fun themePresetNamed(name: String?): ThemePreset = ThemePreset.entries.firstOrNull { it.name == name } ?: ThemePreset.DEFAULT
