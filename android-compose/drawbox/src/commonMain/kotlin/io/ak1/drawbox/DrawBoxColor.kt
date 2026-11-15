package io.ak1.drawbox

import androidx.compose.ui.graphics.Color

/*
 * DrawBox's own default colours, named once. They are drawn on the board itself, under the
 * viewport transform, so they are fixed values rather than a host theme's roles; a host recolours
 * the selection through SelectionChromeStyle.accent.
 */

/** The selection chrome's default accent: the box, handles and marquee. */
internal val SELECTION_ACCENT = Color(0xFF2196F3)

/** How strongly the marquee fills with the accent, over the board beneath it. */
internal const val MARQUEE_FILL_ALPHA = 0.13f

/** The background grid over a dark board (about 25% white) and over a light one (about 20% black). */
internal val GridLightOnDark = Color(0x40FFFFFF)
internal val GridDarkOnLight = Color(0x33000000)

/** The outline of a text block drawn before its text can be measured. */
internal val TEXT_PREVIEW_OUTLINE = Color(0x33888888)

/** The fill and outline of an image whose bytes cannot be decoded, so it never renders as a hole. */
internal val IMAGE_PLACEHOLDER_FILL = Color(0xFFE0E0E0)
internal val IMAGE_PLACEHOLDER_OUTLINE = Color(0xFFB0B0B0)
