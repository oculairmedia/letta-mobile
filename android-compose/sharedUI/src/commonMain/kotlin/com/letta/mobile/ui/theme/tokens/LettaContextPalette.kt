package com.letta.mobile.ui.theme.tokens

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance

/**
 * One colour per section of the context-window breakdown (its bar and legend), distinguishable side by
 * side. The M3 scheme has too few accents for ten sections, so these are fixed hues, one set per
 * theme: each reads at 3:1 or more against every surface of its theme (down to the popover's
 * surfaceContainer), as a graphic must. [Dark] is the
 * original desktop set (DesktopComposerContextUsage); [Light] deepens the same hues until they clear
 * a light surface.
 */
@Immutable
internal class LettaContextPalette(
    val system: Color,
    val toolDefinitions: Color,
    val toolRules: Color,
    val coreMemory: Color,
    val memoryFiles: Color,
    val directories: Color,
    val summaryMemory: Color,
    val externalSummary: Color,
    val messages: Color,
    val unitemised: Color,
) {
    companion object {
        val Light: LettaContextPalette = LettaContextPalette(
            system = Color(0xFFC0760E),
            toolDefinitions = Color(0xFF3D83FF),
            toolRules = Color(0xFF7C6CF0),
            coreMemory = Color(0xFF29986D),
            memoryFiles = Color(0xFF2793A6),
            directories = Color(0xFFA56AEE),
            summaryMemory = Color(0xFFDB55A4),
            externalSummary = Color(0xFFD9534F),
            messages = Color(0xFFE75924),
            unitemised = Color(0xFF7E889D),
        )

        val Dark: LettaContextPalette = LettaContextPalette(
            system = Color(0xFFF0A030),
            toolDefinitions = Color(0xFF4C8DFF),
            toolRules = Color(0xFF7C6CF0),
            coreMemory = Color(0xFF34C08A),
            memoryFiles = Color(0xFF2FB0C7),
            directories = Color(0xFFB07CF0),
            summaryMemory = Color(0xFFE06CB0),
            externalSummary = Color(0xFFD9534F),
            messages = Color(0xFFE8622F),
            unitemised = Color(0xFF8A93A6),
        )

        /** The set that reads on [surface]: [Light] on a light surface, [Dark] on a dark one. */
        fun forSurface(surface: Color): LettaContextPalette = if (surface.luminance() > LIGHT_SURFACE_LUMINANCE) Light else Dark

        private const val LIGHT_SURFACE_LUMINANCE = 0.5f
    }
}
