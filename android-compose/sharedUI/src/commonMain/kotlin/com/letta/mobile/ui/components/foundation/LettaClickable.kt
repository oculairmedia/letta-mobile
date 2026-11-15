package com.letta.mobile.ui.components.foundation

import androidx.compose.foundation.clickable
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.semantics.Role

/**
 * The one way to make a row or cell clickable: the press ripple clipped to [target]'s shape, a [role]
 * a screen reader announces ("Button", "Tab", "Checkbox"…), an optional [onClickLabel] naming what a
 * click does ("Open run"), and, for a [LettaClickTarget.Standalone] target (the default), at least the
 * platform's minimum touch target (48 dp) of layout. Pass [LettaClickTarget.Inline] for a line or a
 * dense row whose height must not move. Replaces bare `.clickable { }` calls on rows and cells, many
 * of which had no role and drew under the touch target.
 *
 * [role] is required on purpose: pick the one that says what the element is. A null [onClick] keeps
 * the role and shape but reads as disabled (no ripple, no click action). Put padding after this
 * modifier so the ripple covers the whole row. For a control with its own semantics (a switch row, a
 * swatch, an icon button) use that component instead.
 */
fun Modifier.lettaClickable(
    role: Role,
    onClickLabel: String? = null,
    target: LettaClickTarget = LettaClickTarget.Standalone,
    onClick: (() -> Unit)?,
): Modifier = this
    .then(if (target.minimumTouchTarget) Modifier.minimumInteractiveComponentSize() else Modifier)
    .then(if (target.shape == RectangleShape) Modifier else Modifier.clip(target.shape))
    .clickable(enabled = onClick != null, onClickLabel = onClickLabel, role = role, onClick = onClick ?: {})

/** [lettaClickable] on a standalone target whose ripple is clipped to [shape] (a rounded cell, a round avatar). */
fun Modifier.lettaClickable(
    role: Role,
    onClickLabel: String? = null,
    shape: Shape,
    onClick: (() -> Unit)?,
): Modifier = lettaClickable(role, onClickLabel, LettaClickTarget(shape), onClick)
