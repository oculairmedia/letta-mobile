package com.letta.mobile.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color

/**
 * letta-mobile-bglj6.1.19: the Material 3 expressive loading indicator (its shape morph) that the
 * legacy Android chat shows while reasoning streams. Android draws the real one; Compose
 * Multiplatform's material3 keeps it internal, so desktop and web draw a small circular spinner.
 *
 * [still] (reduced motion) draws it at rest, never looping.
 */
@Composable
internal expect fun ExpressiveLoadingIndicator(color: Color, still: Boolean, modifier: Modifier = Modifier)
