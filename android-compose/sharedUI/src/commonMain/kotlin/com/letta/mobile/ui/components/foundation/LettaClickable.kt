package com.letta.mobile.ui.components.foundation

import androidx.compose.foundation.clickable
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.semantics.Role

/**
 * The one way to make a row or cell clickable: the press ripple clipped to [shape], a [role] a screen
 * reader announces ("Button", "Tab", "Checkbox"…), an optional [onClickLabel] naming what a click does
 * ("Open run"), and at least the platform's minimum touch target (48 dp) of layout. Replaces bare
 * `.clickable { }` calls on rows and cells, many of which had no role and drew under the touch target.
 *
 * [role] is required on purpose: pick the one that says what the element is. A null [onClick] keeps
 * the role and shape but reads as disabled (no ripple, no click action). Put padding after this
 * modifier so the ripple covers the whole row. For a control with its own semantics (a switch row, a
 * swatch, an icon button) use that component instead.
 */
fun Modifier.lettaClickable(
    role: Role,
    onClickLabel: String? = null,
    shape: Shape = RectangleShape,
    onClick: (() -> Unit)?,
): Modifier = this
    .minimumInteractiveComponentSize()
    .clip(shape)
    .clickable(enabled = onClick != null, onClickLabel = onClickLabel, role = role, onClick = onClick ?: {})
