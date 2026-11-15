package com.letta.mobile.desktop

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Sizes of the desktop app's own OS windows and title bar: facts about the window frame, which no
 * shared page draws. The main window, the canvas debug windows and the shader lookdev all open at
 * [mainWidth] x [mainHeight].
 */
internal object DesktopWindowDimens {
    /** A top-level window's first size, before the user resizes it. */
    val mainWidth: Dp = 1280.dp
    val mainHeight: Dp = 820.dp

    /** The undecorated quick-query popup. */
    val quickQueryWidth: Dp = 680.dp
    val quickQueryHeight: Dp = 440.dp

    /**
     * The custom title bar: taller than a stock 32-44 dp caption bar so the two-line agent identity
     * block (title over agent name) fits.
     */
    val titleBarHeight: Dp = 48.dp

    /** The title-bar lane always left free for native window dragging. */
    val titleBarMinDragWidth: Dp = 96.dp
}

/** Values the desktop's drawn effects (glows, gradients) share. */
internal object DesktopEffectTokens {
    /**
     * The alpha of a glow's outer gradient stop. A glow fades to its own hue at this alpha, not to
     * `Color.Transparent` (transparent black), so the unpremultiplied gradient does not darken
     * towards its edge.
     */
    const val GLOW_EDGE_ALPHA: Float = 0f
}

/**
 * The Rive avatar workbench's palette: a developer surface drawn over the art, always dark, so it
 * keeps fixed inks rather than following the app theme.
 */
internal object RiveWorkbenchColors {
    /** The telemetry panel's heading. */
    val heading = Color(0xFFBBBBBB)

    /** The telemetry panel's empty-state hint. */
    val hint = Color(0xFF808080)

    /** A probed property's readout. */
    val readout = Color(0xFFDDDDDD)

    /** The sparkline's bars. */
    val sparkBar = Color(0xFF6FA8FF)

    /** A surround node's ring, its dark under-stroke, and the selected node's ring. */
    val nodeRing = Color(0xFFBBBBBB)
    val nodeRingShadow = Color(0x80000000)
    val nodeRingSelected = Color.White
}

/**
 * The ambient shader lookdev's palette: the preset tints it feeds the shader (the identity and
 * status hues the chat glow is tuned for) and the near-black stage it previews them on.
 */
internal object ShaderLookdevColors {
    val tealRunning = Color(0xFF3FE0C0)
    val pinkIdentity = Color(0xFFE0457B)
    val amberIdentity = Color(0xFFF0A03C)
    val errorRed = Color(0xFFFF5449)
    val violetIdentity = Color(0xFF8E7CFF)

    /** The tints offered as swatches, in order. */
    val presets: List<Color> = listOf(tealRunning, pinkIdentity, amberIdentity, errorRed, violetIdentity)

    /** The preview stage behind the glow. */
    val stage = Color(0xFF0A0A0A)
}
