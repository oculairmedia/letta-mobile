package com.letta.mobile.ui.theme.tokens

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight

/**
 * Named text styles for the overrides call sites used to make by hand (`style = ...copy(fontFamily = ...)`,
 * `fontWeight = FontWeight.SemiBold` next to a typography role). Each is a `MaterialTheme.typography`
 * role with one change, so it follows the theme's type scale on every host.
 *
 * Use a plain typography role when no override is wanted; use one of these instead of a `fontWeight`,
 * `fontFamily` or typography `.copy(` at a call site.
 */
object LettaTextStyles {
    /** Monospace small text: ids, hashes, paths, timings. `bodySmall` in monospace (the most common override). */
    val mono: TextStyle
        @Composable
        @ReadOnlyComposable
        get() = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)

    /** Monospace body text: a code value read as content. `bodyMedium` in monospace. */
    val monoBody: TextStyle
        @Composable
        @ReadOnlyComposable
        get() = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace)

    /** Monospace caption: a count or id inside a chip or badge. `labelSmall` in monospace. */
    val monoLabel: TextStyle
        @Composable
        @ReadOnlyComposable
        get() = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace)

    /** An emphasised control or column label. `labelLarge` semibold. */
    val labelSemibold: TextStyle
        @Composable
        @ReadOnlyComposable
        get() = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold)

    /** A small emphasised label: a badge, an overline. `labelSmall` semibold. */
    val labelSmallSemibold: TextStyle
        @Composable
        @ReadOnlyComposable
        get() = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold)

    /** A row or card title. `titleSmall` semibold. */
    val titleSemibold: TextStyle
        @Composable
        @ReadOnlyComposable
        get() = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold)

    /** A section or dialog title. `titleMedium` semibold. */
    val titleMediumSemibold: TextStyle
        @Composable
        @ReadOnlyComposable
        get() = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold)

    /** A page's own title at the top of its content. `headlineSmall` bold. */
    val pageTitle: TextStyle
        @Composable
        @ReadOnlyComposable
        get() = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold)

    /** A figure that is the point of its tile (a count, a total). `titleSmall` bold. */
    val figure: TextStyle
        @Composable
        @ReadOnlyComposable
        get() = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold)
}
