package io.ak1.drawbox.domain.model

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import io.ak1.drawbox.domain.usecase.SvgExporter
import io.ak1.drawbox.domain.usecase.UseCase
import io.ak1.drawbox.presentation.reducer.Reducer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Text inside a shape: part of the shape, saved with it, edited and styled like text. */
class ShapeTextTest {

    private val reducer = Reducer(UseCase())

    private fun shape(type: ShapeType = ShapeType.RECTANGLE, text: String = "") = Element.Shape(
        id = "s",
        shapeType = type,
        points = listOf(Offset(0f, 0f), Offset(200f, 100f)),
        strokeColor = Color.Black,
        strokeWidth = 2f,
        text = text,
    )

    @Test
    fun onlyClosedShapesHoldText() {
        assertTrue(shape(ShapeType.RECTANGLE).canHoldText)
        assertTrue(shape(ShapeType.CIRCLE).canHoldText)
        assertTrue(shape(ShapeType.TRIANGLE).canHoldText)
        assertFalse(shape(ShapeType.LINE).canHoldText)
        assertFalse(shape(ShapeType.ARROW).canHoldText)
    }

    @Test
    fun theTextBoxSitsInsideTheShape() {
        listOf(ShapeType.RECTANGLE, ShapeType.CIRCLE, ShapeType.TRIANGLE).forEach { type ->
            val s = shape(type)
            val b = s.bounds()
            val box = s.textBox()
            assertTrue(box.left >= b.left && box.right <= b.right && box.top >= b.top && box.bottom <= b.bottom, "$type: $box outside $b")
            assertTrue(box.width > 0f && box.height > 0f, "$type: empty box")
        }
        // A rectangle's text is centred on the rectangle.
        assertEquals(shape().bounds().center, shape().textBox().center)
    }

    @Test
    fun shapeTextRoundTripsThroughJson() {
        val s = shape(text = "Plan the week").copy(
            textColor = Color(0xFF3B82F6),
            fontSize = 32f,
            fontFamilyKey = BuiltinFontFamilyKeys.SERIF,
            textAlignment = TextAlignment.LEFT,
        )
        val back = s.toDto().toElement() as Element.Shape
        assertEquals("Plan the week", back.text)
        assertEquals(Color(0xFF3B82F6), back.textColor)
        assertEquals(32f, back.fontSize)
        assertEquals(BuiltinFontFamilyKeys.SERIF, back.fontFamilyKey)
        assertEquals(TextAlignment.LEFT, back.textAlignment)

        val viaFile = DrawingSerializer.deserialize(DrawingSerializer.serialize(PayLoad(Color.White, listOf(s))))
        assertEquals(s.text, (viaFile.elements.single() as Element.Shape).text)
        assertEquals(s.textColor, (viaFile.elements.single() as Element.Shape).textColor)
    }

    @Test
    fun aShapeWithoutTextWritesNoTextFieldsAndOldFilesLoad() {
        val dto = shape().toDto()
        assertNull(dto.text)
        assertNull(dto.fontSize)
        assertNull(dto.textColor)
        val back = dto.toElement() as Element.Shape
        assertEquals("", back.text)
        assertEquals(DEFAULT_SHAPE_FONT_SIZE, back.fontSize)
        assertEquals(TextAlignment.CENTER, back.textAlignment)
    }

    @Test
    fun updateTextAndTextStylingReachShapes() {
        var state = State(elements = listOf(shape()), mode = Mode.SELECT, selectedIds = setOf("s"))
        state = reducer.reduce(state, Intent.UpdateText("s", "hello"))
        state = reducer.reduce(state, Intent.SetSelectedTextColor(Color.Red))
        state = reducer.reduce(state, Intent.SetSelectedFontSize(40f))
        state = reducer.reduce(state, Intent.SetSelectedTextAlignment(TextAlignment.RIGHT))
        state = reducer.reduce(state, Intent.SetSelectedFontFamily(BuiltinFontFamilyKeys.MONO))
        val s = state.elements.single() as Element.Shape
        assertEquals("hello", s.text)
        assertEquals(Color.Red, s.textColor)
        assertEquals(Color.Black, s.strokeColor, "the text colour is not the outline")
        assertEquals(40f, s.fontSize)
        assertEquals(TextAlignment.RIGHT, s.textAlignment)
        assertEquals(BuiltinFontFamilyKeys.MONO, s.fontFamilyKey)
        assertEquals(5, state.history.size, "each is an undo step")
    }

    @Test
    fun aLineTakesNoText() {
        val line = shape(ShapeType.LINE)
        val out = reducer.reduce(State(elements = listOf(line)), Intent.UpdateText("s", "nope"))
        assertEquals("", (out.elements.single() as Element.Shape).text)
    }

    @Test
    fun askingToEditTextOnAShapeSelectsIt() {
        val state = State(elements = listOf(shape()), mode = Mode.SELECT, selectInsideHollowShapes = true)
        val out = reducer.reduce(state, Intent.RequestTextEditAt(Offset(100f, 50f), 4f))
        assertEquals(setOf("s"), out.selectedIds)
    }

    @Test
    fun svgExportCarriesShapeText() {
        val svg = SvgExporter.exportToSvg(listOf(shape(text = "Ship it")))
        assertTrue(svg.contains("Ship it"), "the shape's text should be in the SVG")
        assertTrue(svg.contains("text-anchor=\"middle\""), "centred by default")
    }
}
