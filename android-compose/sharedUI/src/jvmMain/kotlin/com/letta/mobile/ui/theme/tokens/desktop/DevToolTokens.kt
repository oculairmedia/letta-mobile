package com.letta.mobile.ui.theme.tokens.desktop

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The Rive avatar workbench's fixed inks: a developer surface drawn over the art, always dark, so
 * it keeps its own colours rather than following the app theme.
 */
object RiveWorkbenchColors {
    /** The telemetry panel's heading. */
    val heading: Color = Color(0xFFBBBBBB)

    /** The telemetry panel's empty-state hint. */
    val hint: Color = Color(0xFF808080)

    /** A probed property's readout. */
    val readout: Color = Color(0xFFDDDDDD)

    /** The telemetry sparkline's bars. */
    val sparkBar: Color = Color(0xFF6FA8FF)

    /** An unselected surround node's ring. */
    val nodeRing: Color = Color(0xFFBBBBBB)

    /** The dark under-stroke behind every node ring: black at half strength. */
    val nodeRingShadow: Color = Color(red = 0f, green = 0f, blue = 0f, alpha = 0.5f)
}

/**
 * The ambient shader lookdev: the tints it feeds the glow (the identity and status hues the chat
 * glow is tuned for), the near-black stage it previews them on, and its panel sizes.
 */
object ShaderLookdevTokens {
    /** Teal: the running status tint, and the tint the lookdev opens with. */
    val tealRunning: Color = Color(0xFF3FE0C0)

    /** The tints offered as swatches, in order: teal (running), pink identity, amber identity, error red, violet identity. */
    val tintPresets: List<Color> = listOf(
        tealRunning,
        Color(0xFFE0457B),
        Color(0xFFF0A03C),
        Color(0xFFFF5449),
        Color(0xFF8E7CFF),
    )

    /** The preview stage behind the glow. */
    val stage: Color = Color(0xFF0A0A0A)

    /** The sample chat text drawn over the glow. */
    val sampleText: Color = Color(0xFFB8C2C4)

    /** The controls column's width. */
    val controlsWidth: Dp = 360.dp

    /** The SkSL editor's minimum height. */
    val editorMinHeight: Dp = 420.dp
}
