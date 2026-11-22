package com.letta.mobile.data.model

enum class AppTheme {
    LIGHT,
    DARK,
    SYSTEM,
    ;

    /** Whether to draw dark: [systemDark] (the OS's setting) for [SYSTEM], else what this theme says. */
    fun isDark(systemDark: Boolean): Boolean = when (this) {
        LIGHT -> false
        DARK -> true
        SYSTEM -> systemDark
    }
}

enum class ThemePreset {
    DEFAULT,
    OCEAN,
    AMOLED_BLACK,
    SAKURA,
    AUTUMN,
    SPRING,
}
