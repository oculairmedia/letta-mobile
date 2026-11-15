package com.letta.mobile.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/*
 * Sizes and colours owned by one designsystem component each, named here so the component reads
 * them rather than writing its own numbers. Shared scale values (spacing, controls, radii) come from
 * LettaDimens and MaterialTheme.shapes; only what is specific to one component lives here.
 */

/** CardGroup: corner of a lone, first, last or pressed card, the expressive outer radius. */
val CardGroupOuterCorner: Dp = 20.dp

/** CardGroup: corner where two cards meet, nearly square so the group reads as one block. */
val CardGroupInnerCorner: Dp = 4.dp

/** Voice dictation overlay: the instruction pill's lift above the navigation bar, clear of the composer. */
val VoiceOverlayInstructionInset: Dp = 96.dp

/** Voice dictation overlay: the audio wave's gradient, top to bottom (yellow, green, red, blue). */
val VoiceWavePalette: List<Color> = listOf(
    Color(0xFFFDDF85),
    Color(0xFFA0D0AF),
    Color(0xFFE25F57),
    Color(0xFF85B1F8),
)

/**
 * A glow's outer gradient stop. A glow fades to its own hue at this alpha rather than to
 * `Color.Transparent` (transparent black), so the unpremultiplied gradient does not darken towards
 * its edge.
 */
const val GLOW_EDGE_ALPHA: Float = 0f

/** AdaptiveDialog: widest the floating dialog grows on a large screen. */
val AdaptiveDialogMaxWidth: Dp = 900.dp

/** MathBlock: minimum height of a display-mode equation while KaTeX lays it out. */
val MathDisplayMinHeight: Dp = 60.dp

/** MermaidDiagram full screen: the faint surface wash behind the diagram, over the dark scrim (a fill, not text). */
const val MERMAID_VIEWPORT_FILL_ALPHA: Float = 0.08f

/** ThinkingTextToken: the dim ink either side of the sweep's bright band (a shimmer gradient stop). */
const val THINKING_SWEEP_TAIL_ALPHA: Float = 0.45f

/** TagDrillInDialog: the item card's surfaceVariant wash over the dialog surface (a fill, not text). */
const val TAG_DRILL_IN_CARD_FILL_ALPHA: Float = 0.45f
