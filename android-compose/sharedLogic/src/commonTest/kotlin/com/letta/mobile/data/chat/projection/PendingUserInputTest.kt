package com.letta.mobile.data.chat.projection

import com.letta.mobile.data.model.UiApprovalRequest
import com.letta.mobile.data.model.UiApprovalResponse
import com.letta.mobile.data.model.UiApprovalToolCall
import com.letta.mobile.data.model.UiMessage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** letta-mobile-bglj6.1.22: which approval the Touch canvas offers to answer. */
class PendingUserInputTest {
    @Test
    fun anUnansweredQuestionIsPending() {
        val messages = listOf(prompt(), request("req-1", "AskUserQuestion"))
        assertEquals("req-1", pendingUserInputApproval(messages)?.requestId)
    }

    @Test
    fun aRuntimeResolvedApprovalNeverWaitsOnThePerson() {
        assertNull(pendingUserInputApproval(listOf(prompt(), request("req-1", "Bash"))))
    }

    @Test
    fun aResponseToTheSameRequestAnswersIt() {
        val messages = listOf(request("req-1", "AskUserQuestion"), response("req-1"))
        assertNull(pendingUserInputApproval(messages))
    }

    @Test
    fun aResponseNamingNoRequestAnswersTheNewest() {
        val messages = listOf(request("req-1", "AskUserQuestion"), response(null))
        assertNull(pendingUserInputApproval(messages))
    }

    @Test
    fun aResponseToAnotherRequestLeavesItPending() {
        val messages = listOf(request("req-1", "AskUserQuestion"), response("req-0"))
        assertEquals("req-1", pendingUserInputApproval(messages)?.requestId)
    }

    @Test
    fun theNewestQuestionWins() {
        val messages = listOf(
            request("req-1", "AskUserQuestion"),
            response("req-1"),
            request("req-2", "AskUserQuestion"),
        )
        assertEquals("req-2", pendingUserInputApproval(messages)?.requestId)
    }

    @Test
    fun nothingIsPendingWithoutARequest() {
        assertNull(pendingUserInputApproval(emptyList()))
        assertNull(pendingUserInputApproval(listOf(prompt())))
    }

    private fun prompt() = UiMessage(id = "p", role = "user", content = "hi", timestamp = TIMESTAMP)

    private fun request(id: String, tool: String) = UiMessage(
        id = "m-$id",
        role = "assistant",
        content = "",
        timestamp = TIMESTAMP,
        approvalRequest = UiApprovalRequest(id, listOf(UiApprovalToolCall(toolCallId = "c-$id", name = tool, arguments = "{}"))),
    )

    private fun response(requestId: String?) = UiMessage(
        id = "r-$requestId",
        role = "user",
        content = "",
        timestamp = TIMESTAMP,
        approvalResponse = UiApprovalResponse(requestId = requestId, approved = true),
    )

    private companion object {
        const val TIMESTAMP = "2026-10-07T12:00:00Z"
    }
}
