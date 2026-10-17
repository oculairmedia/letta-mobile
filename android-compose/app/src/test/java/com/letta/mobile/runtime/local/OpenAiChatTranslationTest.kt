package com.letta.mobile.runtime.local

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** letta-mobile-by4vm.121.16: OpenAI chat requests translate one to one, or are rejected with the reason. */
class OpenAiChatTranslationTest {
    @Test
    fun `system messages tools and the tool round trip translate without loss`() {
        val longSystem = "You are Letta. ".repeat(400)
        val chat = translate(
            """
            {
              "messages": [
                {"role": "system", "content": "$longSystem"},
                {"role": "user", "content": "Turn on the flashlight"},
                {"role": "assistant", "content": null, "tool_calls": [
                  {"id": "call_a", "type": "function", "function": {"name": "device_action", "arguments": "{\"command\":\"hardware.flashlight\",\"input\":{\"enabled\":true}}"}}
                ]},
                {"role": "tool", "tool_call_id": "call_a", "content": "{\"success\":true}"}
              ],
              "tools": [$DEVICE_ACTION_TOOL],
              "tool_choice": "auto"
            }
            """,
        )

        assertEquals(longSystem, chat.systemInstruction)
        assertEquals(listOf(OnDeviceRole.User, OnDeviceRole.Model), chat.history.map { it.role })
        assertEquals("Turn on the flashlight", chat.history[0].text)
        val call = chat.history[1].toolCalls.single()
        assertEquals(OnDeviceToolCall("call_a", "device_action", """{"command":"hardware.flashlight","input":{"enabled":true}}"""), call)
        assertEquals(OnDeviceRole.Tool, chat.latest.role)
        assertEquals(listOf(OnDeviceToolResult("device_action", """{"success":true}""")), chat.latest.toolResults)
        val tool = chat.tools.single()
        assertEquals("device_action", tool.name)
        assertEquals(Json.parseToJsonElement(DEVICE_ACTION_TOOL).jsonObject["function"], Json.parseToJsonElement(tool.functionJson))
    }

    @Test
    fun `parallel tool results go back together`() {
        val chat = translate(
            """
            {"messages": [
              {"role": "user", "content": "both"},
              {"role": "assistant", "tool_calls": [
                {"id": "c1", "type": "function", "function": {"name": "Read", "arguments": "{}"}},
                {"id": "c2", "type": "function", "function": {"name": "Memory", "arguments": ""}}
              ]},
              {"role": "tool", "tool_call_id": "c1", "content": "a"},
              {"role": "tool", "tool_call_id": "c2", "content": [{"type": "text", "text": "b"}]}
            ]}
            """,
        )

        assertEquals(listOf("Read", "Memory"), chat.latest.toolResults.map { it.name })
        assertEquals(listOf("a", "b"), chat.latest.toolResults.map { it.content })
        assertEquals("{}", chat.history.last().toolCalls[1].argumentsJson)
    }

    @Test
    fun `every image stays on its own message`() {
        val chat = translate(
            """
            {"messages": [
              {"role": "user", "content": [{"type": "image_url", "image_url": {"url": "data:image/png;base64,AQIDBA=="}}, {"type": "text", "text": "first"}]},
              {"role": "assistant", "content": "a cat"},
              {"role": "user", "content": [{"type": "image_url", "image_url": "BQY="}, {"type": "text", "text": "second"}]},
              {"role": "assistant", "content": "a dog"},
              {"role": "user", "content": [{"type": "image_url", "image_url": {"url": "data:image/jpeg;base64,Bwg="}}, {"type": "text", "text": "third"}]}
            ]}
            """,
        )

        assertEquals(3, chat.images.size)
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), chat.history[0].images.single().bytes)
        assertEquals("image/png", chat.history[0].images.single().mediaType)
        assertNull(chat.history[2].images.single().mediaType)
        assertEquals("third", chat.latest.text)
    }

    @Test
    fun `tool choice none offers no tools`() {
        val chat = translate("""{"messages":[{"role":"user","content":"hi"}],"tools":[$DEVICE_ACTION_TOOL],"tool_choice":"none"}""")

        assertTrue(chat.tools.isEmpty())
    }

    @Test
    fun `a forced tool choice is rejected because LiteRT cannot enforce it`() {
        val required = rejection("""{"messages":[{"role":"user","content":"hi"}],"tools":[$DEVICE_ACTION_TOOL],"tool_choice":"required"}""")
        val named = rejection(
            """{"messages":[{"role":"user","content":"hi"}],"tools":[$DEVICE_ACTION_TOOL],
            "tool_choice":{"type":"function","function":{"name":"device_action"}}}""",
        )

        assertEquals(OpenAiChatTranslation.UNSUPPORTED, required.code)
        assertEquals(OpenAiChatTranslation.UNSUPPORTED, named.code)
    }

    @Test
    fun `malformed requests are rejected with the reason`() {
        assertEquals(OpenAiChatTranslation.INVALID_REQUEST, rejection("""{"messages":[]}""").code)
        assertTrue(rejection("""{"messages":[{"role":"tool","tool_call_id":"x","content":"orphan"}]}""").message!!.contains("x"))
        val endsWithModel = rejection("""{"messages":[{"role":"user","content":"a"},{"role":"assistant","content":"b"}]}""")
        assertTrue(endsWithModel.message!!.contains("last message"))
        assertTrue(
            rejection(
                """{"messages":[{"role":"assistant","tool_calls":[{"id":"c","function":{"name":"Read","arguments":"[1]"}}]},
                {"role":"tool","tool_call_id":"c","content":"r"}]}""",
            ).message!!.contains("not a JSON object"),
        )
    }

    private fun translate(body: String): OnDeviceChatRequest =
        OpenAiChatTranslation.translate(Json.parseToJsonElement(body.trimIndent()).jsonObject)

    private fun rejection(body: String): OnDeviceRequestRejected =
        assertThrows(OnDeviceRequestRejected::class.java) { translate(body) }

    private companion object {
        const val DEVICE_ACTION_TOOL =
            """{"type":"function","function":{"name":"device_action","description":"Run a device command.",
            "parameters":{"type":"object","properties":{"command":{"type":"string"},"input":{"type":"object"}},
            "required":["command"]}}}"""
    }
}
