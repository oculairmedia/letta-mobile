package com.letta.mobile.ui.theme.tokens

import androidx.compose.ui.unit.Dp

/**
 * A text input's border (the prompt card, a field) in its three looks: a quiet line at rest, a firmer
 * one under the pointer, the primary colour while it holds focus. See LettaFieldBorder.
 */
object LettaFieldTokens {
    /** The border's width in every look, so a change of look never moves what is inside. */
    val border: Dp = LettaStrokeTokens.hairline

    /** How much of `outlineVariant` the resting line carries: a hairline, as on any surface. */
    const val REST_ALPHA: Float = 0.5f

    /** How long the border takes to change look: the chips' duration, quick enough to follow a pointer. */
    const val BORDER_MILLIS: Int = 150
}
