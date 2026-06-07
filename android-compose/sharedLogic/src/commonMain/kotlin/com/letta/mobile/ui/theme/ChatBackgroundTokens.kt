package com.letta.mobile.ui.theme

sealed class ChatBackgroundToken(
    val key: String,
    val label: String,
) {
    data object Default : ChatBackgroundToken("default", "Default")

    data class SolidColor(
        val argb: Long,
        val name: String,
    ) : ChatBackgroundToken("solid_${name.toTokenSlug()}", name)

    data class Gradient(
        val argbColors: List<Long>,
        val name: String,
    ) : ChatBackgroundToken("gradient_${name.toTokenSlug()}", name)

    companion object {
        val solidPresets: List<SolidColor> = listOf(
            SolidColor(0xFF121212, "Charcoal"),
            SolidColor(0xFF1A1A2E, "Midnight"),
            SolidColor(0xFF0D1B2A, "Deep Navy"),
            SolidColor(0xFF1B2631, "Slate"),
            SolidColor(0xFF212121, "Dark Grey"),
            SolidColor(0xFFF5F5F5, "Light Grey"),
            SolidColor(0xFFFFF8E1, "Cream"),
            SolidColor(0xFFE8EAF6, "Lavender"),
        )

        val gradientPresets: List<Gradient> = listOf(
            Gradient(listOf(0xFF0D1B2A, 0xFF1B2631), "Night Sky"),
            Gradient(listOf(0xFF1A1A2E, 0xFF16213E), "Deep Space"),
            Gradient(listOf(0xFF0F2027, 0xFF203A43, 0xFF2C5364), "Ocean"),
            Gradient(listOf(0xFF141E30, 0xFF243B55), "Royal"),
            Gradient(listOf(0xFFE8EAF6, 0xFFC5CAE9), "Soft Indigo"),
            Gradient(listOf(0xFFFCE4EC, 0xFFF8BBD0), "Blush"),
        )

        val allPresets: List<ChatBackgroundToken> =
            listOf<ChatBackgroundToken>(Default) + solidPresets + gradientPresets

        fun fromKey(key: String): ChatBackgroundToken =
            allPresets.firstOrNull { it.key == key } ?: Default
    }
}

private fun String.toTokenSlug(): String =
    lowercase().replace(' ', '_')
