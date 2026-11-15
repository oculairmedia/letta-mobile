package com.letta.mobile.ui.components.foundation

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import com.letta.mobile.ui.theme.tokens.LettaComponentDimens

/**
 * A swatch's colour and state: [color], whether it is the [selected] choice, its [name] for a screen
 * reader ("Color red"; null when words beside it already say it) and its [size]
 * ([LettaComponentDimens.swatch] by default).
 */
@Immutable
data class LettaSwatch(
    val color: Color,
    val selected: Boolean = false,
    val name: String? = null,
    val size: Dp = LettaComponentDimens.swatch,
)
