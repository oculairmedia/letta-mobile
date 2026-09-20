package com.letta.mobile.ui.canvas

import io.ak1.drawbox.domain.model.Element
import io.ak1.drawbox.domain.model.Intent
import io.ak1.drawbox.domain.model.Mode
import io.ak1.drawbox.domain.model.TextAlignment
import io.ak1.drawbox.domain.model.ShapeType
import io.ak1.drawbox.domain.model.State
import io.ak1.drawbox.domain.model.StrokeStyle
import io.ak1.drawbox.presentation.viewmodel.DrawBoxController
import io.ak1.drawbox.ui.controls.ControlsBarIntent
import io.ak1.drawbox.ui.controls.ControlsBarState

/**
 * Bridges DrawBoxController state and intents with DrawBox UI ControlsBar, and with the board's
 * own property control for what DrawBox's bar state does not carry (width, opacity, dash, radius).
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

    /**
     * The properties the master control shows for the selection, read from its first shape or
     * stroke, or for the current tool when nothing is selected. Corner radius only applies to
     * rectangles, so it is offered when one is selected or about to be drawn.
     */
    fun buildProperties(state: State): CanvasProperties {
        val selected = state.elements.filter { it.id in state.selectedIds }
        val shape = selected.filterIsInstance<Element.Shape>().firstOrNull()
        val path = selected.filterIsInstance<Element.Path>().firstOrNull()
        val text = selected.filterIsInstance<Element.Text>().firstOrNull()
        val textProps = textPropertyRows(selected, text, state)
        return CanvasProperties(
            selectionCount = selected.size,
            strokeWidth = shape?.strokeWidth ?: path?.strokeWidth ?: state.strokeWidth,
            opacity = path?.alpha ?: text?.opacity ?: state.opacity,
            strokeStyle = shape?.strokeStyle ?: state.currentItemStrokeStyle,
            cornerRadius = shape?.cornerRadius ?: state.currentItemCornerRadius,
            showCornerRadius = hasRectangle(selected) || (selected.isEmpty() && state.mode == Mode.RECTANGLE),
            fontSize = textProps.fontSize,
            fontFamily = textProps.fontFamily,
            textAlignment = textProps.textAlignment,
            showFontSize = textProps.showFontSize,
        )
    }

    private data class CanvasTextProperties(
        val fontSize: Float,
        val fontFamily: String,
        val textAlignment: TextAlignment,
        val showFontSize: Boolean,
    )

    private fun textPropertyRows(selected: List<Element>, text: Element.Text?, state: State): CanvasTextProperties {
        val show = text != null || (selected.isEmpty() && state.mode == Mode.TEXT)
        return CanvasTextProperties(
            fontSize = text?.fontSize ?: state.currentItemFontSize,
            fontFamily = text?.fontFamilyKey ?: state.currentItemFontFamilyKey,
            textAlignment = text?.alignment ?: state.currentItemTextAlignment,
            showFontSize = show,
        )
    }

    private fun hasRectangle(selected: List<Element>): Boolean =
        selected.any { it is Element.Shape && it.shapeType == ShapeType.RECTANGLE }

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

    /**
     * Applies a property change to every selected element, or to the tool defaults when nothing
     * is selected. DrawBox keeps opacity per stroke and per text but not per shape, and has no
     * selection-wide opacity intent, so a selection's strokes and texts are rewritten one by one
     * and the tool default is set alongside, which is what the next stroke inherits.
     */
    fun dispatchProperty(
        controller: DrawBoxController,
        intent: CanvasPropertyIntent,
        state: State,
    ) {
        val hasSelection = state.selectedIds.isNotEmpty()
        when (intent) {
            is CanvasPropertyIntent.SetFontSize,
            is CanvasPropertyIntent.SetFontFamily,
            is CanvasPropertyIntent.SetTextAlignment -> dispatchTextProperty(controller, intent, hasSelection)
            is CanvasPropertyIntent.SetOpacity -> applyOpacity(controller, intent.opacity, state, hasSelection)
            is CanvasPropertyIntent.SetStrokeWidth,
            is CanvasPropertyIntent.SetStrokeStyle,
            is CanvasPropertyIntent.SetCornerRadius -> dispatchStrokeProperty(controller, intent, hasSelection)
        }
    }

    private fun dispatchTextProperty(
        controller: DrawBoxController,
        intent: CanvasPropertyIntent,
        hasSelection: Boolean,
    ) {
        when (intent) {
            is CanvasPropertyIntent.SetFontSize -> {
                if (hasSelection) controller.setSelectionFontSize(intent.size)
                else controller.setFontSize(intent.size)
            }
            is CanvasPropertyIntent.SetFontFamily -> {
                if (hasSelection) controller.setSelectionFontFamily(intent.key)
                else controller.setFontFamily(intent.key)
            }
            is CanvasPropertyIntent.SetTextAlignment -> {
                if (hasSelection) controller.setSelectionTextAlignment(intent.alignment)
                else controller.setTextAlignment(intent.alignment)
            }
            else -> Unit
        }
    }

    private fun dispatchStrokeProperty(
        controller: DrawBoxController,
        intent: CanvasPropertyIntent,
        hasSelection: Boolean,
    ) {
        when (intent) {
            is CanvasPropertyIntent.SetStrokeWidth -> {
                if (hasSelection) controller.setSelectionStrokeWidth(intent.width)
                else controller.setStrokeWidth(intent.width)
            }
            is CanvasPropertyIntent.SetStrokeStyle -> {
                if (hasSelection) controller.setSelectionStrokeStyle(intent.style)
                else controller.setStrokeStyle(intent.style)
            }
            is CanvasPropertyIntent.SetCornerRadius -> {
                if (hasSelection) controller.setSelectionCornerRadius(intent.radius)
                else controller.setCornerRadius(intent.radius)
            }
            else -> Unit
        }
    }

    private fun applyOpacity(
        controller: DrawBoxController,
        opacity: Float,
        state: State,
        hasSelection: Boolean,
    ) {
        controller.setOpacity(opacity)
        if (!hasSelection) return
        state.elements
            .filter { it.id in state.selectedIds }
            .forEach { element ->
                when (element) {
                    is Element.Path -> controller.onIntent(Intent.UpdateElement(element.copy(alpha = opacity)))
                    is Element.Text -> controller.onIntent(Intent.UpdateElement(element.copy(opacity = opacity)))
                    else -> Unit
                }
            }
    }
}

