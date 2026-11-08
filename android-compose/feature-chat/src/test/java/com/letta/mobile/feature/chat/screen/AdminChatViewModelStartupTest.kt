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
import androidx.lifecycle.SavedStateHandle
import com.letta.mobile.data.model.IdFormats
import com.letta.mobile.data.shell.CurrentFocus
import com.letta.mobile.data.shell.CurrentFocusChatMode
import com.letta.mobile.data.shell.FocusAvailability
import com.letta.mobile.data.shell.ShellDestination
import com.letta.mobile.data.shell.ShellWorkspaceState
import com.letta.mobile.feature.chat.route.ChatRouteArgs

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

            // Past the composer's attachment cap, the image is not lost: it waits in staging.
            val limits = com.letta.mobile.data.attachment.AttachmentLimits.Default
            var staged = 0
            while (vm.composerState.value.pendingAttachments.size < limits.maxAttachmentCount) {
                com.letta.mobile.data.canvas.CanvasShare.stageForConversation(
                    target,
                    com.letta.mobile.data.canvas.CanvasShare.createChatImageAttachment("fill-${staged++}".encodeToByteArray(), com.letta.mobile.data.canvas.CanvasMimeType.PNG),
                )
            }
            val overflow = com.letta.mobile.data.canvas.CanvasShare.createChatImageAttachment("overflow".encodeToByteArray(), com.letta.mobile.data.canvas.CanvasMimeType.PNG)
            com.letta.mobile.data.canvas.CanvasShare.stageForConversation(target, overflow)
            assertEquals(limits.maxAttachmentCount, vm.composerState.value.pendingAttachments.size)
            assertEquals(listOf(overflow), com.letta.mobile.data.canvas.CanvasShare.consumeStagedAttachments(target))
        } finally {
            viewModel?.viewModelScope?.cancel()
            com.letta.mobile.data.canvas.CanvasShare.clearStagedAttachments()
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `owner explicit default opens its qualified target despite a stale route handle and never writes settings`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        var model: AdminChatViewModel? = null
        try {
            val agent = TestData.agent("agent-default", "Default")
            val owner = ShellWorkspaceState().apply {
                bindSource("source", 1L)
                navigate(ShellDestination.Chat(agent.id.value, IdFormats.DEFAULT_CONVERSATION_ID))
            }
            val args = ChatRouteArgs(SavedStateHandle(mapOf(
                "agentId" to agent.id.value, "conversationId" to "stale-last", "workspaceOwned" to true,
            )))
            var opens = 0
            val host = ChatPagingHost().apply {
                openCanonical = { targetAgent, targetConversation, _, _ ->
                    assertEquals(agent.id.value, targetAgent)
                    assertEquals(IdFormats.defaultConversationIdFor(agent.id.value), targetConversation)
                    opens++
                    ChatPagingPresentation(flowOf(PagingData.empty()), MutableStateFlow(emptyList()), {})
                }
            }
            val session = FixtureSession("default", focus = owner, routeArgs = args)
            model = openedChatViewModel(host, agent, "stale-last", session)
            assertEquals(1, opens)
            assertEquals(IdFormats.DEFAULT_CONVERSATION_ID, owner.currentFocus.conversationId?.value)
            assertEquals(IdFormats.defaultConversationIdFor(agent.id.value), model.conversationId?.value)
            io.mockk.verify(exactly = 0) { session.settings.setLastChatSelection(any(), any(), any()) }
            owner.reportAvailability(owner.requestToken(), conversation = FocusAvailability.Unavailable)
            assertFalse(model.canSendMessages)
            val before = model.uiState.value
            model.sendMessage("unavailable target must not send")
            assertEquals(before, model.uiState.value)
            assertEquals(IdFormats.DEFAULT_CONVERSATION_ID, owner.currentFocus.conversationId?.value)

        } finally {
            model?.viewModelScope?.cancel()
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `recreated owner new chat ignores stale saved conversation without opening or creating one`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        var model: AdminChatViewModel? = null
        try {
            val agent = TestData.agent("agent-new", "New")
            val owner = ShellWorkspaceState().apply {
                bindSource("source", 1L)
                selectFocus(CurrentFocus("chat", agent.id, chatMode = CurrentFocusChatMode.New))
            }
            val args = ChatRouteArgs(SavedStateHandle(mapOf(
                "agentId" to agent.id.value, "conversationId" to "stale-last", "workspaceOwned" to true,
            )))
            var opens = 0
            val host = ChatPagingHost().apply {
                openCanonical = { _, _, _, _ -> opens++; error("An empty New target must not open a transcript") }
            }
            model = openedChatViewModel(host, agent, "stale-last", FixtureSession("new", focus = owner, routeArgs = args))
            assertEquals(0, opens)
            assertEquals(null, model.conversationId)
            assertEquals(null, owner.currentFocus.conversationId)
            assertEquals(CurrentFocusChatMode.New, owner.currentFocus.chatMode)
        } finally {
            model?.viewModelScope?.cancel()
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `retained vm reconciles completion after save without replaying new chat startup`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        var model: AdminChatViewModel? = null
        try {
            val agent = TestData.agent("agent-new", "New")
            val old = ShellWorkspaceState().apply {
                bindSource("source", 1L)
                selectFocus(CurrentFocus("chat", agent.id, chatMode = CurrentFocusChatMode.New))
            }
            val saved = old.snapshot()
            val args = ChatRouteArgs(SavedStateHandle(mapOf("agentId" to agent.id.value, "workspaceOwned" to true)))
            var opens = 0
            val host = ChatPagingHost().apply {
                openCanonical = { _, _, _, _ ->
                    opens++
                    ChatPagingPresentation(flowOf(PagingData.empty()), MutableStateFlow(emptyList()), {})
                }
            }
            model = openedChatViewModel(host, agent, "resolved", FixtureSession("retained", focus = old, routeArgs = args))
            assertEquals(0, opens)
            assertTrue(args.complete(args.requestToken(), ConversationId("resolved")))
            val restored = ShellWorkspaceState().apply { bindSource("source", 1L); assertTrue(restore(saved)) }
            model.bindWorkspace(restored) {}

            assertEquals(ConversationId("resolved"), restored.currentFocus.conversationId)
            assertEquals(ConversationId("resolved"), model.conversationId)
            assertEquals(CurrentFocusChatMode.Exact, restored.currentFocus.chatMode)
            assertEquals("No startup replay", 0, opens)
        } finally {
            model?.viewModelScope?.cancel()
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `bound startup never independently persists last selection`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        var model: AdminChatViewModel? = null
        try {
            val agent = TestData.agent("agent-writer", "Writer")
            val owner = ShellWorkspaceState().apply {
                bindSource("source", 1L)
                navigate(ShellDestination.Chat(agent.id.value, "writer-conversation"))
            }
            val session = FixtureSession("writer", focus = owner)
            val host = ChatPagingHost().apply {
                openCanonical = { _, _, _, _ ->
                    ChatPagingPresentation(flowOf(PagingData.empty()), MutableStateFlow(emptyList()), {})
                }
            }
            model = openedChatViewModel(host, agent, "writer-conversation", session)
            io.mockk.verify(exactly = 0) { session.settings.setLastChatSelection(any(), any(), any()) }
            assertEquals(ConversationId("writer-conversation"), owner.currentFocus.conversationId)
        } finally {
            model?.viewModelScope?.cancel()
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `retired source cannot clear visibility for an identical id on the new source`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val store = androidx.lifecycle.ViewModelStore()
        try {
            val agent = TestData.agent("agent-source", "Source")
            val owner = ShellWorkspaceState().apply {
                bindSource("source-a", 1L)
                navigate(ShellDestination.Chat(agent.id.value, "same-raw-id"))
            }
            val tracker = com.letta.mobile.data.channel.CurrentConversationTracker()
            val session = FixtureSession("visibility", focus = owner, currentConversationTracker = tracker)
            val host = ChatPagingHost().apply {
                openCanonical = { _, _, _, _ ->
                    ChatPagingPresentation(flowOf(PagingData.empty()), MutableStateFlow(emptyList()), {})
                }
            }
            val model = openedChatViewModel(host, agent, "same-raw-id", session)
            store.put("chat", model)
            owner.bindSource("source-b", 2L)
            owner.navigate(ShellDestination.Chat(agent.id.value, "same-raw-id"))
            tracker.setCurrent("same-raw-id")
            model.onScreenPaused()
            assertEquals("same-raw-id", tracker.current)
            store.clear()
            assertEquals("same-raw-id", tracker.current)
        } finally {
            store.clear()
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
