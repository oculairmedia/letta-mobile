package com.letta.mobile.ui.theme.tokens

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Shape

/**
 * Named shapes for call sites that would otherwise build a `RoundedCornerShape` of their own.
 *
 * Corners come from one scale, `MaterialTheme.shapes` (extraSmall 4, small 8, medium 10, large 14,
 * extraLarge 16 dp on every host). Use a role there for anything that is not named here; these
 * names exist so a reader sees what the shape is for.
 */
object LettaShapes {
    /** Fully rounded ends: pills, chips drawn as capsules, status capsules. Replaces `RoundedCornerShape(percent = 50)`. */
    val pill: Shape = RoundedCornerShape(percent = PILL_PERCENT)

    /** A bordered or filled container (LettaPanel, inline notices, cards on a page): `MaterialTheme.shapes.medium`. */
    val panel: Shape
        @Composable
        @ReadOnlyComposable
        get() = MaterialTheme.shapes.medium

    /** A text input's outline (LettaSearchField): `MaterialTheme.shapes.medium`, the panel radius, so a field in a panel nests evenly. */
    val field: Shape
        @Composable
        @ReadOnlyComposable
        get() = MaterialTheme.shapes.medium

    private const val PILL_PERCENT = 50
}
