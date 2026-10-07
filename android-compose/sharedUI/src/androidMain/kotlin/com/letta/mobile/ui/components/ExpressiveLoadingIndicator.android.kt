package com.letta.mobile.ui.components

import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LoadingIndicator
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal actual fun ExpressiveLoadingIndicator(color: Color, still: Boolean, modifier: Modifier) {
    if (still) {
        // Determinate at rest: the first shape, no morph.
        LoadingIndicator(progress = { 0f }, modifier = modifier, color = color)
    } else {
        LoadingIndicator(modifier = modifier, color = color)
    }
}
