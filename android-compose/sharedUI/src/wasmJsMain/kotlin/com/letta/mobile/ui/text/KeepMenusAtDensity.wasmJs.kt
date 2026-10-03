package com.letta.mobile.ui.text

import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.Density

/** Web has no container-drawn context menu to rescale (the browser draws its own), as on Android. */
@Composable
internal actual fun KeepMenusAtDensity(density: Density, content: @Composable () -> Unit) = content()
