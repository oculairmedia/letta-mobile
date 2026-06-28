package com.letta.mobile.data.chat.projection

import com.letta.mobile.data.model.UiApprovalRequest
import com.letta.mobile.data.model.UiMessage
import com.letta.mobile.data.model.UiToolCall
import kotlin.test.Test
import kotlin.test.assertEquals

class RunStepClassificationTest {

    private fun createMessage(
        role: String = "assistant",
        isReasoning: Boolean = false,
        approvalRequest: UiApprovalRequest? = null,
        toolCalls: List<UiToolCall>? = null
    ) = UiMessage(
        id = "test-id",
        role = role,
        content = "test-content",
        timestamp = "2024-01-01T00:00:00Z",
        isReasoning = isReasoning,
        approvalRequest = approvalRequest,
        toolCalls = toolCalls
    )

    @Test
    fun `isReasoning takes highest precedence`() {
        val message = createMessage(
            isReasoning = true,
            approvalRequest = UiApprovalRequest("req", emptyList()), // Should be ignored
            toolCalls = listOf(UiToolCall("tool", "args", null)) // Should be ignored
        )
        assertEquals(StepDotIcon.Reasoning, message.runStepDotIcon())
    }

    @Test
    fun `approvalRequest takes precedence over toolCalls`() {
        val message = createMessage(
            isReasoning = false,
            approvalRequest = UiApprovalRequest("req", emptyList()),
            toolCalls = listOf(UiToolCall("tool", "args", null)) // Should be ignored
        )
        assertEquals(StepDotIcon.Approval, message.runStepDotIcon())
    }

    @Test
    fun `toolCalls returns ToolCall if no reasoning or approval`() {
        val message = createMessage(
            isReasoning = false,
            approvalRequest = null,
            toolCalls = listOf(UiToolCall("tool", "args", null))
        )
        assertEquals(StepDotIcon.ToolCall, message.runStepDotIcon())
    }

    @Test
    fun `assistant role with no tools or reasoning returns AssistantText`() {
        val message = createMessage(
            role = "assistant",
            isReasoning = false,
            approvalRequest = null,
            toolCalls = emptyList() // Also tests empty vs null
        )
        assertEquals(StepDotIcon.AssistantText, message.runStepDotIcon())

        val messageNullTools = createMessage(
            role = "assistant",
            isReasoning = false,
            approvalRequest = null,
            toolCalls = null
        )
        assertEquals(StepDotIcon.AssistantText, messageNullTools.runStepDotIcon())
    }

    @Test
    fun `non-assistant role with no tools or reasoning returns Unknown`() {
        val message = createMessage(
            role = "user",
            isReasoning = false,
            approvalRequest = null,
            toolCalls = null
        )
        assertEquals(StepDotIcon.Unknown, message.runStepDotIcon())
        
        val systemMessage = createMessage(
            role = "system",
            isReasoning = false,
            approvalRequest = null,
            toolCalls = null
        )
        assertEquals(StepDotIcon.Unknown, systemMessage.runStepDotIcon())
    }
}
