package io.ak1.drawbox.text

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import io.ak1.drawbox.domain.model.Element
import io.ak1.drawbox.domain.model.TextAlignment
import io.ak1.drawbox.domain.model.Viewport
import io.ak1.drawbox.domain.model.bounds
import io.ak1.drawbox.domain.model.resolvedTextColor
import io.ak1.drawbox.domain.model.textBox
import kotlin.math.roundToInt

/**
 * Inline editor for a shape's text: the counterpart of [InlineTextEditor] for an
 * [Element.Shape] that [io.ak1.drawbox.domain.model.canHoldText]. It sits over the shape's
 * [textBox], set as the renderer sets the text - same family, size, colour and alignment,
 * wrapped to the box's width and centred in it top to bottom - and turns with the shape, so the
 * caret is the only sign of editing. The host hides the shape's rendered text meanwhile through
 * `DrawBox(hiddenTextElementIds = setOf(shape.id))`, which leaves the shape itself on screen.
 *
 * [draft] / [onDraftChange] are hoisted as for [InlineTextEditor]; commit with
 * `Intent.UpdateText(shape.id, draft)` (or `DrawBoxController.updateText`).
 */
@Composable
fun InlineShapeTextEditor(
    shape: Element.Shape,
    viewport: Viewport,
    draft: String,
    onDraftChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val box = shape.textBox()
    val topLeft = viewport.worldToScreen(box.topLeft)
    val density = LocalDensity.current
    val widthDp = with(density) { (box.width * viewport.scale).toDp() }
    val heightDp = with(density) { (box.height * viewport.scale).toDp() }
    // The shape turns about its own centre; the box is not always centred on it (triangles).
    val centre = shape.bounds().center
    val origin = TransformOrigin(
        pivotFractionX = if (box.width > 0f) (centre.x - box.left) / box.width else 0.5f,
        pivotFractionY = if (box.height > 0f) (centre.y - box.top) / box.height else 0.5f,
    )

    val focusRequester = remember(shape.id) { FocusRequester() }
    LaunchedEffect(shape.id) {
        runCatching { focusRequester.requestFocus() }
    }

    Box(
        modifier = modifier
            .zIndex(10f)
            .offset { IntOffset(topLeft.x.roundToInt(), topLeft.y.roundToInt()) }
            .size(widthDp, heightDp)
            .graphicsLayer {
                rotationZ = shape.rotation
                transformOrigin = origin
            },
        contentAlignment = Alignment.Center,
    ) {
        BasicTextField(
            value = draft,
            onValueChange = onDraftChange,
            textStyle = TextStyle(
                // Direct `.sp` for the same reason as InlineTextEditor: renderer parity.
                fontSize = (shape.fontSize * viewport.scale).sp,
                fontFamily = FontRegistry.resolve(shape.fontFamilyKey),
                textAlign = when (shape.textAlignment) {
                    TextAlignment.LEFT -> TextAlign.Left
                    TextAlignment.CENTER -> TextAlign.Center
                    TextAlignment.RIGHT -> TextAlign.Right
                },
                color = shape.resolvedTextColor,
            ),
            cursorBrush = SolidColor(shape.resolvedTextColor),
            modifier = Modifier.fillMaxWidth().focusRequester(focusRequester),
        )
    }
}
