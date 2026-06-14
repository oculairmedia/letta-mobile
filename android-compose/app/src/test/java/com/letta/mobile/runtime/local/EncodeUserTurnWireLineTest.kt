package com.letta.mobile.runtime.local

import com.letta.mobile.runtime.TurnImagePart
import com.letta.mobile.runtime.TurnInput
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EncodeUserTurnWireLineTest {
    private val json = Json

    @Test
    fun `embedded node preloads include runtime introspection bridge after platform polyfills`() {
        val projectDir = File("/tmp/embedded-lettacode")

        val files = embeddedLettaCodePreloadRequireFiles(projectDir).map { it.name }

        assertEquals(
            listOf(
                "regexp-polyfill.cjs",
                "android-network-polyfill.cjs",
                "embedded-runtime-introspection-preload.cjs",
            ),
            files,
        )
    }

    private fun wire(input: TurnInput.UserMessage) =
        json.parseToJsonElement(encodeUserTurnWireLine(input)).jsonObject

    @Test
    fun `text-only send keeps legacy plain-string content`() {
        val obj = wire(TurnInput.UserMessage(localMessageId = "otid-1", text = "hello"))
        val message = obj["message"]!!.jsonObject
        assertEquals("user", message["role"]!!.jsonPrimitive.content)
        // content is a plain JSON string, not an array
        assertEquals("hello", message["content"]!!.jsonPrimitive.content)
        assertEquals("otid-1", message["otid"]!!.jsonPrimitive.content)
    }

    @Test
    fun `image send encodes a Letta content-union array with text first`() {
        val obj = wire(
            TurnInput.UserMessage(
                localMessageId = "otid-2",
                text = "what is this?",
                imageParts = listOf(TurnImagePart(base64 = "QUJD", mediaType = "image/png")),
            )
        )
        val content = obj["message"]!!.jsonObject["content"]!!
        assertTrue("content must be an array when images present", content is JsonArray)
        val parts = content.jsonArray
        assertEquals(2, parts.size)

        // text part first
        val textPart = parts[0].jsonObject
        assertEquals("text", textPart["type"]!!.jsonPrimitive.content)
        assertEquals("what is this?", textPart["text"]!!.jsonPrimitive.content)

        // image part as the NESTED Letta union (isBase64ImageContentPart gate)
        val imagePart = parts[1].jsonObject
        assertEquals("image", imagePart["type"]!!.jsonPrimitive.content)
        val source = imagePart["source"]!!.jsonObject
        assertEquals("base64", source["type"]!!.jsonPrimitive.content)
        assertEquals("image/png", source["media_type"]!!.jsonPrimitive.content)
        assertEquals("QUJD", source["data"]!!.jsonPrimitive.content)
        assertTrue(!source["data"]!!.jsonPrimitive.content.startsWith("data:"))
    }

    @Test
    fun `blank text with image omits the text part`() {
        val obj = wire(
            TurnInput.UserMessage(
                localMessageId = "otid-3",
                text = "   ",
                imageParts = listOf(TurnImagePart(base64 = "REVG", mediaType = "image/jpeg")),
            )
        )
        val parts = obj["message"]!!.jsonObject["content"]!!.jsonArray
        assertEquals(1, parts.size)
        assertEquals("image", parts[0].jsonObject["type"]!!.jsonPrimitive.content)
    }

    @Test
    fun `multiple images all encode in order after text`() {
        val obj = wire(
            TurnInput.UserMessage(
                localMessageId = "otid-4",
                text = "compare",
                imageParts = listOf(
                    TurnImagePart(base64 = "AAA", mediaType = "image/png"),
                    TurnImagePart(base64 = "BBB", mediaType = "image/jpeg"),
                ),
            )
        )
        val parts = obj["message"]!!.jsonObject["content"]!!.jsonArray
        assertEquals(3, parts.size)
        assertEquals("text", parts[0].jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("AAA", parts[1].jsonObject["source"]!!.jsonObject["data"]!!.jsonPrimitive.content)
        assertEquals("BBB", parts[2].jsonObject["source"]!!.jsonObject["data"]!!.jsonPrimitive.content)
    }
}
