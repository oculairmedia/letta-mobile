package com.letta.mobile.data.mapper

import com.letta.mobile.data.model.MessageType
import com.letta.mobile.testutil.TestData
import io.kotest.core.spec.style.WordSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotBeBlank

class MessageMapperTest : WordSpec({
    "AppMessage.toUiMessage" should {
        "map user messages to role user" {
            val uiMsg = TestData.appMessage(messageType = MessageType.USER, content = "Hello").toUiMessage()
            uiMsg.role shouldBe "user"
            uiMsg.content shouldBe "Hello"
            uiMsg.isReasoning shouldBe false
            uiMsg.toolCalls.shouldBeNull()
        }

        "map assistant messages to role assistant" {
            val uiMsg = TestData.appMessage(messageType = MessageType.ASSISTANT, content = "Hi there").toUiMessage()
            uiMsg.role shouldBe "assistant"
            uiMsg.content shouldBe "Hi there"
        }

        "map reasoning messages to assistant role with isReasoning" {
            val uiMsg = TestData.appMessage(messageType = MessageType.REASONING, content = "Thinking...").toUiMessage()
            uiMsg.role shouldBe "assistant"
            uiMsg.isReasoning shouldBe true
        }

        "map tool call messages to tool role with tool calls" {
            val uiMsg = TestData.appMessage(
                messageType = MessageType.TOOL_CALL,
                content = "{\"query\": \"test\"}",
                toolName = "web_search",
                toolCallId = "tc-1"
            ).toUiMessage()
            uiMsg.role shouldBe "tool"
            uiMsg.toolCalls?.size shouldBe 1
            uiMsg.toolCalls?.first()?.name shouldBe "web_search"
        }

        "map tool return messages to tool role with tool result details" {
            val uiMsg = TestData.appMessage(
                messageType = MessageType.TOOL_RETURN,
                content = "Search results...",
                toolName = "web_search",
                toolCallId = "tc-1"
            ).toUiMessage()
            uiMsg.role shouldBe "tool"
            uiMsg.content shouldBe ""
            uiMsg.toolCalls?.size shouldBe 1
            uiMsg.toolCalls?.first()?.name shouldBe "web_search"
            uiMsg.toolCalls?.first()?.result shouldBe "Search results..."
        }

        "preserve timestamp" {
            TestData.appMessage(id = "m1").toUiMessage().timestamp.shouldNotBeBlank()
        }

        "preserve id" {
            TestData.appMessage(id = "unique-id-123").toUiMessage().id shouldBe "unique-id-123"
        }
    }
})
