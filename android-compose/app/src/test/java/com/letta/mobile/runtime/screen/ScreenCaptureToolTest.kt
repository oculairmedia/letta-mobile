package com.letta.mobile.runtime.screen

import com.letta.mobile.runtime.actions.MobileActionCapabilityStatus
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenCaptureToolTest {
    @Test
    fun `handle returns base64 image payload in response data`() {
        val tool = ScreenCaptureTool(FakeScreenCaptureProvider())

        val response = tool.handle(JsonObject(emptyMap()), actionId = "screen-action-1")
        val payload = response.data!!

        assertTrue(response.success)
        assertEquals(ScreenCaptureTool.TOOL_NAME, response.toolName)
        assertEquals(MobileActionCapabilityStatus.Available, response.status)
        assertEquals(ScreenCaptureTool.CAPABILITY_ID, response.capabilityId)
        assertEquals("screen-action-1", response.actionId)
        assertEquals("ZmFrZS1wbmc=", payload["imageBase64"]!!.jsonPrimitive.content)
        assertEquals("320", payload["width"]!!.jsonPrimitive.content)
        assertEquals("240", payload["height"]!!.jsonPrimitive.content)
        assertEquals("image/png", payload["mimeType"]!!.jsonPrimitive.content)
    }

    @Test
    fun `capability documents own-window scope and privileged future escalation`() {
        val capability = ScreenCaptureTool(FakeScreenCaptureProvider()).capabilities().single()

        assertEquals(ScreenCaptureTool.CAPABILITY_ID, capability.id)
        assertEquals("screen.capture", capability.toolName)
        assertEquals(MobileActionCapabilityStatus.Available, capability.status)
        assertTrue(capability.defaultEnabled)
        assertTrue(capability.description.contains("Letta app's current window"))
        assertTrue(capability.description.contains("MediaProjection/Shizuku"))
        assertTrue(capability.reason.contains("Own-window capture"))
        assertTrue(capability.reason.contains("other-app screen capture"))
        assertTrue(capability.requiredPermissions.isEmpty())
    }

    @Test
    fun `provider failure returns error envelope`() {
        val tool = ScreenCaptureTool(FailingScreenCaptureProvider())

        val response = tool.handle(JsonObject(emptyMap()), actionId = "screen-action-2")

        assertFalse(response.success)
        assertEquals(MobileActionCapabilityStatus.Error, response.status)
        assertEquals("screen_capture_failed", response.error)
        assertEquals("capture unavailable", response.message)
    }

    private class FakeScreenCaptureProvider : ScreenCaptureProvider {
        override fun captureOwnWindow(maxDimension: Int): ScreenCaptureResult = ScreenCaptureResult(
            imageBase64 = "ZmFrZS1wbmc=",
            width = 320,
            height = 240,
            mimeType = "image/png",
        )
    }

    private class FailingScreenCaptureProvider : ScreenCaptureProvider {
        override fun captureOwnWindow(maxDimension: Int): ScreenCaptureResult = error("capture unavailable")
    }
}
