package com.letta.mobile.feature.chat.screen

import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import com.letta.mobile.data.model.Agent
import com.letta.mobile.data.model.ConversationId
import com.letta.mobile.testutil.TestData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AdminChatViewModelStartupTest {
    @Test
    fun `immediate constructor startup opens and retains canonical presentation`() = runTest {
        // Unlike StandardTestDispatcher, Main.immediate must enter launch bodies
        // during construction, with cached repository results that never suspend.
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        var viewModel: AdminChatViewModel? = null
        try {
            assertFalse(Dispatchers.Main.immediate.isDispatchNeeded(coroutineContext))
            val agent = TestData.agent("agent-startup", "Startup")
            var constructorReturned = false
            var opens = 0
            var closes = 0
            val presentation = ChatPagingPresentation(
                settled = flowOf(PagingData.empty()),
                live = MutableStateFlow(emptyList()),
                close = { closes++ },
            )
            val host = ChatPagingHost().apply {
                openCanonical = { agentId, conversationId, target, _ ->
                    if (opens == 0) {
                        assertFalse("Opening must happen inside the constructor", constructorReturned)
                    } else {
                        assertEquals("Replacing must retire the constructor's job", 1, closes)
                    }
                    assertEquals(agent.id.value, agentId)
                    assertEquals("conversation-startup", conversationId)
                    assertEquals(null, target)
                    opens++
                    presentation
                }
            }
            val vm = openedChatViewModel(host, agent, "conversation-startup", "startup")
            viewModel = vm
            constructorReturned = true
            assertEquals(1, opens)
            assertEquals(ConversationId("conversation-startup"), vm.conversationId)
            assertSame(presentation, vm.pagingPresentation.value)
            assertTrue(presentation.hasBoundRoute)

            // Exercise the initialized viewport map and retained route/job after
            // construction. A late null initializer must not erase the live job.
            presentation.saveViewport(ChatPagingViewport("message-1", 12))
            presentation.clearViewport()
            presentation.requestTail()
            assertEquals(2, opens)
            assertSame(presentation, vm.pagingPresentation.value)
            vm.viewModelScope.cancel()
            assertEquals("Both presentation jobs must remain owned", 2, closes)
        } finally {
            viewModel?.viewModelScope?.cancel()
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `staged canvas attachment delivered to composer pending attachments`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        var viewModel: AdminChatViewModel? = null
        try {
            val agent = TestData.agent("agent-canvas", "CanvasAgent")
            // The chat screen exists before a canvas can be opened from it, and the canvas
            // stages under that screen's own recipient key, never the conversation id.
            val vm = createTestViewModel(agent, "conversation-canvas-share")
            viewModel = vm
            val target = vm.canvasShareRecipient
            assertEquals(0, vm.composerState.value.pendingAttachments.size)

            val sampleImage = com.letta.mobile.data.canvas.CanvasShare.createChatImageAttachment(
                bytes = "canvas-test-image-content".encodeToByteArray(),
                mimeType = com.letta.mobile.data.canvas.CanvasMimeType.PNG,
            )
            com.letta.mobile.data.canvas.CanvasShare.stageForConversation(target, sampleImage)
            assertEquals(1, vm.composerState.value.pendingAttachments.size)
            assertEquals("image/png", vm.composerState.value.pendingAttachments.first().mediaType)

            val secondImage = com.letta.mobile.data.canvas.CanvasShare.createChatImageAttachment(
                bytes = "canvas-second-image".encodeToByteArray(),
                mimeType = com.letta.mobile.data.canvas.CanvasMimeType.PNG,
            )
            com.letta.mobile.data.canvas.CanvasShare.stageForConversation(target, secondImage)
            assertEquals(2, vm.composerState.value.pendingAttachments.size)

            // An image staged for another screen, or under a bare conversation id, is not ours.
            val strangerImage = com.letta.mobile.data.canvas.CanvasShare.createChatImageAttachment(
                bytes = "not-for-this-chat".encodeToByteArray(),
                mimeType = com.letta.mobile.data.canvas.CanvasMimeType.PNG,
            )
            com.letta.mobile.data.canvas.CanvasShare.stageForConversation(
                com.letta.mobile.data.canvas.CanvasConversationTarget("conversation-canvas-share"),
                strangerImage,
            )
            com.letta.mobile.data.canvas.CanvasShare.stageForConversation(
                com.letta.mobile.data.canvas.CanvasConversationTarget("chat-screen-someone-else"),
                strangerImage,
            )
            assertEquals(2, vm.composerState.value.pendingAttachments.size)
        } finally {
            viewModel?.viewModelScope?.cancel()
            com.letta.mobile.data.canvas.CanvasShare.clearStagedAttachments()
            Dispatchers.resetMain()
        }
    }

    private fun createTestViewModel(agent: Agent, convId: String): AdminChatViewModel {
        val presentation = ChatPagingPresentation(
            settled = flowOf(PagingData.empty()),
            live = MutableStateFlow(emptyList()),
            close = { },
        )
        val host = ChatPagingHost().apply {
            openCanonical = { _, _, _, _ -> presentation }
        }
        return openedChatViewModel(
            pagingHost = host,
            agent = agent,
            conversationId = convId,
            tag = "startup",
        )
    }
}
