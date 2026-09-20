package com.letta.mobile.ui.canvas

import androidx.compose.ui.geometry.Offset
import io.ak1.drawbox.domain.model.Element
import io.ak1.drawbox.domain.model.bounds

/**
 * The board's text elements, looked up the few ways the workspace needs them.
 *
 * Text on the board is one of DrawBox's own elements: it is placed, measured, wrapped and hit
 * tested by DrawBox, selected and resized by the same chrome as every other element, undone by the
 * same undo and exported with the drawing. The one thing DrawBox leaves to the host is the caret -
 * it says WHICH text is to be edited and the host renders the editor - so these are the questions
 * that answer takes.
 */
internal object CanvasTextElements {

    /** Every text element's id, for spotting the one that has just been added. */
    fun ids(elements: List<Element>): Set<String> =
        elements.filterIsInstance<Element.Text>().map { it.id }.toSet()

    /** The text element with [id], or null when it has since been deleted or never existed. */
    fun byId(elements: List<Element>, id: String?): Element.Text? {
        if (id == null) return null
        return elements.filterIsInstance<Element.Text>().firstOrNull { it.id == id }
    }

    /**
     * The topmost text element under [point], within [tolerance].
     *
     * Topmost, because text can overlap text and the one in front is the one you meant. Measured
     * against the element's own bounds, which is what DrawBox draws it in.
     */
    fun at(elements: List<Element>, point: Offset, tolerance: Float): Element.Text? =
        elements.filterIsInstance<Element.Text>()
            .sortedBy { it.zIndex }
            .lastOrNull { text ->
                text.bounds().inflate(tolerance).contains(point)
            }
}
