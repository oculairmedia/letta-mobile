package com.letta.mobile.ui.theme.tokens

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Leaf scale values shared by list rows and the broader Letta dimension facade. */
object LettaRowDimens {
    /** Compact content padding and gap, also exposed as LettaDimens.Space.sm. */
    val spacing: Dp = 8.dp

    /** Standard glyph size, also exposed as LettaDimens.Control.icon. */
    val icon: Dp = 18.dp

    /** Muted content opacity, also exposed as LettaDimens.Alpha.disabled. */
    const val disabledAlpha: Float = 0.6f
}
