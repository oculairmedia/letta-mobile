package com.letta.mobile.ui.theme

import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import com.composables.icons.lucide.Check
import com.composables.icons.lucide.ChevronDown
import com.composables.icons.lucide.Circle
import com.composables.icons.lucide.CircleAlert
import com.composables.icons.lucide.CircleCheck
import com.composables.icons.lucide.Clock
import com.composables.icons.lucide.Lucide

/*
 * The small state marks every page shares, beside the pills (they take the same glyphs as LettaIcons,
 * read straight from Lucide so the theme does not depend on the icon catalogue):
 *
 *   LettaPendingGlyph     not started: an empty ring
 *   LettaInProgressGlyph  under way: a clock
 *   LettaDoneGlyph        finished well: a ringed check in the success colour
 *   LettaFailedGlyph      ended badly (failed, cancelled): a ringed alert in the error colour
 *   LettaCheckGlyph       chosen or included (the selected option, an installed command): a bare check
 *   LettaMenuIndicator    the still "opens a menu" mark at the end of a chip or a switcher
 *
 * The status glyphs are drawn at the standard glyph size. The pending, in-progress and check glyphs
 * are decorative (their rows say the state in words); the done and failed glyphs take an optional
 * contentDescription for a row that does not.
 */

/** A task, a todo or a step that has not started. */
@Composable
fun LettaPendingGlyph(modifier: Modifier = Modifier) {
    StatusGlyph(Lucide.Circle, MaterialTheme.colorScheme.onSurfaceVariant, modifier, contentDescription = null)
}

/** A task, a todo or a step under way. */
@Composable
fun LettaInProgressGlyph(modifier: Modifier = Modifier) {
    StatusGlyph(Lucide.Clock, MaterialTheme.colorScheme.primary, modifier, contentDescription = null)
}

/** A task, a todo or a run that finished well. */
@Composable
fun LettaDoneGlyph(modifier: Modifier = Modifier, contentDescription: String? = null) {
    val success = MaterialTheme.customColors.successColor.takeIf { it != Color.Unspecified } ?: MaterialTheme.colorScheme.primary
    StatusGlyph(Lucide.CircleCheck, success, modifier, contentDescription)
}

/** A task or a run that failed or was cancelled. */
@Composable
fun LettaFailedGlyph(modifier: Modifier = Modifier, contentDescription: String? = null) {
    StatusGlyph(Lucide.CircleAlert, MaterialTheme.colorScheme.error, modifier, contentDescription)
}

/** The chosen option in a list, or an item that is included: a bare check in the content colour. */
@Composable
fun LettaCheckGlyph(modifier: Modifier = Modifier) {
    StatusGlyph(Lucide.Check, LocalContentColor.current, modifier, contentDescription = null)
}

/**
 * The mark at the end of a control that opens a menu or a picker (a composer chip, the agent
 * switcher): a still downward chevron at the small glyph size, in [tint].
 */
@Composable
fun LettaMenuIndicator(
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
    tint: Color = LocalContentColor.current,
) {
    Icon(
        imageVector = Lucide.ChevronDown,
        contentDescription = contentDescription,
        modifier = modifier.size(LettaDimens.Control.iconSm),
        tint = tint,
    )
}

@Composable
private fun StatusGlyph(icon: ImageVector, tint: Color, modifier: Modifier, contentDescription: String?) {
    Icon(
        imageVector = icon,
        contentDescription = contentDescription,
        modifier = modifier.size(LettaDimens.Control.icon),
        tint = tint,
    )
}
