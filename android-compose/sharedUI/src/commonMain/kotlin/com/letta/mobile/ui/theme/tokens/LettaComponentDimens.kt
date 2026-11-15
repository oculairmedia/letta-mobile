package com.letta.mobile.ui.theme.tokens

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Sizes owned by one shared component each, so call sites pass none of their own. They sit on the
 * LettaDimens grid; they are named here because "the size of a status dot" is a fact about the dot,
 * not about spacing.
 */
object LettaComponentDimens {
    /** A status dot's diameter (LettaStatusDot): every hand-built dot used 8 dp. */
    val statusDot: Dp = 8.dp

    /** A colour swatch's default diameter (LettaColorSwatch). */
    val swatch: Dp = 24.dp

    /** The ring a selected swatch draws (LettaColorSwatch); an unselected one draws a hairline. */
    val swatchSelectedStroke: Dp = 2.dp

    /** The glyph above an empty state's words (LettaEmptyState). */
    val emptyStateIcon: Dp = 40.dp
}
