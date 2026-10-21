package com.letta.mobile.data.canvas

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CanvasSvgRendererTest {
    private fun scene(vararg elements: JsonObject): String = JsonObject(mapOf(
        "bgColor" to JsonPrimitive("#eceff1ff"), "elements" to JsonArray(elements.toList()),
    )).toString()

    @Test
    fun rendersEveryDrawingKindWithBackgroundAndPaddedBounds() {
        val image = JsonObject(CanvasSceneSchema.image.example + mapOf(
            "imageData" to JsonPrimitive("iVBORw0KGgo="),
            "imageMediaType" to JsonPrimitive("image/png"),
        ))
        val svg = CanvasSvgRenderer.render(scene(CanvasSceneSchema.shape.example,
            CanvasSceneSchema.text.example, CanvasSceneSchema.path.example, image))
        assertTrue(svg.contains("viewBox=\""))
        assertEquals(2, Regex("<rect ").findAll(svg).count())
        assertEquals(2, Regex("<text ").findAll(svg).count())
        assertEquals(2, Regex("<line ").findAll(svg).count())
        assertEquals(1, Regex("<image ").findAll(svg).count())
        assertTrue(svg.contains("fill=\"#eceff1ff\""))
    }

    @Test
    fun buildCycleSampleShapesAreRealGeometryNotEmptyPlaceholders() {
        // The Idea and Plan shapes from the bundled CanvasSamples.buildCycleJson.
        val sample = """{"bgColor":"#eceff1ff","elements":[
            {"id":"idea-tri","type":"Shape","zIndex":1,"points":["210.0,542.0","490.0,762.0"],"strokeColor":"#f39c12ff","strokeWidth":5.0,"shapeType":"TRIANGLE","fillColor":"#fce9c4ff","cornerRadius":12.0},
            {"id":"plan-rect","type":"Shape","zIndex":1,"points":["568.0,560.0","868.0,740.0"],"strokeColor":"#16a085ff","strokeWidth":5.0,"shapeType":"RECTANGLE","fillColor":"#d1f2ebff","cornerRadius":24.0}
        ]}"""
        val svg = CanvasSvgRenderer.render(sample)
        assertEquals(1, Regex("<polygon ").findAll(svg).count())
        assertEquals(2, Regex("<rect ").findAll(svg).count())
        assertTrue(svg.contains("width=\"300.0\""))
    }

    @Test
    fun escapesTextAndStripsSynchronizationMetadata() {
        val text = JsonObject(CanvasSceneSchema.text.example + mapOf(
            "text" to JsonPrimitive("<tag> & \"quoted\" 'words'"),
            "_actorId" to JsonPrimitive("secret-actor"),
            "_lamport" to JsonPrimitive(42),
        ))
        val svg = CanvasSvgRenderer.render(scene(text))
        assertTrue(svg.contains("&lt;tag&gt; &amp; &quot;quoted&quot; &apos;words&apos;"))
        assertFalse(svg.contains("secret-actor"))
        assertFalse(svg.contains("_lamport"))
    }

    @Test
    fun rejectsCorruptOrUnsupportedScenesInsteadOfExportingBlankSvg() {
        for (input in listOf("not JSON", "{}", """{"elements":[{"type":"alien"}]}""",
            """{"elements":[],"_documents":[{"id":"note"}]}""")) {
            assertFailsWith<IllegalArgumentException> { CanvasSvgRenderer.render(input) }
        }
    }
}
