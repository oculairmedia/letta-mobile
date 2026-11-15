package com.letta.mobile.ui.components.disclosure

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

/**
 * How a [LettaMenuIndicator] draws: the chevron's [emphasis], whether it is the [compact] glyph, and
 * a [tint] when it takes its host's ink ([Color.Unspecified] keeps the emphasis tier's).
 */
@Immutable
data class LettaMenuIndicatorStyle(
    val emphasis: ChevronEmphasis = ChevronEmphasis.Neutral,
    val compact: Boolean = true,
    val tint: Color = Color.Unspecified,
) {
    companion object {
        /** A chip's mark: neutral, at the small glyph size. */
        val Default: LettaMenuIndicatorStyle = LettaMenuIndicatorStyle()
    }
}
