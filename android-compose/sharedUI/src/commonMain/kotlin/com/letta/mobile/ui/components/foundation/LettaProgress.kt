package com.letta.mobile.ui.components.foundation

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import com.letta.mobile.ui.theme.tokens.LettaControlTokens
import com.letta.mobile.ui.theme.tokens.LettaStrokeTokens

/*
 * The two loading primitives. A spinner says "this one thing is working" and comes in three sizes
 * by role; a busy bar says "this page or pane is loading or saving" and spans its width under the
 * header. Before these, 24 spinners used four strokes and four sizes and 27 bars were hand-rolled.
 */

/**
 * An indeterminate spinner. [size] is one of the three spinner tokens, chosen by role:
 * [LettaControlTokens.spinnerSm] inside a line of text or a chip, [LettaControlTokens.spinnerMd]
 * in place of an icon (a button's or a row's), [LettaControlTokens.spinnerLg] for a pane loading
 * in place of its content. The stroke follows the size.
 */
@Composable
fun LettaSpinner(
    modifier: Modifier = Modifier,
    size: Dp = LettaControlTokens.spinnerMd,
    color: Color = MaterialTheme.colorScheme.primary,
) {
    CircularProgressIndicator(
        modifier = modifier.size(size),
        color = color,
        strokeWidth = if (size >= LettaControlTokens.spinnerLg) LettaStrokeTokens.spinnerLg else LettaStrokeTokens.spinner,
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
