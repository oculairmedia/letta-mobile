package com.letta.mobile.ui.canvas

import com.letta.mobile.data.canvas.CanvasSceneCheck
import com.letta.mobile.data.canvas.CanvasSceneSchema
import com.letta.mobile.data.canvas.CanvasSceneValidator
import io.ak1.drawbox.domain.model.DrawingSerializer
import io.ak1.drawbox.domain.model.Element
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * letta-mobile-qygvv.21: the scene format the agent is told about and held to is the one DrawBox
 * actually decodes. Every example, once the host has validated it, loads as the element it names.
 */
class CanvasSceneSchemaDrawBoxTest {
    private fun validated(vararg elements: JsonObject): String {
        val scene = JsonObject(mapOf("elements" to JsonArray(elements.toList())))
        return assertIs<CanvasSceneCheck.Valid>(CanvasSceneValidator.scene(scene.toString())).json
    }

    @Test
    fun everyExampleDecodesAsItsOwnType() {
        val image = JsonObject(CanvasSceneSchema.image.example + mapOf("imageRef" to JsonPrimitive("asset-1")))
        val examples = listOf(CanvasSceneSchema.shape.example, CanvasSceneSchema.text.example, CanvasSceneSchema.path.example, image)

        val drawing = DrawingSerializer.deserialize(validated(*examples.toTypedArray()))

        assertEquals(
            listOf(Element.Shape::class, Element.Text::class, Element.Path::class, Element.Image::class),
            drawing.elements.map { it::class },
        )
    }

    @Test
    fun theSceneExampleInTheToolDescriptionDecodes() {
        val drawing = DrawingSerializer.deserialize(
            assertIs<CanvasSceneCheck.Valid>(CanvasSceneValidator.scene(CanvasSceneSchema.sceneExample.toString())).json,
        )

        assertEquals(listOf("box-1", "title-1"), drawing.elements.map { it.id })
    }
}
