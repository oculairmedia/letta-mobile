package com.letta.mobile.ui.shell.tokens

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * Text styles only the shell draws. Everything else uses a typography role or a LettaTextStyles
 * token.
 */
internal object ShellType {
    /** The brand wordmark on Home's connection hero: `displayLarge` in a serif, semibold, untracked. */
    val wordmark: TextStyle
        @Composable
        @ReadOnlyComposable
        get() = MaterialTheme.typography.displayLarge.copy(
            fontFamily = FontFamily.Serif,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 0.sp,
        )

    /** A failure's headline on Home's connection hero: `headlineSmall` semibold. */
    val heroHeadline: TextStyle
        @Composable
        @ReadOnlyComposable
        get() = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.SemiBold)

    /** The title of a Channels or Skills detail pane: `titleLarge` semibold. */
    val detailTitle: TextStyle
        @Composable
        @ReadOnlyComposable
        get() = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold)

    /** Body text that names the thing on its row (a fleet agent, a schedule's range or cadence): `bodyMedium` medium. */
    val bodyEmphasis: TextStyle
        @Composable
        @ReadOnlyComposable
        get() = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium)

    /** An agent shown by its raw id because its name is unresolved: `labelMedium` monospace at normal weight. */
    val idFallback: TextStyle
        @Composable
        @ReadOnlyComposable
        get() = MaterialTheme.typography.labelMedium.copy(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Normal)

    /** A vault secret's key: `labelLarge` monospace. */
    val secretKey: TextStyle
        @Composable
        @ReadOnlyComposable
        get() = MaterialTheme.typography.labelLarge.copy(fontFamily = FontFamily.Monospace)

    /** The initial drawn on an agent orb in the rail: `labelMedium` semibold. */
    val orbInitial: TextStyle
        @Composable
        @ReadOnlyComposable
        get() = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold)
}
