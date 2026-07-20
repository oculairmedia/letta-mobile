package com.letta.mobile.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

/**
 * Visual background preset for the chat surface. The sealed hierarchy is
 * effectively immutable - instances are created once and identity-compared -
 * so we mark it `@Immutable` to let Compose skip recompositions of
 * downstream consumers when the same instance is re-read from
 * `LocalChatBackground` (o7ob.2.6).
 */
@Immutable
sealed class ChatBackground(val key: String, val label: String) {

    object Default : ChatBackground(ChatBackgroundToken.Default.key, ChatBackgroundToken.Default.label)

    data class SolidColor(val color: Color, val name: String) :
        ChatBackground("solid_${name.lowercase().replace(' ', '_')}", name)

    data class Gradient(val colors: List<Color>, val name: String) :
        ChatBackground("gradient_${name.lowercase().replace(' ', '_')}", name) {
        fun toBrush(): Brush = Brush.verticalGradient(colors)
    }

    companion object {
        val solidPresets: List<SolidColor> =
            ChatBackgroundToken.solidPresets.map { it.toChatBackground() }

        val gradientPresets: List<Gradient> =
            ChatBackgroundToken.gradientPresets.map { it.toChatBackground() }

        // Build with a cast through Any? so the Kotlin compiler retains
        // null-safety checks - R8 can produce JVM-level nulls for object
        // singletons on some Android configurations.
        val allPresets: List<ChatBackground> by lazy {
            val raw: List<Any?> = listOf(Default) + solidPresets + gradientPresets
            raw.filterNotNull().filterIsInstance<ChatBackground>()
        }

        fun fromKey(key: String): ChatBackground {
            return try {
                ChatBackgroundToken.fromKey(key).toChatBackground()
            } catch (_: Exception) {
                Default
            }
        }
    }
}

private fun ChatBackgroundToken.toChatBackground(): ChatBackground =
    when (this) {
        ChatBackgroundToken.Default -> ChatBackground.Default
        is ChatBackgroundToken.SolidColor -> toChatBackground()
        is ChatBackgroundToken.Gradient -> toChatBackground()
    }

private fun ChatBackgroundToken.SolidColor.toChatBackground(): ChatBackground.SolidColor =
    ChatBackground.SolidColor(Color(argb), name)

private fun ChatBackgroundToken.Gradient.toChatBackground(): ChatBackground.Gradient =
    ChatBackground.Gradient(argbColors.map { Color(it) }, name)
