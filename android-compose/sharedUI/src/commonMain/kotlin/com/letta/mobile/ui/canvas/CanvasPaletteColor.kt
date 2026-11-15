package com.letta.mobile.ui.canvas

import androidx.compose.ui.graphics.Color

/*
 * The colours a board's content is drawn in: the marker palette, sticky-note tints, board
 * backgrounds and the pattern and presence defaults.
 *
 * These are document data, not theme roles. A colour picked here is stored in the scene as hex and
 * must look the same on every device and in both themes, so it cannot follow MaterialTheme. They are
 * named here once; the pickers' lists (StrokePalette, NoteColors, BoardBackgrounds) are built from
 * them.
 */

/** Marker colours: the ones whiteboards reach for, plus black and white. The black is also the dark ink [contrastOn] picks. */
internal val InkBlack = Color(0xFF1F1F1F)
internal val InkGray = Color(0xFF6B7280)
internal val InkRed = Color(0xFFE5484D)
internal val InkOrange = Color(0xFFF59E0B)
internal val InkYellow = Color(0xFFEAB308)
internal val InkGreen = Color(0xFF22C55E)
internal val InkBlue = Color(0xFF3B82F6)
internal val InkViolet = Color(0xFF8B5CF6)
internal val InkWhite = Color(0xFFFFFFFF)

/** Sticky-note tints: pale enough for dark text, distinct enough to sort by. */
internal val NoteTintYellow = Color(0xFFFDE68A)
internal val NoteTintPink = Color(0xFFFBCFE8)
internal val NoteTintGreen = Color(0xFFBBF7D0)
internal val NoteTintBlue = Color(0xFFBFDBFE)
internal val NoteTintViolet = Color(0xFFDDD6FE)
internal val NoteTintOrange = Color(0xFFFED7AA)
internal val NoteTintGray = Color(0xFFE5E7EB)

/** Board backgrounds: paper whites, warm and cool tints, and the dark boards. */
internal val BoardWhite = Color(0xFFFFFFFF)
internal val BoardPaper = Color(0xFFF7F3EA)
internal val BoardSky = Color(0xFFEFF6FF)
internal val BoardMint = Color(0xFFF0FDF4)
internal val BoardCharcoal = Color(0xFF1E1E22)
internal val BoardMidnight = Color(0xFF0B0F17)

/** A background pattern's tint when the board stored none: a mid grey that reads on light and dark boards. */
internal val PatternDefaultTint = Color(0xFF9CA3AF)

/** A peer's cursor colour when the colour it sent cannot be read. */
internal val PresenceFallbackColor = Color(0xFF2196F3)
