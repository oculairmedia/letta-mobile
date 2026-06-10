package com.letta.mobile.data.model

import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MessageContentPartCommonTest {
    @Test
    fun serializesTextAndImagePartsInLettaWireShape() {
        val parts = listOf<MessageContentPart>(
            MessageContentPart.Text("look"),
            MessageContentPart.Image(base64 = "AAAA", mediaType = "image/jpeg"),
        ).toJsonArray()

        val text = parts[0].jsonObject
        assertEquals("text", text["type"]?.jsonPrimitive?.contentOrNull)
        assertEquals("look", text["text"]?.jsonPrimitive?.contentOrNull)

        val image = parts[1].jsonObject
        assertEquals("image", image["type"]?.jsonPrimitive?.contentOrNull)
        assertFalse("image_url" in image)

        val source = image["source"]!!.jsonObject
        assertEquals("base64", source["type"]?.jsonPrimitive?.contentOrNull)
        assertEquals("image/jpeg", source["media_type"]?.jsonPrimitive?.contentOrNull)
        assertEquals("AAAA", source["data"]?.jsonPrimitive?.contentOrNull)
    }

    // ---- bead letta-mobile-6oxlf acceptance (d): buildContentParts ----

    /**
     * Verifies that [buildContentParts] assembles [text, ...images] in the
     * order the Letta API expects and that the resulting list feeds correctly
     * into [toJsonArray].
     */
    @Test
    fun buildContentPartsProducesTextFirstThenImages() {
        val images = listOf(
            MessageContentPart.Image(base64 = "img1", mediaType = "image/png"),
            MessageContentPart.Image(base64 = "img2", mediaType = "image/jpeg"),
        )

        val parts = buildContentParts(text = "caption", images = images)

        assertEquals(3, parts.size)
        assertTrue(parts[0] is MessageContentPart.Text)
        assertEquals("caption", (parts[0] as MessageContentPart.Text).text)
        assertEquals(images[0], parts[1])
        assertEquals(images[1], parts[2])
    }

    @Test
    fun buildContentPartsOmitsTextPartWhenBlank() {
        val images = listOf(MessageContentPart.Image(base64 = "BBBB", mediaType = "image/png"))

        val parts = buildContentParts(text = "   ", images = images)

        assertEquals(1, parts.size, "Blank text must not produce a Text part")
        assertEquals(images[0], parts[0])
    }

    @Test
    fun buildContentPartsProducesEmptyListWhenBothBlankAndNoImages() {
        val parts = buildContentParts(text = "", images = emptyList())
        assertTrue(parts.isEmpty())
    }

    @Test
    fun buildContentPartsRoundTripsToLettaWireShape() {
        val images = listOf(MessageContentPart.Image(base64 = "CCCC", mediaType = "image/jpeg"))
        val parts = buildContentParts(text = "wire check", images = images)
        val array = parts.toJsonArray()

        // text part
        val textObj = array[0].jsonObject
        assertEquals("text", textObj["type"]?.jsonPrimitive?.contentOrNull)
        assertEquals("wire check", textObj["text"]?.jsonPrimitive?.contentOrNull)

        // image part — Letta base64 shape, NOT image_url
        val imageObj = array[1].jsonObject
        assertEquals("image", imageObj["type"]?.jsonPrimitive?.contentOrNull)
        assertFalse("image_url" in imageObj)
        val source = imageObj["source"]!!.jsonObject
        assertEquals("base64", source["type"]?.jsonPrimitive?.contentOrNull)
        assertEquals("image/jpeg", source["media_type"]?.jsonPrimitive?.contentOrNull)
        assertEquals("CCCC", source["data"]?.jsonPrimitive?.contentOrNull)
    }
}
