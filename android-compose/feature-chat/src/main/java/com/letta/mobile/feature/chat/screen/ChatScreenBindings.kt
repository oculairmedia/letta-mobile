package com.letta.mobile.feature.chat.screen

import androidx.compose.foundation.background
import androidx.compose.ui.Modifier
import com.letta.mobile.ui.theme.ChatBackground

/** The chat background's fill: none, a solid colour or a gradient. */
internal fun ChatBackground.fillModifier(): Modifier = when (this) {
    is ChatBackground.Default -> Modifier
    is ChatBackground.SolidColor -> Modifier.background(color)
    is ChatBackground.Gradient -> Modifier.background(toBrush())
}
