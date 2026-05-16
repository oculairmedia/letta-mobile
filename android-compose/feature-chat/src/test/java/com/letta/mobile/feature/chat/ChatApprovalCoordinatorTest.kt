package com.letta.mobile.feature.chat

import com.letta.mobile.data.repository.MessageRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.jupiter.api.Tag

@Tag("unit")
class ChatApprovalCoordinatorTest {
    private val messageRepository: MessageRepository = mockk(relaxed = true)
    private val coordinator = ChatApprovalCoordinator(messageRepository)

    @Test
    fun `submitApproval returns missing active conversation without repository call`() = runTest {
        val result = coordinator.submitApproval(
            activeConversationId = null,
            requestId = "approval-1",
            toolCallIds = listOf("tool-1"),
            approve = true,
            reason = null,
        )

        assertEquals(ChatApprovalResult.MissingActiveConversation, result)
        coVerify(exactly = 0) { messageRepository.submitApproval(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `submitApproval forwards decision to repository`() = runTest {
        val result = coordinator.submitApproval(
            activeConversationId = "conv-1",
            requestId = "approval-1",
            toolCallIds = listOf("tool-1", "tool-2"),
            approve = false,
            reason = "not safe",
        )

        assertEquals(ChatApprovalResult.Submitted, result)
        coVerify(exactly = 1) {
            messageRepository.submitApproval(
                conversationId = "conv-1",
                approvalRequestId = "approval-1",
                toolCallIds = listOf("tool-1", "tool-2"),
                approve = false,
                reason = "not safe",
            )
        }
    }

    @Test
    fun `submitApproval maps repository failure`() = runTest {
        coEvery { messageRepository.submitApproval(any(), any(), any(), any(), any()) } throws IllegalStateException("boom")

        val result = coordinator.submitApproval(
            activeConversationId = "conv-1",
            requestId = "approval-1",
            toolCallIds = listOf("tool-1"),
            approve = true,
            reason = null,
        )

        assertEquals(ChatApprovalResult.Failed("boom"), result)
    }
}
