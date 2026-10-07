package com.letta.mobile.ui.components

import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.letta.mobile.ui.theme.LettaDimens

/**
 * letta-mobile-bglj6.1.19: draws a loading indicator; [still] (reduced motion) draws it at rest,
 * never looping.
 */
@Immutable
fun interface ChatLoadingIndicator {
    @Composable
    fun Draw(color: Color, still: Boolean, modifier: Modifier)
}

/**
 * The host's loading indicator for the shared chat page. Android provides the Material 3
 * expressive LoadingIndicator (its shape morph, as the legacy chat's reasoning header shows), which
 * Compose Multiplatform's material3 does not expose; without a provider the page draws a small
 * circular spinner.
 */
val LocalChatLoadingIndicator: ProvidableCompositionLocal<ChatLoadingIndicator?> = staticCompositionLocalOf { null }

@Composable
internal fun ExpressiveLoadingIndicator(color: Color, still: Boolean, modifier: Modifier = Modifier) {
    val host = LocalChatLoadingIndicator.current
    if (host != null) {
        host.Draw(color, still, modifier)
    } else if (still) {
        CircularProgressIndicator(progress = { STILL_PROGRESS }, modifier = modifier, color = color, strokeWidth = LettaDimens.Space.hair)
    } else {
        CircularProgressIndicator(modifier = modifier, color = color, strokeWidth = LettaDimens.Space.hair)
    }
}

/** A still arc, so the reduced-motion spinner still reads as "working". */
private const val STILL_PROGRESS = 0.75f
