package com.letta.mobile.runtime.clipboard

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ClipboardToolTest {

    @Test
    fun `capabilities reports read and write with correct risk and sensitivity`() {
        val tool = ClipboardTool(FakeClipboardProvider())
        val capabilities = tool.capabilities()

        assertEquals(2, capabilities.size)

        val readCap = capabilities.find { it.toolName == "clipboard.read" }!!
        assertEquals("android.clipboard.read", readCap.id)
        assertEquals("Read clipboard text", readCap.label)
        assertEquals("Available", readCap.status.name)
        assertEquals("Low", readCap.riskTier.name)
        assertEquals("PersonalData", readCap.sensitivity.name)
        assertEquals("Direct", readCap.executionMode.name)
        assertTrue(readCap.reason.contains("foreground"))

        val writeCap = capabilities.find { it.toolName == "clipboard.write" }!!
        assertEquals("android.clipboard.write", writeCap.id)
        assertEquals("Write clipboard text", writeCap.label)
        assertEquals("Available", writeCap.status.name)
        assertEquals("Low", writeCap.riskTier.name)
        assertEquals("DeviceState", writeCap.sensitivity.name)
        assertEquals("Direct", writeCap.executionMode.name)
    }

    @Test
    fun `read action returns clipboard text when available`() {
        val provider = FakeClipboardProvider(text = "Hello, clipboard!")
        val tool = ClipboardTool(provider)

        val response = tool.handle(
            buildJsonObject { put("action", "read") },
            "test-action-id"
        )

        assertTrue(response.success)
        assertEquals("clipboard.read", response.toolName)
        assertEquals("Available", response.status.name)
        assertTrue(response.message.contains("Hello, clipboard!"))
        assertEquals("android.clipboard.read", response.capabilityId)
        assertEquals("test-action-id", response.actionId)
        assertNull(response.error)
    }

    @Test
    fun `read action handles empty clipboard`() {
        val provider = FakeClipboardProvider(text = null)
        val tool = ClipboardTool(provider)

        val response = tool.handle(
            buildJsonObject { put("action", "read") },
            "test-action-id"
        )

        assertTrue(response.success)
        assertEquals("clipboard.read", response.toolName)
        assertTrue(response.message.contains("empty") || response.message.contains("no text"))
        assertNull(response.error)
    }

    @Test
    fun `read action handles provider failure`() {
        val provider = FakeClipboardProvider(shouldFail = true)
        val tool = ClipboardTool(provider)

        val response = tool.handle(
            buildJsonObject { put("action", "read") },
            "test-action-id"
        )

        assertFalse(response.success)
        assertEquals("Error", response.status.name)
        assertEquals("read_failed", response.error)
    }

    @Test
    fun `write action sets clipboard text`() {
        val provider = FakeClipboardProvider()
        val tool = ClipboardTool(provider)

        val response = tool.handle(
            buildJsonObject {
                put("action", "write")
                put("text", "New clipboard content")
            },
            "test-action-id"
        )

        assertTrue(response.success)
        assertEquals("clipboard.write", response.toolName)
        assertEquals("Available", response.status.name)
        assertTrue(response.message.contains("success"))
        assertEquals("android.clipboard.write", response.capabilityId)
        assertEquals("test-action-id", response.actionId)
        assertNull(response.error)
        assertEquals("New clipboard content", provider.lastWrittenText)
    }

    @Test
    fun `write action requires text parameter`() {
        val provider = FakeClipboardProvider()
        val tool = ClipboardTool(provider)

        val response = tool.handle(
            buildJsonObject { put("action", "write") },
            "test-action-id"
        )

        assertFalse(response.success)
        assertEquals("clipboard.write", response.toolName)
        assertEquals("Error", response.status.name)
        assertTrue(response.message.contains("Missing required parameter"))
        assertEquals("missing_text", response.error)
        assertNull(provider.lastWrittenText)
    }

    @Test
    fun `write action handles provider failure`() {
        val provider = FakeClipboardProvider(shouldFail = true)
        val tool = ClipboardTool(provider)

        val response = tool.handle(
            buildJsonObject {
                put("action", "write")
                put("text", "Test text")
            },
            "test-action-id"
        )

        assertFalse(response.success)
        assertEquals("Error", response.status.name)
        assertEquals("write_failed", response.error)
    }

    @Test
    fun `invalid action returns error`() {
        val tool = ClipboardTool(FakeClipboardProvider())

        val response = tool.handle(
            buildJsonObject { put("action", "invalid") },
            "test-action-id"
        )

        assertFalse(response.success)
        assertEquals("Error", response.status.name)
        assertTrue(response.message.contains("Invalid action"))
        assertEquals("invalid_action", response.error)
    }

    private class FakeClipboardProvider(
        private val text: String? = "default text",
        private val shouldFail: Boolean = false,
    ) : ClipboardProvider {
        var lastWrittenText: String? = null

        override fun readText(): ClipboardReadResponse {
            return if (shouldFail) {
                ClipboardReadResponse(
                    success = false,
                    text = null,
                    reason = "Simulated read failure.",
                )
            } else if (text == null) {
                ClipboardReadResponse(
                    success = true,
                    text = null,
                    reason = "Clipboard is empty.",
                )
            } else {
                ClipboardReadResponse(
                    success = true,
                    text = text,
                    reason = "Clipboard text retrieved.",
                )
            }
        }

        override fun writeText(text: String): ClipboardWriteResponse {
            return if (shouldFail) {
                ClipboardWriteResponse(
                    success = false,
                    reason = "Simulated write failure.",
                )
            } else {
                lastWrittenText = text
                ClipboardWriteResponse(
                    success = true,
                    reason = "Clipboard text set successfully.",
                )
            }
        }
    }
}
