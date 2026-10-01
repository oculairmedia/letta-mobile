package io.ak1.drawbox

import androidx.compose.ui.unit.Density

/**
 * The density board content is laid out in: one world unit per px, per dp and per sp, and no system
 * font scale (letta-mobile-bglj6.17).
 *
 * Elements are stored in world units and drawn through the [io.ak1.drawbox.domain.model.Viewport]
 * (`screen = world * scale + offset`), so one world unit is one px at zoom 1. A text element's
 * `fontSize` is in those same units ("em size in world pixels"), which only holds when its text is
 * laid out under this density: under the display's own density a 24-unit font was 24 sp, so on a
 * phone at density 2.75 it took 66 world units and the words outgrew their box. Under [WorldDensity]
 * the same element measures the same on every display and at every font-size setting, which is what
 * keeps a board identical on a phone and a desktop. Chrome around the board (toolbars, menus,
 * selection handles) stays in the host's dp and sp.
 */
val WorldDensity: Density = Density(density = 1f, fontScale = 1f)
