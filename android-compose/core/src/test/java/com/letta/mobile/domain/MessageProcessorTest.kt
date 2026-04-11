package com.letta.mobile.domain

import com.letta.mobile.data.api.MessageApi
import com.letta.mobile.data.model.ToolCall
import com.letta.mobile.data.model.ToolCallMessage
import com.letta.mobile.data.model.ToolReturnMessage
import io.kotest.core.spec.style.WordSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive

class MessageProcessorTest : WordSpec({
    "processStream" should {
        "carry tool names forward to tool return messages" {
            val processor = MessageProcessor(ClientToolRegistry())
            val stream = flowOf(
                ToolCallMessage(
                    id = "tool-call-1",
                    toolCall = ToolCall(
                        toolCallId = "call-123",
                        name = "web_search",
                        arguments = "{\"query\":\"kotlin\"}",
                    ),
                ),
                ToolReturnMessage(
                    id = "tool-return-1",
                    toolCallId = "call-123",
                    toolReturnRaw = JsonPrimitive("Found 10 results"),
                ),
            )

            val messages = runBlocking {
                processor.processStream(
                    stream = stream,
                    agentId = "agent-1",
                    conversationId = "conversation-1",
                    messageApi = mockk<MessageApi>(relaxed = true),
                ).toList()
            }

            messages shouldHaveSize 2
            messages[0].toolName shouldBe "web_search"
            messages[1].toolName shouldBe "web_search"
            messages[1].content shouldBe "Found 10 results"
        }
    }
})
