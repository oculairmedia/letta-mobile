package com.letta.mobile.ui.canvas

import io.ak1.drawbox.domain.model.Element
import io.ak1.drawbox.domain.model.Mode
import io.ak1.drawbox.domain.model.State
import io.ak1.drawbox.presentation.viewmodel.DrawBoxController
import io.ak1.drawbox.ui.controls.ControlsBarIntent
import io.ak1.drawbox.ui.controls.ControlsBarState

/**
 * Bridges DrawBoxController state and intents with DrawBox UI ControlsBar.
 */
object CanvasControlsBridge {
    fun buildControlsBarState(
        state: State,
        canUndo: Boolean,
        canRedo: Boolean,
    ): ControlsBarState {
        val selectedDrawables = state.elements.filter { it.id in state.selectedIds }
        val selectedShapes = selectedDrawables.filterIsInstance<Element.Shape>()
        val isClosedShapeMode = state.mode == Mode.RECTANGLE ||
            state.mode == Mode.CIRCLE ||
            state.mode == Mode.TRIANGLE
        val showFill = selectedShapes.isNotEmpty() || isClosedShapeMode
        val currentFillColor = selectedShapes.firstOrNull()?.fillColor ?: state.currentItemFillColor
        val currentStrokeEnabled = selectedShapes.firstOrNull()?.strokeEnabled ?: state.currentItemStrokeEnabled

        val currentShapeColor = when (val first = selectedDrawables.firstOrNull()) {
            is Element.Shape -> first.strokeColor
            is Element.Path -> first.strokeColor
            is Element.Text -> first.color
            else -> state.strokeColor
        }

        return ControlsBarState(
            currentMode = state.mode,
            canUndo = canUndo,
            canRedo = canRedo,
            strokeColor = currentShapeColor,
            showFillTarget = showFill,
            strokeEnabled = currentStrokeEnabled,
            fillColor = currentFillColor,
        )
    }

    fun dispatchIntent(
        controller: DrawBoxController,
        intent: ControlsBarIntent,
        hasSelection: Boolean,
    ) {
        when (intent) {
            ControlsBarIntent.Undo -> controller.undo()
            ControlsBarIntent.Redo -> controller.redo()
            is ControlsBarIntent.SelectMode -> controller.setMode(intent.mode)
            is ControlsBarIntent.SetStrokeColor -> {
                if (hasSelection) controller.setSelectionColor(intent.color)
                else controller.setColor(intent.color)
            }
            is ControlsBarIntent.SetStrokeEnabled -> {
                if (hasSelection) controller.setSelectionStrokeEnabled(intent.enabled)
                else controller.setStrokeEnabled(intent.enabled)
            }
            is ControlsBarIntent.SetFillColor -> {
                if (hasSelection) controller.setSelectionFillColor(intent.color)
                else controller.setFillColor(intent.color)
            }
        }
    }
}
