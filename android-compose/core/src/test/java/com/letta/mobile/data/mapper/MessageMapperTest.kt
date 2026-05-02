package com.letta.mobile.data.mapper

import com.letta.mobile.data.model.ApprovalRequestMessage
import com.letta.mobile.data.model.ApprovalResponseMessage
import com.letta.mobile.data.model.ApprovalResult
import com.letta.mobile.data.model.AssistantMessage
import com.letta.mobile.data.model.MessageType
import com.letta.mobile.data.model.ToolCall
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import com.letta.mobile.testutil.TestData
import io.kotest.core.spec.style.WordSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotBeBlank
import java.time.Instant
import org.junit.jupiter.api.Tag

@Tag("unit")
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

        "propagate pending state to ui message" {
            val uiMsg = TestData.appMessage(
                id = "pending-local",
                content = "Queued",
                isPending = true,
                localId = "local-1",
            ).toUiMessage()

            uiMsg.isPending shouldBe true
        }

        "thread run id and step id onto ui message" {
            val uiMsg = TestData.appMessage(
                messageType = MessageType.ASSISTANT,
                content = "Hi there",
                runId = "run-123",
                stepId = "step-456",
            ).toUiMessage()

            uiMsg.runId shouldBe "run-123"
            uiMsg.stepId shouldBe "step-456"
        }

        "always produce toolCalls for TOOL_CALL even without name" {
            val uiMsg = TestData.appMessage(
                messageType = MessageType.TOOL_CALL,
                content = "{}",
                toolName = null,
                toolCallId = "tc-x"
            ).toUiMessage()
            uiMsg.toolCalls.shouldNotBeNull()
            uiMsg.toolCalls!! shouldHaveSize 1
            uiMsg.toolCalls!!.first().name shouldBe "tool"
        }

        "always produce toolCalls for TOOL_RETURN even without name" {
            val uiMsg = TestData.appMessage(
                messageType = MessageType.TOOL_RETURN,
                content = "result data",
                toolName = null,
                toolCallId = "tc-y"
            ).toUiMessage()
            uiMsg.toolCalls.shouldNotBeNull()
            uiMsg.toolCalls!! shouldHaveSize 1
            uiMsg.toolCalls!!.first().name shouldBe "tool"
            uiMsg.toolCalls!!.first().result shouldBe "result data"
        }

        "propagate inline image attachments on user messages (letta-mobile-mge5.24)" {
            val img = com.letta.mobile.data.model.MessageContentPart.Image(
                base64 = "AAAA",
                mediaType = "image/png",
            )
            val appMsg = com.letta.mobile.data.model.AppMessage(
                id = "u1",
                date = java.time.Instant.parse("2024-03-15T10:00:00Z"),
                messageType = MessageType.USER,
                content = "caption",
                attachments = listOf(img),
            )
            val uiMsg = appMsg.toUiMessage()
            uiMsg.attachments shouldHaveSize 1
            uiMsg.attachments.first().base64 shouldBe "AAAA"
            uiMsg.attachments.first().mediaType shouldBe "image/png"
        }

        "hydrate UserMessage.contentRaw image parts end-to-end through toAppMessages" {
            // Simulates what history-hydration actually sees on the wire:
            // a user_message with a multimodal content array whose image
            // part uses source.type=letta with inline data (the real shape
            // the Letta server returns for persisted image uploads).
            val contentRaw = kotlinx.serialization.json.buildJsonArray {
                add(kotlinx.serialization.json.buildJsonObject {
                    put("type", kotlinx.serialization.json.JsonPrimitive("text"))
                    put("text", kotlinx.serialization.json.JsonPrimitive("look"))
                })
                add(kotlinx.serialization.json.buildJsonObject {
                    put("type", kotlinx.serialization.json.JsonPrimitive("image"))
                    put("source", kotlinx.serialization.json.buildJsonObject {
                        put("type", kotlinx.serialization.json.JsonPrimitive("letta"))
                        put("file_id", kotlinx.serialization.json.JsonPrimitive("file-xyz"))
                        put("media_type", kotlinx.serialization.json.JsonPrimitive("image/jpeg"))
                        put("data", kotlinx.serialization.json.JsonPrimitive("HYDRATED+/=="))
                    })
                })
            }
            val userMessage = com.letta.mobile.data.model.UserMessage(
                id = "user-history-1",
                contentRaw = contentRaw,
            )

            val appMessages = listOf<com.letta.mobile.data.model.LettaMessage>(userMessage).toAppMessages()
            appMessages shouldHaveSize 1
            val app = appMessages.first()
            app.messageType shouldBe MessageType.USER
            app.content shouldBe "look"
            app.attachments shouldHaveSize 1
            app.attachments.first().base64 shouldBe "HYDRATED+/=="
            app.attachments.first().mediaType shouldBe "image/jpeg"

            val uiMessages = appMessages.toUiMessages()
            uiMessages shouldHaveSize 1
            val ui = uiMessages.first()
            ui.role shouldBe "user"
            ui.attachments shouldHaveSize 1
            ui.attachments.first().base64 shouldBe "HYDRATED+/=="
        }
    }

    "List<AppMessage>.toUiMessages" should {
        "merge TOOL_CALL and matching TOOL_RETURN into single card" {
            val messages = listOf(
                TestData.appMessage(id = "m1", messageType = MessageType.USER, content = "search for cats"),
                TestData.appMessage(
                    id = "m2",
                    messageType = MessageType.TOOL_CALL,
                    content = "{\"query\": \"cats\"}",
                    toolName = "web_search",
                    toolCallId = "tc-1",
                    date = Instant.parse("2024-03-15T10:00:01Z"),
                ),
                TestData.appMessage(
                    id = "m3",
                    messageType = MessageType.TOOL_RETURN,
                    content = "Found 10 results about cats",
                    toolName = "web_search",
                    toolCallId = "tc-1",
                    date = Instant.parse("2024-03-15T10:00:03Z"),
                ),
                TestData.appMessage(id = "m4", messageType = MessageType.ASSISTANT, content = "Here are cat results"),
            )

            val ui = messages.toUiMessages()
            ui shouldHaveSize 3
            ui[0].role shouldBe "user"
            ui[1].role shouldBe "tool"
            ui[1].toolCalls.shouldNotBeNull()
            ui[1].toolCalls!! shouldHaveSize 1
            val tc = ui[1].toolCalls!!.first()
            tc.name shouldBe "web_search"
            tc.arguments shouldBe "{\"query\": \"cats\"}"
            tc.result shouldBe "Found 10 results about cats"
            tc.executionTimeMs shouldBe 2_000L
            ui[2].role shouldBe "assistant"
        }

        "promote send_message tool to assistant bubble" {
            val messages = listOf(
                TestData.appMessage(id = "m1", messageType = MessageType.USER, content = "Hello"),
                TestData.appMessage(
                    id = "m2",
                    messageType = MessageType.TOOL_CALL,
                    content = "{\"message\": \"Hi there, how can I help?\"}",
                    toolName = "send_message",
                    toolCallId = "tc-sm"
                ),
                TestData.appMessage(
                    id = "m3",
                    messageType = MessageType.TOOL_RETURN,
                    content = "None",
                    toolName = "send_message",
                    toolCallId = "tc-sm"
                ),
            )

            val ui = messages.toUiMessages()
            ui shouldHaveSize 2
            ui[0].role shouldBe "user"
            ui[1].role shouldBe "assistant"
            ui[1].content shouldBe "Hi there, how can I help?"
        }

        "render orphaned TOOL_RETURN as standalone tool card" {
            val messages = listOf(
                TestData.appMessage(
                    id = "m1",
                    messageType = MessageType.TOOL_RETURN,
                    content = "orphan result",
                    toolName = "archival_memory_search",
                    toolCallId = "tc-orphan"
                ),
            )

            val ui = messages.toUiMessages()
            ui shouldHaveSize 1
            ui[0].role shouldBe "tool"
            ui[0].toolCalls.shouldNotBeNull()
            ui[0].toolCalls!! shouldHaveSize 1
            ui[0].toolCalls!!.first().name shouldBe "archival_memory_search"
            ui[0].toolCalls!!.first().result shouldBe "orphan result"
        }

        "handle TOOL_CALL without matching TOOL_RETURN" {
            val messages = listOf(
                TestData.appMessage(
                    id = "m1",
                    messageType = MessageType.TOOL_CALL,
                    content = "{\"key\": \"val\"}",
                    toolName = "core_memory_append",
                    toolCallId = "tc-no-return"
                ),
            )

            val ui = messages.toUiMessages()
            ui shouldHaveSize 1
            ui[0].role shouldBe "tool"
            val tc = ui[0].toolCalls!!.first()
            tc.name shouldBe "core_memory_append"
            tc.arguments shouldBe "{\"key\": \"val\"}"
            tc.result.shouldBeNull()
        }

        "preserve run and step ids across merged tool call rendering" {
            val messages = listOf(
                TestData.appMessage(
                    id = "m1",
                    messageType = MessageType.TOOL_CALL,
                    content = "{}",
                    toolName = "archival_memory_search",
                    toolCallId = "tc-1",
                    runId = "run-1",
                    stepId = "step-1",
                ),
                TestData.appMessage(
                    id = "m2",
                    messageType = MessageType.TOOL_RETURN,
                    content = "results",
                    toolName = "archival_memory_search",
                    toolCallId = "tc-1",
                    runId = "run-1",
                    stepId = "step-1",
                ),
            )

            val ui = messages.toUiMessages()
            ui shouldHaveSize 1
            ui.first().runId shouldBe "run-1"
            ui.first().stepId shouldBe "step-1"
        }

        "pass through user, assistant, reasoning unchanged" {
            val messages = listOf(
                TestData.appMessage(id = "m1", messageType = MessageType.USER, content = "hello"),
                TestData.appMessage(id = "m2", messageType = MessageType.ASSISTANT, content = "hi"),
                TestData.appMessage(id = "m3", messageType = MessageType.REASONING, content = "thinking"),
            )

            val ui = messages.toUiMessages()
            ui shouldHaveSize 3
            ui[0].role shouldBe "user"
            ui[1].role shouldBe "assistant"
            ui[2].role shouldBe "assistant"
            ui[2].isReasoning shouldBe true
        }

        "handle orphaned send_message TOOL_RETURN as assistant message" {
            val messages = listOf(
                TestData.appMessage(
                    id = "m1",
                    messageType = MessageType.TOOL_RETURN,
                    content = "Hi from agent",
                    toolName = "send_message",
                    toolCallId = "tc-sm-orphan"
                ),
            )

            val ui = messages.toUiMessages()
            ui shouldHaveSize 1
            ui[0].role shouldBe "assistant"
            ui[0].content shouldBe "Hi from agent"
        }

        "handle multiple tool call-return pairs" {
            val messages = listOf(
                TestData.appMessage(
                    id = "m1", messageType = MessageType.TOOL_CALL,
                    content = "{}", toolName = "archival_memory_search", toolCallId = "tc-1"
                ),
                TestData.appMessage(
                    id = "m2", messageType = MessageType.TOOL_RETURN,
                    content = "results A", toolName = "archival_memory_search", toolCallId = "tc-1"
                ),
                TestData.appMessage(
                    id = "m3", messageType = MessageType.TOOL_CALL,
                    content = "{}", toolName = "core_memory_append", toolCallId = "tc-2"
                ),
                TestData.appMessage(
                    id = "m4", messageType = MessageType.TOOL_RETURN,
                    content = "OK", toolName = "core_memory_append", toolCallId = "tc-2"
                ),
            )

            val ui = messages.toUiMessages()
            ui shouldHaveSize 2
            ui[0].toolCalls!!.first().name shouldBe "archival_memory_search"
            ui[0].toolCalls!!.first().result shouldBe "results A"
            ui[1].toolCalls!!.first().name shouldBe "core_memory_append"
            ui[1].toolCalls!!.first().result shouldBe "OK"
        }

        "promote generated ui tool results into assistant generated ui messages" {
            val messages = listOf(
                TestData.appMessage(
                    id = "m1",
                    messageType = MessageType.TOOL_CALL,
                    content = "{\"title\":\"Today\"}",
                    toolName = "render_summary_card",
                    toolCallId = "tc-ui-1",
                ),
                TestData.appMessage(
                    id = "m2",
                    messageType = MessageType.TOOL_RETURN,
                    content = "{\"type\":\"generated_ui\",\"component\":\"summary_card\",\"props\":{\"title\":\"Today\",\"body\":\"3 tasks pending\"},\"text\":\"Here is your summary\"}",
                    toolName = "render_summary_card",
                    toolCallId = "tc-ui-1",
                ),
            )

            val ui = messages.toUiMessages()
            ui shouldHaveSize 1
            ui[0].role shouldBe "assistant"
            ui[0].content shouldBe "Here is your summary"
            ui[0].generatedUi.shouldNotBeNull()
            ui[0].generatedUi!!.name shouldBe "summary_card"
            ui[0].generatedUi!!.propsJson shouldBe "{\"title\":\"Today\",\"body\":\"3 tasks pending\"}"
        }

        "promote suggestion chip tool results into assistant generated ui messages" {
            val messages = listOf(
                TestData.appMessage(
                    id = "m1",
                    messageType = MessageType.TOOL_CALL,
                    content = "{\"title\":\"Next steps\"}",
                    toolName = "render_suggestion_chips",
                    toolCallId = "tc-ui-2",
                ),
                TestData.appMessage(
                    id = "m2",
                    messageType = MessageType.TOOL_RETURN,
                    content = "{\"type\":\"generated_ui\",\"component\":\"suggestion_chips\",\"props\":{\"title\":\"Next steps\",\"suggestions\":[{\"label\":\"Explain coroutines\",\"message\":\"Explain Kotlin coroutines\"}]},\"text\":\"Choose a follow-up\"}",
                    toolName = "render_suggestion_chips",
                    toolCallId = "tc-ui-2",
                ),
            )

            val ui = messages.toUiMessages()
            ui shouldHaveSize 1
            ui[0].role shouldBe "assistant"
            ui[0].generatedUi.shouldNotBeNull()
            ui[0].generatedUi!!.name shouldBe "suggestion_chips"
            ui[0].content shouldBe "Choose a follow-up"
        }

        "map approval requests to dedicated approval ui messages" {
            val messages = listOf(
                ApprovalRequestMessage(
                    id = "approval-1",
                    toolCalls = listOf(
                        ToolCall(
                            toolCallId = "tool-call-1",
                            name = "Bash",
                            arguments = "{\"command\":\"rm -rf /tmp/demo\"}",
                        )
                    ),
                ).toAppMessage()!!,
            )

            val ui = messages.toUiMessages()

            ui shouldHaveSize 1
            ui[0].role shouldBe "approval"
            ui[0].approvalRequest.shouldNotBeNull()
            ui[0].approvalRequest!!.requestId shouldBe "approval-1"
            ui[0].approvalRequest!!.toolCalls.single().name shouldBe "Bash"
        }

        "map approval responses to dedicated approval ui messages" {
            val messages = listOf(
                ApprovalResponseMessage(
                    id = "approval-response-1",
                    approvalRequestId = "approval-1",
                    approve = false,
                    reason = "Unsafe command",
                    approvals = listOf(
                        ApprovalResult(
                            toolCallId = "tool-call-1",
                            approve = false,
                            status = "rejected",
                            reason = "Unsafe command",
                        )
                    ),
                ).toAppMessage()!!,
            )

            val ui = messages.toUiMessages()

            ui shouldHaveSize 1
            ui[0].role shouldBe "approval"
            ui[0].approvalResponse.shouldNotBeNull()
            ui[0].approvalResponse!!.approved shouldBe false
            ui[0].approvalResponse!!.reason shouldBe "Unsafe command"
        }

        // Regression: bypassPermissions / yolo sessions echo a tool_return as
        // an approval_response_message with approve=null (and per-call
        // approvals[].approve=null). Prior to the fix, the predicate
        //   approval.approved == true || approvals.any { it.approved == true }
        // treated null as "rejected", so EVERY auto-approved Bash call was
        // painted as a Rejected card. We now drop these at the mapper.
        // Repro pulled live from conv-4d764880-... after cutting v0.1.3:
        // 109/109 approval_response_message entries had approve=null.
        "drop auto-approval response messages with no explicit decision" {
            val messages = listOf(
                ApprovalResponseMessage(
                    id = "approval-response-auto-1",
                    approvalRequestId = "approval-1",
                    approve = null,
                    reason = null,
                    approvals = listOf(
                        ApprovalResult(
                            toolCallId = "tool-call-1",
                            approve = null,
                            status = null,
                            reason = null,
                        )
                    ),
                ).toAppMessage()!!,
            )

            val ui = messages.toUiMessages()

            ui shouldHaveSize 0
        }

        "preserve approval response when at least one decision is explicit" {
            val messages = listOf(
                ApprovalResponseMessage(
                    id = "approval-response-mixed-1",
                    approvalRequestId = "approval-1",
                    approve = null,
                    reason = null,
                    approvals = listOf(
                        ApprovalResult(
                            toolCallId = "tool-call-1",
                            approve = true,
                            status = "approved",
                            reason = null,
                        ),
                        ApprovalResult(
                            toolCallId = "tool-call-2",
                            approve = null,
                            status = null,
                            reason = null,
                        ),
                    ),
                ).toAppMessage()!!,
            )

            val ui = messages.toUiMessages()

            ui shouldHaveSize 1
            ui[0].approvalResponse.shouldNotBeNull()
            ui[0].approvalResponse!!.approvals.first().approved shouldBe true
        }

        "preserve approval response when top-level approve is true" {
            val messages = listOf(
                ApprovalResponseMessage(
                    id = "approval-response-true-1",
                    approvalRequestId = "approval-1",
                    approve = true,
                    reason = null,
                    approvals = emptyList(),
                ).toAppMessage()!!,
            )

            val ui = messages.toUiMessages()

            ui shouldHaveSize 1
            ui[0].approvalResponse!!.approved shouldBe true
        }

        // letta-mobile-23h5: Bash/Edit/etc. tool calls in bypassPermissions
        // sessions used to emit THREE UI items for every approved call: the
        // tool-call card, the tool-return, and a standalone "Approved" pill
        // bubble from the matching approval_response_message. The pill had
        // no context (no tool name, no command, no output), just the word
        // "Approved". We now fold the approve=true decision onto the
        // originating tool-call card as a chip and drop the pill bubble.
        "fold approve=true decision onto the owning tool-call card" {
            val messages = listOf(
                TestData.appMessage(
                    id = "tool-call-1",
                    messageType = MessageType.TOOL_CALL,
                    content = "{\"command\":\"ls\"}",
                    toolName = "Bash",
                    toolCallId = "tc-1",
                ),
                ApprovalResponseMessage(
                    id = "approval-response-fold-1",
                    approvalRequestId = "approval-1",
                    approve = null,
                    reason = null,
                    approvals = listOf(
                        ApprovalResult(
                            toolCallId = "tc-1",
                            approve = true,
                            status = "approved",
                            reason = null,
                        )
                    ),
                ).toAppMessage()!!,
            )

            val ui = messages.toUiMessages()

            // Only the tool-call card survives — no redundant "Approved" pill.
            ui shouldHaveSize 1
            ui[0].role shouldBe "tool"
            val toolCall = ui[0].toolCalls!!.single()
            toolCall.name shouldBe "Bash"
            toolCall.approvalDecision shouldBe
                com.letta.mobile.data.model.UiToolApprovalDecision.Approved
        }

        // Rejections are consequential — the user should always see them
        // spelled out. Keep the standalone approval card AND fold the
        // decision onto the tool call so the card's chip shows Rejected too.
        "keep standalone card but still fold decision when approve=false" {
            val messages = listOf(
                TestData.appMessage(
                    id = "tool-call-rej",
                    messageType = MessageType.TOOL_CALL,
                    content = "{\"command\":\"rm -rf /\"}",
                    toolName = "Bash",
                    toolCallId = "tc-rej",
                ),
                ApprovalResponseMessage(
                    id = "approval-response-rej-1",
                    approvalRequestId = "approval-rej",
                    approve = false,
                    reason = "Unsafe command",
                    approvals = listOf(
                        ApprovalResult(
                            toolCallId = "tc-rej",
                            approve = false,
                            status = "rejected",
                            reason = "Unsafe command",
                        )
                    ),
                ).toAppMessage()!!,
            )

            val ui = messages.toUiMessages()

            ui shouldHaveSize 2
            val toolUi = ui.single { it.role == "tool" }
            toolUi.toolCalls!!.single().approvalDecision shouldBe
                com.letta.mobile.data.model.UiToolApprovalDecision.Rejected
            val approvalUi = ui.single { it.approvalResponse != null }
            approvalUi.approvalResponse!!.approved shouldBe false
            approvalUi.approvalResponse!!.reason shouldBe "Unsafe command"
        }

        // When the operator attached a reason to an approve=true (rare but
        // meaningful) we must keep the standalone card so the note is
        // visible. The tool-card chip still shows Approved.
        "keep standalone card when approve=true carried a reason" {
            val messages = listOf(
                TestData.appMessage(
                    id = "tool-call-note",
                    messageType = MessageType.TOOL_CALL,
                    content = "{}",
                    toolName = "Edit",
                    toolCallId = "tc-note",
                ),
                ApprovalResponseMessage(
                    id = "approval-response-note-1",
                    approvalRequestId = "approval-note",
                    approve = true,
                    reason = "Approved because this path is known-safe",
                    approvals = listOf(
                        ApprovalResult(
                            toolCallId = "tc-note",
                            approve = true,
                            status = "approved",
                            reason = null,
                        )
                    ),
                ).toAppMessage()!!,
            )

            val ui = messages.toUiMessages()

            ui shouldHaveSize 2
            val toolUi = ui.single { it.role == "tool" }
            toolUi.toolCalls!!.single().approvalDecision shouldBe
                com.letta.mobile.data.model.UiToolApprovalDecision.Approved
            val approvalUi = ui.single { it.approvalResponse != null }
            approvalUi.approvalResponse!!.reason shouldBe
                "Approved because this path is known-safe"
        }
    }

    "LettaMessage.toAppMessage" should {
        "extract generated ui payloads from assistant content objects" {
            val message = com.letta.mobile.data.model.AssistantMessage(
                id = "assistant-ui-1",
                contentRaw = buildJsonObject {
                    put("type", "generated_ui")
                    put("component", "summary_card")
                    putJsonObject("props") {
                        put("title", "Daily summary")
                        put("body", "3 tasks need attention")
                    }
                    put("text", "Here is your daily summary")
                },
            )

            val appMessage = message.toAppMessage()

            appMessage.shouldNotBeNull()
            appMessage.messageType shouldBe MessageType.ASSISTANT
            appMessage.content shouldBe "Here is your daily summary"
            appMessage.generatedUi.shouldNotBeNull()
            appMessage.generatedUi!!.component shouldBe "summary_card"
            appMessage.generatedUi!!.propsJson shouldBe "{\"title\":\"Daily summary\",\"body\":\"3 tasks need attention\"}"
            appMessage.generatedUi!!.fallbackText shouldBe "Here is your daily summary"
        }

        "suppress raw generated ui json when no fallback text exists" {
            val message = com.letta.mobile.data.model.AssistantMessage(
                id = "assistant-ui-2",
                contentRaw = buildJsonObject {
                    put("type", "generated_ui")
                    put("component", "metric_card")
                    putJsonObject("props") {
                        put("label", "Tasks")
                        put("value", "3")
                    }
                },
            )

            val appMessage = message.toAppMessage()

            appMessage.shouldNotBeNull()
            appMessage.content shouldBe ""
            appMessage.generatedUi.shouldNotBeNull()
            appMessage.generatedUi!!.component shouldBe "metric_card"
        }

        "carry run and step ids from LettaMessage into AppMessage" {
            val message = AssistantMessage(
                id = "assistant-run-1",
                contentRaw = buildJsonObject {
                    put("text", "Grouped assistant message")
                },
                date = "2024-03-15T10:00:00Z",
                runId = "run-hydrated",
                stepId = "step-hydrated",
            )

            val appMessage = message.toAppMessage()

            appMessage.shouldNotBeNull()
            appMessage.runId shouldBe "run-hydrated"
            appMessage.stepId shouldBe "step-hydrated"
        }
    }

    "AppMessage.toUiMessage" should {
        "carry generated ui payloads into chat messages" {
            val uiMsg = TestData.appMessage(
                messageType = MessageType.ASSISTANT,
                content = "Here is your daily summary",
            ).copy(
                generatedUi = com.letta.mobile.data.model.GeneratedUiPayload(
                    component = "summary_card",
                    propsJson = "{\"title\":\"Daily summary\"}",
                    fallbackText = "Here is your daily summary",
                ),
            ).toUiMessage()

            uiMsg.role shouldBe "assistant"
            uiMsg.generatedUi.shouldNotBeNull()
            uiMsg.generatedUi!!.name shouldBe "summary_card"
            uiMsg.generatedUi!!.propsJson shouldBe "{\"title\":\"Daily summary\"}"
        }
    }
})
