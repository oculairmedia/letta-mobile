package com.letta.mobile.ui.canvas

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import io.ak1.drawbox.WorldDensity

/**
 * Everything ON the board is sized in canvas (world) units, so it keeps its proportions to the
 * board at any zoom and is the same on a phone and a desktop (letta-mobile-bglj6.17).
 *
 * One world unit is one px before the board's zoom (`screen = world * scale + offset`). Under
 * [CanvasWorldDensity] one dp and one sp are each one px and the system font scale is 1, so a
 * note's 16-unit type is 16 world units on a density-2.75 phone exactly as on a density-1 desktop,
 * whatever the person's font-size setting, and the estimator in sharedLogic
 * (`CanvasComposeReserve`, which books world units) and the renderer agree. The zoom is applied on
 * top, as a graphics-layer scale.
 *
 * Board chrome (toolbars, menus, the selection outline and handles, presence) is NOT board content:
 * it stays in the display's dp and sp and honours accessibility font scaling.
 */
internal val CanvasWorldDensity: Density = WorldDensity

/** [content] laid out in world units: note text, a note's handle bar, padding, corners and outline. */
@Composable
internal fun InWorldUnits(content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalDensity provides CanvasWorldDensity, content = content)
}

/**
 * Called with a note's id each time its card composes. Tests count them to hold a pan or zoom to
 * no recomposition at all; nothing else provides it.
 */
internal val LocalNoteCompositionProbe = staticCompositionLocalOf<((String) -> Unit)?> { null }
