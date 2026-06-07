package com.letta.mobile.desktop.chat

import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.AssistantMessage
import com.letta.mobile.data.model.Conversation
import com.letta.mobile.data.model.ConversationId
import com.letta.mobile.data.model.LettaMessage
import com.letta.mobile.data.model.MessageCreateRequest
import com.letta.mobile.data.model.UserMessage
import com.letta.mobile.data.timeline.Timeline
import com.letta.mobile.data.timeline.TimelineNoActiveRunException
import com.letta.mobile.data.timeline.TimelineStreamFrame
import com.letta.mobile.desktop.defaultDesktopBootstrapState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class DesktopChatControllerTest {
    @Test
    fun startLoadsRemoteConversationsAndHydratesSelectedTimeline() = runTest {
        val controller = testController(FakeDesktopChatGateway())

        controller.start()
        runCurrent()

        val state = controller.state.value
        assertTrue(state.isRemoteBacked)
        assertFalse(state.isLoading)
        assertEquals("conv-1", state.selectedConversationId)
        assertEquals("Remote planning", state.conversations.first().title)
        assertTrue(state.selectedMessages.any { it.role == "user" && it.content == "Hello from history" })

        controller.close()
    }

    @Test
    fun sendUsesRemoteTimelineTransportAndUpdatesMessages() = runTest {
        val gateway = FakeDesktopChatGateway()
        val controller = testController(gateway)

        controller.start()
        runCurrent()
        controller.updateComposerText("Ship live desktop chat")
        controller.send()
        runCurrent()

        val state = controller.state.value
        assertEquals("", state.composerText)
        assertFalse(state.isSending)
        val sentMessage = gateway.sentRequests.single().messages?.single() as JsonObject
        assertEquals("user", sentMessage["role"]?.jsonPrimitive?.content)
        assertEquals("Ship live desktop chat", sentMessage["content"]?.jsonPrimitive?.content)
        assertTrue(state.selectedMessages.any { it.role == "assistant" && it.content == "Remote response" })

        controller.close()
    }

    @Test
    fun unavailableBackendKeepsLocalPreviewAndSurfacesError() = runTest {
        val controller = testController(
            object : FakeDesktopChatGateway() {
                override suspend fun listConversations(limit: Int): List<Conversation> {
                    error("backend offline")
                }
            },
        )

        controller.start()
        runCurrent()

        val state = controller.state.value
        assertFalse(state.isRemoteBacked)
        assertFalse(state.isLoading)
        assertNotNull(state.errorMessage)
        assertEquals("desktop-readiness", state.selectedConversationId)

        controller.close()
    }

    @Test
    fun closeDuringConversationLoadClosesGatewayOnceAndLeavesStateStable() = runTest {
        val gateway = CloseTrackingGateway(
            listConversationsBlock = {
                awaitCancellation()
            },
        )
        val controller = testController(gateway)

        controller.start()
        runCurrent()
        val loadingState = controller.state.value

        controller.close()
        controller.close()
        runCurrent()

        assertEquals(1, gateway.closeCount)
        assertEquals(loadingState, controller.state.value)
    }

    @Test
    fun closeDuringHydrateClosesActiveLoopAndPreventsLateStateMutation() = runTest {
        val loop = FakeDesktopTimelineLoop("conv-1")
        val controller = testController(
            gateway = FakeDesktopChatGateway(),
            loopFactory = { _, _, _ -> loop },
        )

        controller.start()
        runCurrent()
        assertTrue(loop.hydrateStarted.isCompleted)

        controller.close()
        loop.completeHydrate()
        runCurrent()

        assertEquals(1, loop.closeCount)
        assertTrue(controller.state.value.isLoading)
    }

    @Test
    fun selectingNewConversationClosesPreviousLoop() = runTest {
        val loops = mutableListOf<FakeDesktopTimelineLoop>()
        val controller = testController(
            gateway = FakeDesktopChatGateway(conversationIds = listOf("conv-1", "conv-2")),
            loopFactory = { _, conversationId, _ ->
                FakeDesktopTimelineLoop(conversationId)
                    .also { loops += it }
                    .also { it.completeHydrate() }
            },
        )

        controller.start()
        runCurrent()
        controller.selectConversation("conv-2")
        runCurrent()

        assertEquals("conv-2", controller.state.value.selectedConversationId)
        assertEquals(1, loops.first().closeCount)
        assertEquals(0, loops.last().closeCount)

        controller.close()
    }

    @Test
    fun sendAfterCloseIsIgnored() = runTest {
        val loop = FakeDesktopTimelineLoop("conv-1").also { it.completeHydrate() }
        val controller = testController(
            gateway = FakeDesktopChatGateway(),
            loopFactory = { _, _, _ -> loop },
        )

        controller.start()
        runCurrent()
        controller.close()
        controller.updateComposerText("should not send")
        controller.send()
        runCurrent()

        assertTrue(loop.sentMessages.isEmpty())
        assertEquals(1, loop.closeCount)
    }

    @Test
    fun staleSelectionCannotMutateSelectedConversationAfterSwitch() = runTest {
        val loops = mutableListOf<FakeDesktopTimelineLoop>()
        val controller = testController(
            gateway = FakeDesktopChatGateway(conversationIds = listOf("conv-1", "conv-2")),
            loopFactory = { _, conversationId, _ ->
                FakeDesktopTimelineLoop(conversationId).also { loops += it }
            },
        )

        controller.start()
        runCurrent()
        controller.selectConversation("conv-2")
        runCurrent()

        loops.first().completeHydrate()
        runCurrent()

        assertEquals("conv-2", controller.state.value.selectedConversationId)
        assertTrue(controller.state.value.isLoading)
        assertEquals(1, loops.first().closeCount)

        controller.close()
    }

    private fun TestScope.testController(
        gateway: DesktopChatGateway,
        loopFactory: (
            gateway: DesktopChatGateway,
            conversationId: String,
            scope: kotlinx.coroutines.CoroutineScope,
        ) -> DesktopTimelineLoop,
    ): DesktopChatController =
        DesktopChatController(
            bootstrapState = defaultDesktopBootstrapState(),
            scope = this,
            gatewayFactory = { gateway },
            loopFactory = loopFactory,
        )

    private fun TestScope.testController(gateway: DesktopChatGateway): DesktopChatController =
        DesktopChatController(
            bootstrapState = defaultDesktopBootstrapState(),
            scope = this,
            gatewayFactory = { gateway },
        )
}

