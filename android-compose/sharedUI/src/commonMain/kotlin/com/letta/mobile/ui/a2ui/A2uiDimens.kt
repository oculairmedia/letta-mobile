package com.letta.mobile.ui.a2ui

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Default sizes for agent-authored widgets: what a widget uses when the payload names no size of its
 * own. A size the payload does name (`spacing: 6`, `height: 200`) is data and is drawn as given; these
 * are only the fallbacks and the renderer's own placeholders. Containers (Card, Accordion) are the
 * theme's LettaCard, so their corner and fill are the app's, not the payload's.
 */
internal object A2uiDimens {
    /** A placeholder line standing in for one line of body text. */
    val skeletonLineHeight: Dp = 14.dp

    /** A placeholder standing in for an image that has no source. */
    val skeletonImageHeight: Dp = 120.dp

    /** The least height an image takes before it loads, when the payload gives none. */
    val imageMinHeight: Dp = 96.dp

    /** A key column's width: the narrowest a tool argument's name or a label placeholder draws, the widest a schedule field's. */
    val keyColumnWidth: Dp = 72.dp

    /** The widest a tool argument's name column grows before it ellipsizes. */
    val argumentKeyMaxWidth: Dp = 96.dp

    /** The narrowest an approve or deny button draws, so short labels still read as buttons. */
    val affordanceButtonMinWidth: Dp = 64.dp

    /** How strongly a placeholder line or glyph tints `onSurface`: a quiet shape, not content. */
    const val skeletonFillAlpha: Float = 0.16f

    /** How strongly a tool request's arguments well tints `surfaceVariant` behind the arguments. */
    const val argumentsWellAlpha: Float = 0.52f

    /** The heavier outline a destructive tool request draws, one step above the hairline. */
    val destructiveBorder: Dp = 2.dp
}
