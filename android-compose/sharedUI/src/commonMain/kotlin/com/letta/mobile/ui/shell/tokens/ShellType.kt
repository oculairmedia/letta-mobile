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
}
