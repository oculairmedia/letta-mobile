package com.letta.mobile.ui.ambient

import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * The look of the ambient glow the chat shows while the agent works. One global preference, kept
 * with the other [ChatDisplayOptions]; every style takes the same status, tint and motion inputs
 * ([ambientGlowShaderSource]), so only the picture changes.
 *
 * @property label The short name the chat options show for the style.
 */
enum class ThinkingGlowStyle(val label: String) {
    /** Soft colour currents drifting sideways along the bottom edge, with a dimmer layer behind. */
    Aurora("Aurora"),

    /** A halftone field of four-pointed sparkles, larger and brighter toward the composer. */
    Sparkle("Sparkle"),

    /** A calm gradient rising from the bottom edge in slow, broad swells. */
    Tide("Tide"),

    /** A few thin ribbons of light drifting above the composer. */
    Ribbons("Ribbons"),
    ;

    companion object {
        /** The style a fresh install shows. */
        val Default: ThinkingGlowStyle = Aurora

        /** The style stored as [name]; [Default] for nothing stored or a name this build does not know. */
        fun fromName(name: String?): ThinkingGlowStyle = entries.firstOrNull { it.name == name } ?: Default
    }
}

/**
 * The thinking glow's look on a chat page: the chat's display options (ChatDisplayOptions.thinkingGlow),
 * provided by ChatSurface. The docked panel's glow and the hosts' full-page glows (composed as
 * the page background, inside it) all read it, so one setting changes every glow.
 */
val LocalThinkingGlowStyle: ProvidableCompositionLocal<ThinkingGlowStyle> = staticCompositionLocalOf { ThinkingGlowStyle.Default }
