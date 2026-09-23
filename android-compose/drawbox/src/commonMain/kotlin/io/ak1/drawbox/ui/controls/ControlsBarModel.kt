/*
 * Copyright 2021 Akshay Sharma
 * Licensed under the Apache License, Version 2.0 (see drawbox/LICENSE).
 *
 * Vendored from akshay2211/DrawBox v2.1.0, drawbox-ui/src/commonMain/kotlin/io/ak1/drawbox/ui/
 * controls/ControlsBar.kt: the controls-bar state and intent types only, unchanged. The rest of
 * drawbox-ui (the ControlsBar composable, its resources and icons) is not used by Letta.
 */
package io.ak1.drawbox.ui.controls

import androidx.compose.ui.graphics.Color
import io.ak1.drawbox.domain.model.Mode

/**
 * Snapshot of everything the controls bar needs to render.
 *
 * @param recentColors up to a handful of recently-picked colors surfaced as the
 *   color slot's submenu.
 */
data class ControlsBarState(
    val currentMode: Mode,
    val canUndo: Boolean = false,
    val canRedo: Boolean = false,
    val strokeColor: Color = Color.Black,
    val recentColors: List<Color> = emptyList(),
    /**
     * When true, the color swatch renders as a 45°-split disc (stroke ring on
     * the top-left, fill disc on the bottom-right) and taps open the multi-
     * target picker. Enable only for elements/modes that support both stroke
     * AND fill — closed shapes (rect, circle, triangle). Everything else keeps
     * the plain single-target swatch.
     */
    val showFillTarget: Boolean = false,
    val strokeEnabled: Boolean = true,
    val fillColor: Color? = null,
)

/** Every user gesture a controls bar can emit. */
sealed interface ControlsBarIntent {
    data object Undo : ControlsBarIntent
    data object Redo : ControlsBarIntent
    data class SelectMode(val mode: Mode) : ControlsBarIntent
    data class SetStrokeColor(val color: Color) : ControlsBarIntent
    data class SetStrokeEnabled(val enabled: Boolean) : ControlsBarIntent
    data class SetFillColor(val color: Color?) : ControlsBarIntent
}

typealias ControlsBarDispatch = (ControlsBarIntent) -> Unit
