package com.letta.mobile.runtime.local

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OnDeviceToolCallProtocolTest {
    private val json = Json

    private fun request(body: String) = json.parseToJsonElement(body).jsonObject

    @Test
    fun `renderPrompt includes compact tool instructions without full schemas`() {
        val prompt = OnDeviceToolCallProtocol.renderPrompt(
            request(
                """
                {
                  "messages": [
                    {"role": "system", "content": "You are helpful."},
                    {"role": "user", "content": "hi"}
                  ],
                  "tools": [
                    {"type": "function", "function": {
                      "name": "memory",
                      "description": "Edit memory.\nSecond line ignored.",
                      "parameters": {"type": "object", "properties": {"path": {"type": "string"}}}
                    }}
                  ]
                }
                """.trimIndent()
            )
        )

        assertTrue(prompt.contains("```tool_call"))
        assertTrue(prompt.contains("- memory: Edit memory."))
        assertTrue("full parameter schemas should stay out of the LiteRT prompt budget", !prompt.contains("\"path\""))
        assertTrue(prompt.contains("system: You are helpful."))
        assertTrue(prompt.contains("user: hi"))
    }

    @Test
    fun `renderPrompt renders prior tool calls and tool results`() {
        val prompt = OnDeviceToolCallProtocol.renderPrompt(
            request(
                """
                {
                  "messages": [
                    {"role": "user", "content": "list files"},
                    {"role": "assistant", "content": null, "tool_calls": [
                      {"id": "call_1", "type": "function", "function": {"name": "Bash", "arguments": "{\"command\":\"ls\"}"}}
                    ]},
                    {"role": "tool", "tool_call_id": "call_1", "content": "file-a\nfile-b"}
                  ]
                }
                """.trimIndent()
            )
        )

        assertTrue(prompt.contains("assistant called tool (call_1)"))
        assertTrue(prompt.contains("\"Bash\""))
        assertTrue(prompt.contains("tool result (call_1): file-a\nfile-b"))
    }


    @Test
    fun `renderPrompt keeps recent messages within bounded prompt budget`() {
        val oldMessages = (1..20).joinToString(",") { index ->
            "{\"role\":\"user\",\"content\":\"old-$index ${"x".repeat(500)}\"}"
        }
        val prompt = OnDeviceToolCallProtocol.renderPrompt(
            request(
                """
                {
                  "messages": [
                    $oldMessages,
                    {"role": "user", "content": "latest question"}
                  ]
                }
                """.trimIndent()
            )
        )

        assertTrue(prompt.length <= 8_500)
        assertTrue(prompt.contains("latest question"))
        assertTrue(prompt.contains("Older conversation context omitted"))
    }

    @Test
    fun `extractImages decodes OpenAI data URL image parts`() {
        val images = OnDeviceToolCallProtocol.extractImages(
            request(
                """
                {
                  "messages": [{"role": "user", "content": [
                    {"type": "image_url", "image_url": {"url": "data:image/png;base64,AQID"}},
                    {"type": "text", "text": "what is this"}
                  ]}]
                }
                """.trimIndent(),
            ),
        )

        assertEquals(1, images.size)
        assertEquals("image/png", images.single().mediaType)
        assertArrayEquals(byteArrayOf(1, 2, 3), images.single().bytes)
    }

    @Test
    fun `decodeOpenAiImageUrl accepts raw base64 and rejects invalid payloads`() {
        val image = OnDeviceToolCallProtocol.decodeOpenAiImageUrl("AQID")

        assertEquals(null, image?.mediaType)
        assertArrayEquals(byteArrayOf(1, 2, 3), image?.bytes)
        assertEquals(null, OnDeviceToolCallProtocol.decodeOpenAiImageUrl("not base64!"))
    }

    @Test
    fun `parses fenced tool call with leading text`() {
        val turn = OnDeviceToolCallProtocol.parseModelOutput(
            "Let me check.\n```tool_call\n{\"name\": \"Read\", \"arguments\": {\"file_path\": \"/tmp/x\"}}\n```",
        )

        turn as OnDeviceToolCallProtocol.ModelTurn.ToolCall
        assertEquals("Read", turn.name)
        assertTrue(turn.argumentsJson.contains("\"/tmp/x\""))
        assertEquals("Let me check.", turn.leadingText)
    }

    @Test
    fun `parses json fence and bare json object as tool call`() {
        val fenced = OnDeviceToolCallProtocol.parseModelOutput(
            "```json\n{\"name\": \"memory\", \"arguments\": {}}\n```",
        )
        val bare = OnDeviceToolCallProtocol.parseModelOutput(
            "{\"name\": \"memory\", \"arguments\": {\"op\": \"view\"}}",
        )

        assertTrue(fenced is OnDeviceToolCallProtocol.ModelTurn.ToolCall)
        bare as OnDeviceToolCallProtocol.ModelTurn.ToolCall
        assertEquals("memory", bare.name)
    }

    @Test
    fun `plain text and non-tool json stay text`() {
        val text = OnDeviceToolCallProtocol.parseModelOutput("Just an answer.")
        val jsonNoName = OnDeviceToolCallProtocol.parseModelOutput(
            "```json\n{\"answer\": 42}\n```",
        )

        assertTrue(text is OnDeviceToolCallProtocol.ModelTurn.Text)
        assertTrue(jsonNoName is OnDeviceToolCallProtocol.ModelTurn.Text)
    }
}
