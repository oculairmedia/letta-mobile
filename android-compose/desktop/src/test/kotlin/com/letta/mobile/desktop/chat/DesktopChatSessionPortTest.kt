package com.letta.mobile.desktop.chat

import com.letta.mobile.data.chat.projection.ChatMessageListChange
import com.letta.mobile.data.chat.runtime.ChatStreamingPresence
import com.letta.mobile.data.chat.send.ConversationSendQueue
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
    fun openCanvasFromFullScreenOpensTheCanvasWithoutChangingMode() {
        var opened = 0
        val fullScreen = ChatSurfacePresentation.ChatFirst

        val next = reduceDesktopChatSurfaceIntent(fullScreen, ChatSurfaceIntent.OpenCanvas) { opened++ }

        assertSame(fullScreen, next)
        assertEquals(1, opened)
    }

    @Test
    fun otherIntentsGoThroughTheSharedReducer() {
        var opened = 0
        val collapsed = reduceDesktopChatSurfaceIntent(
            ChatSurfacePresentation.ChatFirst,
            ChatSurfaceIntent.Collapse,
        ) { opened++ }
        assertEquals(ChatSurfaceMode.Docked, collapsed.mode)

        val expanded = reduceDesktopChatSurfaceIntent(collapsed, ChatSurfaceIntent.Expand) { opened++ }
        assertEquals(ChatSurfaceMode.FullScreen, expanded.mode)
        assertEquals(0, opened)
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
