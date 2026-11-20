@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.letta.mobile.ui.components.row

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runComposeUiTest
import com.letta.mobile.ui.theme.LettaDimens
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

/** A text action on a hanging row starts on the content edge and still has a full touch target. */
class LettaHangingRowActionTest {
    @Test
    fun aShortLabelStartsOnTheContentEdgeInsideAFullTouchTarget() = runComposeUiTest {
        setContent {
            MaterialTheme {
                LettaHangingRow(leading = { Text("o") }) {
                    Text("Title of the row")
                    LettaHangingRowAction(label = "Go", onClick = {}, tag = ACTION)
                }
            }
        }
        val title = onNodeWithText("Title of the row", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val label = onNodeWithText("Go", useUnmergedTree = true).firstGlyphLeftAndWidth()
        val target = onNodeWithTag(ACTION).fetchSemanticsNode().boundsInRoot
        val minTarget = with(density) { LettaDimens.Control.minTouchTarget.toPx() }

        assertTrue(abs(title.left - label.first) <= 1f, "the label's first letter is at x=${label.first}, off the content edge x=${title.left}")
        assertTrue(abs(title.left - target.left) <= 1f, "the touch target starts at x=${target.left}, off the content edge x=${title.left}")
        assertTrue(label.second < minTarget, "the label (${label.second}px) must be narrower than the target for this to test anything")
        assertTrue(target.width >= minTarget - 1f, "touch target ${target.width}px wide, under $minTarget")
        assertTrue(target.height >= minTarget - 1f, "touch target ${target.height}px tall, under $minTarget")
    }

    @Test
    fun aLongLabelIsNotInsetEither() = runComposeUiTest {
        setContent {
            MaterialTheme {
                LettaHangingRow(leading = { Text("o") }) {
                    Text("Title of the row")
                    LettaHangingRowAction(label = "View conversation", onClick = {}, tag = ACTION)
                }
            }
        }
        val title = onNodeWithText("Title of the row", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val label = onNodeWithText("View conversation", useUnmergedTree = true).firstGlyphLeftAndWidth()
        assertTrue(abs(title.left - label.first) <= 1f, "the label's first letter is at x=${label.first}, off the content edge x=${title.left}")
    }

    /** Where the text's own first letter is drawn (x in the root) and how wide its glyphs are: not the node's padded bounds. */
    private fun SemanticsNodeInteraction.firstGlyphLeftAndWidth(): Pair<Float, Float> {
        val node = fetchSemanticsNode()
        val layouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
        node.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(layouts)
        val layout = layouts.single()
        val box = layout.getBoundingBox(0)
        return node.positionInRoot.x + box.left to layout.getLineRight(0) - layout.getLineLeft(0)
    }

    private companion object {
        const val ACTION = "action"
    }
}
