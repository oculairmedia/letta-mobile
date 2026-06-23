package com.letta.mobile.feature.chat.coordination

import com.letta.mobile.data.a2ui.A2uiAction
import com.letta.mobile.data.a2ui.A2uiActionDispatchResult
import com.letta.mobile.data.a2ui.A2uiMessage
import com.letta.mobile.data.a2ui.A2uiSurfaceManager
import com.letta.mobile.data.transport.WsChatBridge
import com.letta.mobile.feature.chat.state.ChatBannerController
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals

class AdminChatA2uiCoordinatorTest {

    @Test
    fun `syncA2uiHistorySnapshot incremental appends use applyMessages`() = runTest {
        val testDispatcher = UnconfinedTestDispatcher(testScheduler)
        val scope = TestScope(testDispatcher)
        val uiState = MutableStateFlow(com.letta.mobile.ui.chat.render.ChatUiState())
        val surfaceManager = mockk<A2uiSurfaceManager>(relaxed = true)
        val coordinator = AdminChatA2uiCoordinator(
            scope = scope,
            uiState = uiState,
            chatBannerController = mockk(),
            chatApprovalController = mockk(),
            wsChatBridge = mockk(relaxed = true),
            activeConversationId = { "conv1" },
            a2uiSurfaceManager = surfaceManager,
            composerCoordinator = mockk()
        )

        val msg1 = mockk<A2uiMessage>()
        val msg2 = mockk<A2uiMessage>()
        val msg3 = mockk<A2uiMessage>()

        val list1 = listOf(msg1)
        val list2 = listOf(msg1, msg2)
        val list3 = listOf(msg1, msg3) // divergent prefix compared to list2

        // First projection: replaceWith
        coordinator.syncA2uiHistorySnapshot("conv1", list1)
        verify(exactly = 1) { surfaceManager.replaceWith(list1) }

        // Second projection (identical list instance but recreated, e.g. same hash): no action
        val list1_dup = listOf(msg1)
        coordinator.syncA2uiHistorySnapshot("conv1", list1_dup)
        verify(exactly = 1) { surfaceManager.replaceWith(any()) }
        verify(exactly = 0) { surfaceManager.applyMessages(any()) }

        // Third projection (appended): applyMessages
        coordinator.syncA2uiHistorySnapshot("conv1", list2)
        verify(exactly = 1) { surfaceManager.applyMessages(listOf(msg2)) }

        // Fourth projection (divergent prefix): replaceWith
        coordinator.syncA2uiHistorySnapshot("conv1", list3)
        verify(exactly = 1) { surfaceManager.replaceWith(list3) }
    }

    @Test
    fun `submitA2uiAction routes tool approval via REST if request id is present`() = runTest {
        val testDispatcher = UnconfinedTestDispatcher(testScheduler)
        val scope = TestScope(testDispatcher)
        val uiState = MutableStateFlow(com.letta.mobile.ui.chat.render.ChatUiState())
        val chatApprovalController = mockk<ChatApprovalController>(relaxed = true)
        val wsChatBridge = mockk<WsChatBridge>(relaxed = true)
        val coordinator = AdminChatA2uiCoordinator(
            scope = scope,
            uiState = uiState,
            chatBannerController = mockk(),
            chatApprovalController = chatApprovalController,
            wsChatBridge = wsChatBridge,
            activeConversationId = { "conv1" },
            a2uiSurfaceManager = mockk(),
            composerCoordinator = mockk()
        )

        val action = A2uiAction(
            name = "tool_approval_response",
            surfaceId = "surface-1",
            context = buildJsonObject {
                put("approvalRequestId", "req-1")
                put("callId", "call-1")
                put("decision", "approve")
            },
            raw = buildJsonObject {}
        )

        coordinator.submitA2uiAction(action)

        verify(exactly = 1) { chatApprovalController.submitApproval("req-1", listOf("call-1"), true, null, "conv1") }
        verify(exactly = 0) { wsChatBridge.sendA2uiAction(any()) }
    }

    @Test
    fun `submitA2uiAction routes tool approval via WS if request id is missing`() = runTest {
        val testDispatcher = UnconfinedTestDispatcher(testScheduler)
        val scope = TestScope(testDispatcher)
        val uiState = MutableStateFlow(com.letta.mobile.ui.chat.render.ChatUiState())
        val chatApprovalController = mockk<ChatApprovalController>(relaxed = true)
        val wsChatBridge = mockk<WsChatBridge>(relaxed = true)
        val coordinator = AdminChatA2uiCoordinator(
            scope = scope,
            uiState = uiState,
            chatBannerController = mockk(),
            chatApprovalController = chatApprovalController,
            wsChatBridge = wsChatBridge,
            activeConversationId = { "conv1" },
            a2uiSurfaceManager = mockk(),
            composerCoordinator = mockk()
        )

        val action = A2uiAction(
            name = "tool_approval_response",
            surfaceId = "surface-1",
            context = buildJsonObject {
                put("callId", "call-1")
                put("decision", "approve")
            },
            raw = buildJsonObject {}
        )

        every { wsChatBridge.sendA2uiAction(any()) } returns A2uiActionDispatchResult.Queued

        coordinator.submitA2uiAction(action)

        verify(exactly = 0) { chatApprovalController.submitApproval(any(), any(), any(), any(), any()) }
        verify(exactly = 1) { wsChatBridge.sendA2uiAction(action.copy(conversationId = "conv1")) }
    }
}
