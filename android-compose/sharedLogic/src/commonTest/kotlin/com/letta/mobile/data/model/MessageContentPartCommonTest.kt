package com.letta.mobile.data.model

import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

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
}
