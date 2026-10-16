package com.letta.mobile.feature.chat

import com.letta.mobile.data.model.UiApprovalRequest
import com.letta.mobile.data.model.UiMessage
import com.letta.mobile.feature.chat.coordination.ChatApprovalController
import com.letta.mobile.feature.chat.coordination.ChatApprovalCoordinator
import com.letta.mobile.data.chat.approval.ChatApprovalResult
import com.letta.mobile.feature.chat.state.ChatBannerController
import com.letta.mobile.ui.chat.render.ChatUiState
import com.letta.mobile.ui.chat.session.ChatApprovalAnswer
import com.letta.mobile.ui.chat.session.currentHostApprovals
import com.letta.mobile.ui.chat.session.withHostApprovals
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.jupiter.api.Tag

/**
 * letta-mobile-2dsi5.4: an answered approval stays in flight until the timeline decides it.
 * letta-mobile-by4vm.93: the answering row is derived by the shared overlay (withHostApprovals) from
 * the controller's [ChatApprovalController.approvals], as on desktop: the bottom-most on-screen row
 * with an answer in flight, null once it is decided, released, or has no row.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Tag("unit")
class ChatApprovalControllerTest {
    private val coordinator: ChatApprovalCoordinator = mockk()
    private val banner: ChatBannerController = mockk(relaxed = true)

    private fun pendingMessage(requestId: String) = UiMessage(
        id = "msg-$requestId",
        role = "assistant",
        content = "",
        timestamp = "2026-09-14T00:00:00Z",
        approvalRequest = UiApprovalRequest(requestId = requestId, toolCalls = emptyList()),
    )

    private fun TestScope.controller(uiState: MutableStateFlow<ChatUiState>) = ChatApprovalController(
        scope = backgroundScope,
        coordinator = coordinator,
        uiState = uiState,
        bannerController = banner,
        agentId = "agent-1",
        activeConversationId = { "conv-1" },
    )

    @Test
    fun `successful submit keeps the card in flight until the timeline decides it`() =
        runTest(UnconfinedTestDispatcher()) {
            coEvery { coordinator.submitApproval(any(), any(), any<ChatApprovalAnswer>()) } returns
                ChatApprovalResult.Submitted
            val uiState = MutableStateFlow(ChatUiState(messages = persistentListOf(pendingMessage("req-1"))))
            val controller = controller(uiState)

            controller.submitApproval("req-1", listOf("tool-1"), approve = true, reason = null)
            runCurrent()

            assertEquals("req-1", controller.answering(uiState))
            assertFalse(uiState.value.isStreaming)

            uiState.update { it.copy(messages = persistentListOf()) }
            runCurrent()

            assertNull(controller.answering(uiState))
            assertEquals(emptySet<String>(), controller.approvals.submittingApprovals.value)
        }

    @Test
    fun `an answer in flight without a row on screen disables nothing`() = runTest(UnconfinedTestDispatcher()) {
        coEvery { coordinator.submitApproval(any(), any(), any<ChatApprovalAnswer>()) } returns ChatApprovalResult.Submitted
        val uiState = MutableStateFlow(ChatUiState(messages = persistentListOf(pendingMessage("req-1"))))
        val controller = controller(uiState)

        controller.submitApproval("req-2", listOf("tool-2"), approve = true, reason = null)
        runCurrent()

        assertEquals(setOf("req-2"), controller.approvals.submittingApprovals.value)
        assertNull(controller.answering(uiState))
    }

    private fun ChatApprovalController.answering(uiState: MutableStateFlow<ChatUiState>) =
        uiState.value.withHostApprovals(approvals.currentHostApprovals()).activeApprovalRequestId

    @Test
    fun `failed submit makes the card actionable again at once`() = runTest(UnconfinedTestDispatcher()) {
        coEvery { coordinator.submitApproval(any(), any(), any<ChatApprovalAnswer>()) } returns
            ChatApprovalResult.Failed("boom")
        val uiState = MutableStateFlow(ChatUiState(messages = persistentListOf(pendingMessage("req-1"))))
        val controller = controller(uiState)

        controller.submitApproval("req-1", listOf("tool-1"), approve = false, reason = null)
        runCurrent()

        assertNull(controller.answering(uiState))
        assertFalse(uiState.value.isStreaming)
        assertFalse(uiState.value.isAgentTyping)
    }
}
