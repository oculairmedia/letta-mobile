package com.letta.mobile.ui.text

import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.Density

/** A phone's selection menu is the platform's own toolbar, not drawn by the container. */
@Composable
internal actual fun KeepMenusAtDensity(density: Density, content: @Composable () -> Unit) = content()
