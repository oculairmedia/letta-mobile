package com.letta.mobile.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import com.letta.mobile.ui.icons.BakenMark

/**
 * The two-colour Baken mark (its stone and the vermilion pebble, from the design tokens) for the theme in scope: the
 * pale stone on a dark background, the petrol stone on a light one. Drawn untinted wherever the brand stands for
 * itself: Home's hero and the rail's Home button.
 */
@Composable
internal fun shellBrandMark(): ImageVector {
    val dark = MaterialTheme.colorScheme.background.luminance() < LIGHT_BACKGROUND_LUMINANCE
    return remember(dark) {
        if (dark) {
            BakenMark.brand(Color(LettaColorTokens.DARK_MARK_STONE), Color(LettaColorTokens.DARK_PRIMARY))
        } else {
            BakenMark.brand(Color(LettaColorTokens.LIGHT_MARK_STONE), Color(LettaColorTokens.LIGHT_PRIMARY))
        }
    }
}

/** A background brighter than this is a light theme: the mark takes its petrol stone, else its pale one. */
private const val LIGHT_BACKGROUND_LUMINANCE = 0.5f
