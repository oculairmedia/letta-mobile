package com.letta.mobile.runtime.local

import com.google.ai.edge.litertlm.Content
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LiteRtLmOnDeviceVisionTest {
    @Test
    fun `LiteRT request maps images before text content`() {
        val request = LiteRtLmRequest.from(
            prompt = "Describe this image",
            images = listOf(OnDeviceImage(byteArrayOf(1, 2, 3), "image/png")),
        )

        val contents = request.toContents().contents

        assertTrue(request.requiresVision)
        assertEquals(2, contents.size)
        assertTrue(contents[0] is Content.ImageBytes)
        assertTrue(contents[1] is Content.Text)
        assertEquals("Describe this image", (contents[1] as Content.Text).text)
    }

    @Test
    fun `LiteRT request omits blank text after images`() {
        val request = LiteRtLmRequest.from(
            prompt = "   ",
            images = listOf(OnDeviceImage(byteArrayOf(1), "image/png")),
        )

        val contents = request.toContents().contents

        assertEquals(1, contents.size)
        assertTrue(contents.single() is Content.ImageBytes)
    }

    @Test
    fun `LiteRT falls back to text sending when vision engine is unavailable`() {
        val request = LiteRtLmRequest.from(
            prompt = "Describe this image",
            images = listOf(OnDeviceImage(byteArrayOf(1), "image/png")),
        )

        assertTrue(shouldSendVisionContent(request, engineVisionEnabled = true))
        assertFalse(shouldSendVisionContent(request, engineVisionEnabled = false))
    }

    @Test
    fun `LiteRT cache key includes vision mode`() {
        val selection = EmbeddedLettaCodeModelSelection(
            modelHandle = "local/gemma-3n",
            modelPath = "/data/model/gemma.litertlm",
            runtime = "litert-lm",
            accelerator = "gpu",
            maxTokens = 4096,
        )

        val textKey = liteRtLmEngineCacheKey(selection, visionEnabled = false)
        val visionKey = liteRtLmEngineCacheKey(selection, visionEnabled = true)

        assertFalse(textKey == visionKey)
        assertTrue(textKey.endsWith("|vision=false"))
        assertTrue(visionKey.endsWith("|vision=true"))
    }
}
