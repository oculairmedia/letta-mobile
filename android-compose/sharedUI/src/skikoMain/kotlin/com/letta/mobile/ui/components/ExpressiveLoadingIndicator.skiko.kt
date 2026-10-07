package com.letta.mobile.ui.components

import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.letta.mobile.ui.theme.LettaDimens

@Composable
internal actual fun ExpressiveLoadingIndicator(color: Color, still: Boolean, modifier: Modifier) {
    if (still) {
        CircularProgressIndicator(progress = { STILL_PROGRESS }, modifier = modifier, color = color, strokeWidth = LettaDimens.Space.hair)
    } else {
        CircularProgressIndicator(modifier = modifier, color = color, strokeWidth = LettaDimens.Space.hair)
    }
}

/** A still arc, so the reduced-motion spinner still reads as "working". */
private const val STILL_PROGRESS = 0.75f
