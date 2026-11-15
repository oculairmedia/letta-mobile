package com.letta.mobile.ui.theme.tokens

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight

/**
 * More named text styles in the manner of LettaTextStyles: a `MaterialTheme.typography` role with one
 * change, for the roles LettaTextStyles does not name. Use one of these instead of a `fontWeight`,
 * `fontFamily` or typography `.copy(` at a call site; for a role with no override use the role itself.
 */
object LettaTextAccents {
    /** The selected one of a set of small labels (an active chip or tab), or a short value beside its label. `labelMedium` semibold. */
    val labelMediumSemibold: TextStyle
        @Composable
        @ReadOnlyComposable
        get() = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold)

    /** A short emphasised note in small body text (a status word in a fact list). `bodySmall` semibold. */
    val bodySmallSemibold: TextStyle
        @Composable
        @ReadOnlyComposable
        get() = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold)

    /** The selected or headline line in a list of body rows. `bodyMedium` semibold. */
    val bodySemibold: TextStyle
        @Composable
        @ReadOnlyComposable
        get() = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold)

    /** A pane's or an object's own title at the top of a side pane or editor. `titleLarge` semibold. */
    val titleLargeSemibold: TextStyle
        @Composable
        @ReadOnlyComposable
        get() = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold)

    /** A code a person reads off the screen and types elsewhere (a device sign-in code). `headlineSmall` in monospace. */
    val codeDisplay: TextStyle
        @Composable
        @ReadOnlyComposable
        get() = MaterialTheme.typography.headlineSmall.copy(fontFamily = FontFamily.Monospace)
}
