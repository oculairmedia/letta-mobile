package com.letta.mobile.ui.theme

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

sealed class ChatBackground(val key: String, val label: String) {

    object Default : ChatBackground("default", "Default")

    data class SolidColor(val color: Color, val name: String) :
        ChatBackground("solid_${name.lowercase().replace(' ', '_')}", name)

    data class Gradient(val colors: List<Color>, val name: String) :
        ChatBackground("gradient_${name.lowercase().replace(' ', '_')}", name) {
        fun toBrush(): Brush = Brush.verticalGradient(colors)
    }

    companion object {
        val solidPresets: List<SolidColor> = listOf(
            SolidColor(Color(0xFF121212), "Charcoal"),
            SolidColor(Color(0xFF1A1A2E), "Midnight"),
            SolidColor(Color(0xFF0D1B2A), "Deep Navy"),
            SolidColor(Color(0xFF1B2631), "Slate"),
            SolidColor(Color(0xFF212121), "Dark Grey"),
            SolidColor(Color(0xFFF5F5F5), "Light Grey"),
            SolidColor(Color(0xFFFFF8E1), "Cream"),
            SolidColor(Color(0xFFE8EAF6), "Lavender"),
        )

        val gradientPresets: List<Gradient> = listOf(
            Gradient(listOf(Color(0xFF0D1B2A), Color(0xFF1B2631)), "Night Sky"),
            Gradient(listOf(Color(0xFF1A1A2E), Color(0xFF16213E)), "Deep Space"),
            Gradient(listOf(Color(0xFF0F2027), Color(0xFF203A43), Color(0xFF2C5364)), "Ocean"),
            Gradient(listOf(Color(0xFF141E30), Color(0xFF243B55)), "Royal"),
            Gradient(listOf(Color(0xFFE8EAF6), Color(0xFFC5CAE9)), "Soft Indigo"),
            Gradient(listOf(Color(0xFFFCE4EC), Color(0xFFF8BBD0)), "Blush"),
        )

        // Build with a cast through Any? so the Kotlin compiler retains
        // null-safety checks — R8 can produce JVM-level nulls for object
        // singletons on some Android configurations.
        val allPresets: List<ChatBackground> by lazy {
            val raw: List<Any?> = listOf(Default) + solidPresets + gradientPresets
            raw.filterNotNull().filterIsInstance<ChatBackground>()
        }

        fun fromKey(key: String): ChatBackground {
            return try {
                allPresets.firstOrNull { it.key == key } ?: Default
            } catch (_: Exception) {
                Default
            }
        }
    }
}

val LocalChatBackground = staticCompositionLocalOf<ChatBackground> { ChatBackground.Default }
