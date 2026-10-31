package com.letta.mobile.ui.theme.pill

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

/**
 * A tonal pill's two inks: its [container] and the [content] drawn on it, usually a Material
 * container role and its on-container partner (tertiaryContainer / onTertiaryContainer).
 *
 * Kept in a package that imports nothing of the app's, so any page can name it without an import
 * cycle; the pills themselves are LettaTonalPill and its family in ui.theme.
 */
@Immutable
data class LettaPillColors(val container: Color, val content: Color)