/** What the master control shows for a drawing selection or the current tool. */
data class CanvasProperties(
    val selectionCount: Int,
    val strokeWidth: Float,
    val opacity: Float,
    val strokeStyle: StrokeStyle,
    val cornerRadius: Float,
    val showCornerRadius: Boolean,
    /** The size the selected text is set in, or the size the next text will be placed at. */
    val fontSize: Float,
    /** The face that text is set in: `sans`, `serif` or `mono`. */
    val fontFamily: String,
    /** The edge that text is set against. */
    val textAlignment: TextAlignment,
    /** Whether the size of the type is worth offering: text is selected, or about to be placed. */
    val showFontSize: Boolean,
)

/**
 * The property changes DrawBox's own bar intents do not cover. Kept apart from
 * [ControlsBarIntent] because that interface is sealed in its module.
 */
sealed interface CanvasPropertyIntent {
    data class SetStrokeWidth(val width: Float) : CanvasPropertyIntent
    data class SetOpacity(val opacity: Float) : CanvasPropertyIntent
    data class SetStrokeStyle(val style: StrokeStyle) : CanvasPropertyIntent
    data class SetCornerRadius(val radius: Float) : CanvasPropertyIntent

    /** The size of the type: of the selected text, or of the next text placed. */
    data class SetFontSize(val size: Float) : CanvasPropertyIntent

    /** The face the type is set in: `sans`, `serif` or `mono`. */
    data class SetFontFamily(val key: String) : CanvasPropertyIntent

    /** Which edge the lines are set against. */
    data class SetTextAlignment(val alignment: TextAlignment) : CanvasPropertyIntent
}
