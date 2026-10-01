package com.letta.mobile.desktop.chat

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import com.letta.mobile.data.chat.projection.ChatMessageListChange
import com.letta.mobile.data.chat.runtime.ChatStreamingPresence
import com.letta.mobile.data.chat.send.ConversationSendQueue
import com.letta.mobile.data.model.MessageContentPart
import com.letta.mobile.data.model.UiApprovalRequest
import com.letta.mobile.data.model.UiMessage
import com.letta.mobile.desktop.defaultDesktopBootstrapState
import com.letta.mobile.ui.chat.render.ConversationState
import com.letta.mobile.ui.chat.session.ChatSurfaceIntent
import com.letta.mobile.ui.chat.session.ChatSurfaceMode
import com.letta.mobile.ui.chat.session.ChatSurfacePresentation
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class DesktopChatSessionPortTest {
    @Test
    fun uiStateMirrorsTheSelectedConversation() = runTest {
        val (controller, port) = startedPort()

        val state = port.uiState.value
        assertEquals(ConversationState.Ready("conv-1"), state.conversationState)
        assertEquals("agent-0", state.agentId)
        assertFalse(state.isLoadingMessages)
        assertTrue(state.messages.any { it.role == "user" && it.content == "Hello from history" })
        assertFalse(state.isStreaming)
        assertFalse(state.isCancelling)
        assertNull(state.error)
        assertTrue(state.a2uiSurfaces.isEmpty())

        controller.close()
    }

    @Test
    fun composerFollowsTheControllerDraftThroughActions() = runTest {
        val (controller, port) = startedPort()

        port.actions.updateComposerText("Draft from the shared page")
        runCurrent()

        val composer = port.composer.value
        assertEquals("Draft from the shared page", composer.text)
        assertEquals("Draft from the shared page", controller.state.value.composerText)
        assertTrue(composer.canSend)
        assertFalse(composer.canQueueWhileStreaming)
        assertNull(composer.workingDirectory)

        controller.close()
    }

    @Test
    fun hostCommandsMapAndRunTheirDesktopAction() = runTest {
        val (controller, port) = startedPort()
        var ran = 0
        port.updateHostInputs(
            DesktopChatComposerHostInputs(
                commands = listOf(
                    ComposerCommand(label = "/canvas", description = "Open canvas", run = { ran++ }),
                    ComposerCommand(label = "/skill", description = "Fill", fillsComposer = true, run = {}),
                ),
                placeholder = "Ask Ada",
            ),
        )
        runCurrent()

        val commands = port.composer.value.commands
        assertEquals(listOf("/canvas", "/skill"), commands.map { it.id })
        assertEquals(listOf(false, true), commands.map { it.fillsComposer })
        assertEquals("Ask Ada", port.composer.value.placeholder)

        port.actions.runComposerCommand(commands.first())
        assertEquals(1, ran)

        controller.close()
    }

    @Test
    fun timelineToggleStateLivesInTheAdapter() = runTest {
        val (controller, port) = startedPort()

        port.actions.toggleRunCollapsed("run-1")
        port.actions.toggleReasoningExpanded("msg-1")
        runCurrent()
        assertEquals(setOf("run-1"), port.uiState.value.collapsedRunIds)
        assertEquals(setOf("msg-1"), port.uiState.value.expandedReasoningMessageIds)

        port.actions.toggleRunCollapsed("run-1")
        runCurrent()
        assertTrue(port.uiState.value.collapsedRunIds.isEmpty())

        controller.close()
    }

    @Test
    fun capabilitiesReflectWhatDesktopSupports() = runTest {
        val (controller, port) = startedPort()

        val capabilities = port.capabilities
        assertTrue(capabilities.attachImages)
        assertFalse(capabilities.rerun)
        assertFalse(capabilities.search)
        assertTrue(capabilities.modelSwitch)
        assertTrue(capabilities.fontScale)
        assertEquals(controller.supportsWorkingDirectory, capabilities.workingDirectory)
        assertFalse(capabilities.pagedHistory, "no canonical presentation in the fake")

        controller.close()
    }

    @Test
    fun fontScaleForwardsToTheShellBinding() = runTest {
        val controller = testController()
        var scale = 0f
        val port = DesktopChatSessionPort(
            controller = controller,
            scope = backgroundScope,
            bindings = DesktopChatSessionBindings(onSetFontScale = { scale = it }),
        )

        port.actions.setFontScale(1.3f)

        assertEquals(1.3f, scale)
        controller.close()
    }

    @Test
    fun cancellingAndPresenceMapForTheSelectedConversationOnly() = runTest {
        val (controller, _) = startedPort()
        val surface = controller.state.value
        val inputs = DesktopChatTimelineInputs(
            surface = surface,
            presence = ChatStreamingPresence(isStreaming = true, isAgentTyping = true),
            cancellingConversationId = "conv-1",
            sendQueue = ConversationSendQueue(),
            local = DesktopChatLocalTimelineState(),
        )

        val selected = desktopChatUiState(inputs, previous = null)
        assertTrue(selected.isCancellingRun)
        assertTrue(selected.isAgentTyping)

        val other = desktopChatUiState(inputs.copy(cancellingConversationId = "conv-other"), previous = null)
        assertFalse(other.isCancelling)

        controller.close()
    }

    @Test
    fun unchangedMessagesKeepTheSameListAndReportNoChange() = runTest {
        val (controller, _) = startedPort()
        val inputs = DesktopChatTimelineInputs(
            surface = controller.state.value,
            presence = ChatStreamingPresence(isStreaming = false, isAgentTyping = false),
            cancellingConversationId = null,
            sendQueue = ConversationSendQueue(),
            local = DesktopChatLocalTimelineState(),
        )

        val first = desktopChatUiState(inputs, previous = null)
        assertEquals(ChatMessageListChange.Full, first.messageListChange)
        val second = desktopChatUiState(inputs, previous = first)

        assertSame(first.messages, second.messages)
        assertEquals(ChatMessageListChange.None, second.messageListChange)
        controller.close()
    }

    @Test
    fun modelChipMatchesOnLabelOrSelectionValue() {
        val options = listOf("Sonnet" to "anthropic/sonnet", "GPT" to "openai/gpt")

        val byValue = desktopChatModelUiState("openai/gpt", options)
        assertEquals("openai/gpt", byValue.currentHandle)
        assertEquals("GPT", byValue.currentLabel)

        val byLabel = desktopChatModelUiState("Sonnet", options)
        assertEquals("anthropic/sonnet", byLabel.currentHandle)

        val unknown = desktopChatModelUiState("Auto", options)
        assertNull(unknown.currentHandle)
        assertEquals("Auto", unknown.currentLabel)
        assertEquals(listOf("anthropic/sonnet", "openai/gpt"), unknown.options.map { it.handle })
    }

    @Test
    fun composerRefusalsGoToTheComposerAndPageErrorsToTheTimeline() = runTest {
        val (controller, port) = startedPort()

        controller.showComposerError(STOPPING_SEND_BLOCKED_MESSAGE)
        runCurrent()
        assertEquals(STOPPING_SEND_BLOCKED_MESSAGE, port.composer.value.error)
        assertNull(port.uiState.value.error)

        port.actions.clearComposerError()
        runCurrent()
        assertNull(port.composer.value.error)

        controller.showComposerError("Message load failed")
        runCurrent()
        assertEquals("Message load failed", port.uiState.value.error)
        assertNull(port.composer.value.error)

        port.actions.clearError()
        runCurrent()
        assertNull(port.uiState.value.error)

        controller.close()
    }

    @Test
    fun theNewestOnScreenApprovalInFlightIsTheActiveOne() {
        val older = approvalMessage("m-1", "req-1")
        val newer = approvalMessage("m-2", "req-2")
        assertNull(submittingApprovalOnScreen(emptySet(), listOf(older, newer)))
        assertEquals("req-1", submittingApprovalOnScreen(setOf("req-1"), listOf(older, newer)))
        assertEquals("req-2", submittingApprovalOnScreen(setOf("req-1", "req-2"), listOf(older, newer)))
        assertNull(submittingApprovalOnScreen(setOf("req-other"), listOf(older, newer)))
    }

    private fun approvalMessage(id: String, requestId: String) = UiMessage(
        id = id,
        role = "assistant",
        content = "",
        timestamp = "2026-10-01T00:00:00Z",
        approvalRequest = UiApprovalRequest(requestId = requestId, toolCalls = emptyList()),
    )

    @Test
    fun sendTextKeepsTheDraftAndStagedImagesOutOfTheStarter() = runTest {
        val (controller, port) = startedPort()
        val image = MessageContentPart.Image(base64 = "aGVsbG8=", mediaType = "image/png")
        port.actions.updateComposerText("half-written thought")
        port.actions.attachImage(image)
        runCurrent()

        port.actions.sendText("How do I get started?")
        runCurrent()

        val state = controller.state.value
        assertEquals("half-written thought", state.composerText)
        assertEquals(listOf(image), state.pendingImageAttachments)
        val sent = state.selectedMessages.last { it.role == "user" }
        assertEquals("How do I get started?", sent.content)
        assertTrue(sent.attachments.isEmpty())

        controller.close()
    }

    @Test
    fun escapeCollapsesOnlyTheFullScreenPage() {
        assertTrue(escapeCollapsesToCanvas(ChatSurfaceMode.FullScreen, Key.Escape, KeyEventType.KeyDown))
        assertFalse(escapeCollapsesToCanvas(ChatSurfaceMode.FullScreen, Key.Escape, KeyEventType.KeyUp))
        assertFalse(escapeCollapsesToCanvas(ChatSurfaceMode.Docked, Key.Escape, KeyEventType.KeyDown))
        assertFalse(escapeCollapsesToCanvas(ChatSurfaceMode.FullScreen, Key.Enter, KeyEventType.KeyDown))
    }

    private fun TestScope.startedPort(): Pair<DesktopChatController, DesktopChatSessionPort> {
        val controller = testController()
        val port = DesktopChatSessionPort(controller = controller, scope = backgroundScope)
        controller.start()
        runCurrent()
        return controller to port
    }

    private fun TestScope.testController(): DesktopChatController =
        DesktopChatController(
            bootstrapState = defaultDesktopBootstrapState(),
            scope = this,
            gatewayFactory = { FakeDesktopChatGateway() },
            timelinePersistence = noOpDesktopTimelinePersistence,
        )
}
