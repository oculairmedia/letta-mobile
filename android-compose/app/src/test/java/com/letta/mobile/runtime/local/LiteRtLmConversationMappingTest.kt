package com.letta.mobile.runtime.local

import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Role
import com.google.ai.edge.litertlm.ToolCall
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** letta-mobile-by4vm.121.16: the request reaches LiteRT-LM's conversation API one to one. */
class LiteRtLmConversationMappingTest {
    @Test
    fun `system instruction history and tools map onto the conversation config`() {
        val request = OnDeviceChatRequest(
            systemInstruction = "Be brief.",
            history = listOf(
                OnDeviceMessage.user("What is the battery level?"),
                OnDeviceMessage(
                    OnDeviceRole.Model,
                    toolCalls = listOf(OnDeviceToolCall("call_1", "device_action", """{"command":"sensors.summary"}""")),
                ),
                OnDeviceMessage(OnDeviceRole.Tool, toolResults = listOf(OnDeviceToolResult("device_action", "battery 81%"))),
            ),
            latest = OnDeviceMessage.user("Thanks, and the temperature?"),
            tools = listOf(OnDeviceTool("device_action", DEVICE_ACTION_FUNCTION)),
        )

        val config = LiteRtLmConversationMapping.config(request, maxOutputTokens = 512)

        assertEquals("Be brief.", (config.systemInstruction!!.contents.single() as Content.Text).text)
        assertEquals(listOf(Role.USER, Role.MODEL, Role.TOOL), config.initialMessages.map { it.role })
        val call = config.initialMessages[1].toolCalls.single()
        assertEquals("device_action", call.name)
        assertEquals("sensors.summary", call.arguments["command"])
        val result = config.initialMessages[2].contents.contents.single() as Content.ToolResponse
        assertEquals("device_action", result.name)
        assertEquals("battery 81%", result.response)
        assertEquals(1, config.tools.size)
        assertFalse("letta-code runs the tools, never LiteRT-LM", config.automaticToolCalling)
        assertEquals(512, config.maxOutputToken)
    }

    @Test
    fun `no system message means no system instruction`() {
        val config = LiteRtLmConversationMapping.config(OnDeviceChatRequest.userText("hi"), maxOutputTokens = 64)

        assertNull(config.systemInstruction)
        assertTrue(config.initialMessages.isEmpty())
        assertTrue(config.tools.isEmpty())
    }

    @Test
    fun `a user message maps images before text`() {
        val message = LiteRtLmConversationMapping.message(
            OnDeviceMessage(OnDeviceRole.User, "Describe this image", images = listOf(OnDeviceImage(byteArrayOf(1, 2, 3), "image/png"))),
        )

        val contents = message.contents.contents
        assertEquals(Role.USER, message.role)
        assertEquals(2, contents.size)
        assertTrue(contents[0] is Content.ImageBytes)
        assertEquals("Describe this image", (contents[1] as Content.Text).text)
    }

    @Test
    fun `a blank text after images is not sent`() {
        val message = LiteRtLmConversationMapping.message(
            OnDeviceMessage(OnDeviceRole.User, "", images = listOf(OnDeviceImage(byteArrayOf(1), "image/png"))),
        )

        assertTrue(message.contents.contents.single() is Content.ImageBytes)
    }

    @Test
    fun `tool results of one round go back as one tool message`() {
        val message = LiteRtLmConversationMapping.message(
            OnDeviceMessage(
                OnDeviceRole.Tool,
                toolResults = listOf(OnDeviceToolResult("Read", "file body"), OnDeviceToolResult("Memory", "ok")),
            ),
        )

        assertEquals(Role.TOOL, message.role)
        assertEquals(listOf("Read", "Memory"), message.contents.contents.map { (it as Content.ToolResponse).name })
    }

    @Test
    fun `model tool calls come back as openai calls with json arguments`() {
        val reply = LiteRtLmConversationMapping.reply(
            text = "",
            toolCalls = listOf(ToolCall("device_action", mapOf("command" to "hardware.flashlight", "input" to mapOf("enabled" to true)))),
        )

        val call = reply.toolCalls.single()
        assertNull("the bridge mints the OpenAI id", call.id)
        assertEquals("device_action", call.name)
        assertEquals("""{"command":"hardware.flashlight","input":{"enabled":true}}""", call.argumentsJson)
    }

    @Test
    fun `surrogates the native boundary drops are counted for the log`() {
        assertEquals(2, OnDeviceChatRequest.userText("ok 😀").surrogateCount())
    }

    @Test
    fun `the engine context overflow becomes a named non retryable rejection`() {
        val error = RuntimeException(
            "Failed to send message",
            IllegalStateException(
                "INVALID_ARGUMENT: Input token ids are too long. " +
                    "Exceeding the maximum number of tokens allowed: 6120 >= 4096\nstack",
            ),
        )

        val rejected = contextOverflow(error, SELECTION)!!

        assertEquals("on_device_context_overflow", rejected.code)
        assertTrue(rejected.message!!.contains("6120 >= 4096"))
        assertTrue(rejected.message!!.contains("4096 tokens"))
        assertNull(contextOverflow(IllegalStateException("GPU delegate failed"), SELECTION))
    }

    @Test
    fun `LiteRT cache key includes vision mode`() {
        val textKey = liteRtLmEngineCacheKey(SELECTION, visionEnabled = false)
        val visionKey = liteRtLmEngineCacheKey(SELECTION, visionEnabled = true)

        assertFalse(textKey == visionKey)
        assertTrue(textKey.endsWith("|vision=false"))
        assertTrue(visionKey.endsWith("|vision=true"))
    }

    private companion object {
        val SELECTION = EmbeddedLettaCodeModelSelection(
            modelHandle = "local/gemma-3n",
            modelPath = "/data/model/gemma.litertlm",
            runtime = "litert-lm",
            accelerator = "gpu",
            maxTokens = 4096,
        )
        const val DEVICE_ACTION_FUNCTION =
            """{"name":"device_action","description":"Run a device command.",
            "parameters":{"type":"object","properties":{"command":{"type":"string"}}}}"""
    }
}
