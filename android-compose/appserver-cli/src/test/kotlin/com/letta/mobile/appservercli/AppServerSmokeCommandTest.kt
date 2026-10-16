package com.letta.mobile.appservercli

import com.github.ajalt.clikt.core.UsageError
import com.letta.mobile.appserver.AppServerSmokeSpec
import java.io.File
import java.nio.file.Files
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

/** letta-mobile-by4vm.121.12: `app-server-smoke --image` sends the content array the app sends for a photo. */
class AppServerSmokeCommandTest {

    @Test
    fun `an image file becomes the app's base64 image part`() {
        val png = tempFile("smoke.PNG", byteArrayOf(1, 2, 3))

        val part = imagePart(png)

        assertEquals("image/png", part.mediaType)
        assertEquals("AQID", part.base64)
    }

    @Test
    fun `an unknown image extension is a usage error`() {
        assertThrows(UsageError::class.java) { imagePart(tempFile("smoke.bmp", byteArrayOf(1))) }
    }

    @Test
    fun `the spec sends text then images as a content array, and plain text without images`() {
        val textOnly = spec(images = emptyList())
        assertNull(textOnly.contentPartsJson)

        val withImage = spec(images = listOf(imagePart(tempFile("photo.jpg", byteArrayOf(1, 2, 3)))))
        val parts = Json.parseToJsonElement(requireNotNull(withImage.contentPartsJson)).jsonArray.map { it.jsonObject }

        assertEquals(listOf("text", "image"), parts.map { it.getValue("type").jsonPrimitive.content })
        assertEquals("what is this?", parts[0].getValue("text").jsonPrimitive.content)
        val source = parts[1].getValue("source").jsonObject
        assertEquals("base64", source.getValue("type").jsonPrimitive.content)
        assertEquals("image/jpeg", source.getValue("media_type").jsonPrimitive.content)
        assertEquals("AQID", source.getValue("data").jsonPrimitive.content)
    }

    private fun spec(images: List<com.letta.mobile.data.model.MessageContentPart.Image>) = AppServerSmokeSpec(
        url = "ws://127.0.0.1:1",
        token = null,
        agentId = "agent-1",
        conversationId = "conv-1",
        message = "what is this?",
        timeoutMs = 1_000,
        images = images,
    )

    private fun tempFile(name: String, bytes: ByteArray): File =
        Files.createTempDirectory("smoke-image").resolve(name).toFile().apply {
            writeBytes(bytes)
            deleteOnExit()
        }
}
