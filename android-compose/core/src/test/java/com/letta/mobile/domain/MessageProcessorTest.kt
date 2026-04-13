package com.letta.mobile.domain

import com.letta.mobile.data.api.MessageApi
import com.letta.mobile.data.model.ApprovalRequestMessage
import com.letta.mobile.data.model.ApprovalResponseMessage
import com.letta.mobile.data.model.ApprovalResult
import com.letta.mobile.data.model.AssistantMessage
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
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

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

        "emit generated ui assistant payloads during streaming" {
            val processor = MessageProcessor(ClientToolRegistry())
            val stream = flowOf(
                AssistantMessage(
                    id = "assistant-ui-1",
                    contentRaw = buildJsonObject {
                        put("type", "generated_ui")
                        put("component", "summary_card")
                        putJsonObject("props") {
                            put("title", "Today")
                            put("body", "3 tasks pending")
                        }
                        put("text", "Here is your summary")
                    },
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

            messages shouldHaveSize 1
            messages[0].content shouldBe "Here is your summary"
            messages[0].generatedUi?.component shouldBe "summary_card"
            messages[0].generatedUi?.propsJson shouldBe "{\"title\":\"Today\",\"body\":\"3 tasks pending\"}"
        }

        "emit approval request and response messages during streaming" {
            val processor = MessageProcessor(ClientToolRegistry())
            val stream = flowOf(
                ApprovalRequestMessage(
                    id = "approval-request-1",
                    toolCalls = listOf(
                        ToolCall(
                            toolCallId = "call-approval-1",
                            name = "bash",
                            arguments = "{\"command\":\"rm -rf /tmp/demo\"}",
                        ),
                    ),
                ),
                ApprovalResponseMessage(
                    id = "approval-response-1",
                    approvalRequestId = "approval-request-1",
                    approve = false,
                    reason = "Unsafe command",
                    approvals = listOf(
                        ApprovalResult(
                            toolCallId = "call-approval-1",
                            approve = false,
                            status = "rejected",
                            reason = "Unsafe command",
                        ),
                    ),
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
            messages[0].approvalRequest?.requestId shouldBe "approval-request-1"
            messages[0].approvalRequest?.toolCalls?.single()?.toolCallId shouldBe "call-approval-1"
            messages[1].approvalResponse?.requestId shouldBe "approval-request-1"
            messages[1].approvalResponse?.approved shouldBe false
            messages[1].approvalResponse?.reason shouldBe "Unsafe command"
        }
    }
})
