package com.letta.mobile.ui.chat.surface.composer

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

@Composable
internal actual fun Modifier.composerImageIngress(attacher: ComposerImageAttacher): Modifier =
    attacher.let { this }
