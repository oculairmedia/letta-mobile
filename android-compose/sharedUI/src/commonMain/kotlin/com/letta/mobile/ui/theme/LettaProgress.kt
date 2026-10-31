package com.letta.mobile.ui.theme

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp

/*
 * The two loading primitives. A spinner says "this one thing is working" and comes in three sizes
 * by role; a busy bar says "this page or pane is loading or saving" and spans its width under the
 * header. Before these, 24 spinners used four strokes and four sizes and 27 bars were hand-rolled.
 * They live beside the tokens (not in ui.components) so any page can use them through the theme
 * import it already has.
 */

/**
 * An indeterminate spinner. [size] is one of the three spinner tokens, chosen by role:
 * [LettaDimens.Control.spinnerSm] inside a line of text or a chip, [LettaDimens.Control.spinnerMd]
 * in place of an icon (a button's or a row's), [LettaDimens.Control.spinnerLg] for a pane loading
 * in place of its content. The stroke follows the size.
 */
@Composable
fun LettaSpinner(
    modifier: Modifier = Modifier,
    size: Dp = LettaDimens.Control.spinnerMd,
    color: Color = MaterialTheme.colorScheme.primary,
) {
    CircularProgressIndicator(
        modifier = modifier.size(size),
        color = color,
        strokeWidth = if (size >= LettaDimens.Control.spinnerLg) LettaDimens.Stroke.spinnerLg else LettaDimens.Stroke.spinner,
    )
}

/**
 * The full-width bar a page or pane shows under its header while it loads or saves: indeterminate,
 * or determinate when [progress] is given. Callers show it only while busy.
 */
@Composable
fun LettaBusyBar(modifier: Modifier = Modifier, progress: (() -> Float)? = null) {
    if (progress != null) {
        LinearProgressIndicator(progress = progress, modifier = modifier.fillMaxWidth())
    } else {
        LinearProgressIndicator(modifier = modifier.fillMaxWidth())
    }
}