open class FakeDesktopChatGateway(
    private val conversationIds: List<String> = listOf("conv-1"),
) : DesktopChatGateway {
    val sentRequests = mutableListOf<MessageCreateRequest>()

    override suspend fun listConversations(limit: Int): List<Conversation> =
        conversationIds.mapIndexed { index, conversationId ->
            Conversation(
                id = ConversationId(conversationId),
                agentId = AgentId("agent-$index"),
                summary = if (index == 0) "Remote planning" else "Remote planning $index",
                createdAt = "2026-06-07T01:00:00Z",
                updatedAt = "2026-06-07T01:01:00Z",
                lastMessageAt = "2026-06-07T01:02:00Z",
            )
        }

    override suspend fun getConversation(conversationId: String): Conversation =
        listConversations().first { it.id.value == conversationId }

    override suspend fun sendConversationMessage(
        conversationId: String,
        request: MessageCreateRequest,
    ): Flow<LettaMessage> {
        sentRequests += request
        return flowOf(
            AssistantMessage(
                id = "assistant-1",
                contentRaw = JsonPrimitive("Remote response"),
                date = "2026-06-07T01:03:00Z",
                runId = "run-1",
                stepId = "step-1",
            ),
        )
    }

    override suspend fun streamConversation(conversationId: String): Flow<TimelineStreamFrame> {
        throw TimelineNoActiveRunException(conversationId)
    }

    override suspend fun listConversationMessages(
        conversationId: String,
        limit: Int?,
        after: String?,
        order: String?,
    ): List<LettaMessage> = listOf(
        UserMessage(
            id = "user-1",
            contentRaw = JsonPrimitive("Hello from history"),
            date = "2026-06-07T01:00:00Z",
        ),
    )

    override suspend fun listAgentMessages(
        agentId: String,
        limit: Int?,
        order: String?,
        conversationId: String?,
    ): List<LettaMessage> = emptyList()
}

private class CloseTrackingGateway(
    private val listConversationsBlock: suspend () -> List<Conversation>,
) : FakeDesktopChatGateway(), AutoCloseable {
    var closeCount = 0
        private set

    override suspend fun listConversations(limit: Int): List<Conversation> =
        listConversationsBlock()

    override fun close() {
        closeCount++
    }
}

private class FakeDesktopTimelineLoop(
    conversationId: String,
) : DesktopTimelineLoop {
    override val state = MutableStateFlow(Timeline(conversationId))
    val hydrateStarted = CompletableDeferred<Unit>()
    val sentMessages = mutableListOf<String>()
    var closeCount = 0
        private set

    private val hydrateGate = CompletableDeferred<Unit>()

    override suspend fun hydrate(limit: Int, recordConversationCursor: Boolean) {
        hydrateStarted.complete(Unit)
        hydrateGate.await()
    }

    override suspend fun send(
        content: String,
        attachments: List<com.letta.mobile.data.model.MessageContentPart.Image>,
    ): String {
        sentMessages += content
        return "client-test"
    }

    override fun close() {
        closeCount++
        hydrateGate.cancel(CancellationException("closed"))
    }

    fun completeHydrate() {
        hydrateGate.complete(Unit)
    }
}

private suspend fun awaitCancellation(): Nothing =
    CompletableDeferred<Nothing>().await()
