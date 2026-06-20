package com.letta.mobile.desktop.chat

import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.AssistantMessage
import com.letta.mobile.data.model.Conversation
import com.letta.mobile.data.model.ConversationId
import com.letta.mobile.data.model.LettaMessage
import com.letta.mobile.data.model.LettaConfig
import com.letta.mobile.data.model.MessageCreateRequest
import com.letta.mobile.data.model.MessageContentPart
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
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
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
        assertEquals(DesktopChatConnectionState.Live, state.connectionState)
        assertFalse(state.isLoading)
        assertEquals("conv-1", state.selectedConversationId)
        assertEquals("Remote planning", state.conversations.first().title)
        assertTrue(state.selectedMessages.any { it.role == "user" && it.content == "Hello from history" })

        controller.close()
    }

    @Test
    fun startResolvesAgentNamesForConversationNavigation() = runTest {
        val controller = testController(
            gateway = FakeDesktopChatGateway(),
            agentNamesByIdProvider = { mapOf("agent-0" to "Ada") },
        )

        controller.start()
        runCurrent()

        val conversation = controller.state.value.conversations.single()
        assertEquals("agent-0", conversation.agentId)
        assertEquals("Ada", conversation.agentName)
        assertEquals(listOf("Ada"), controller.state.value.conversationGroups.map { it.agentName })

        controller.close()
    }

    @Test
    fun startIgnoresDefaultShimConversationsWhenSelectingInitialTimeline() = runTest {
        val hydratedConversationIds = mutableListOf<String>()
        val controller = testController(
            gateway = FakeDesktopChatGateway(conversationIds = listOf("conv-default-agent-1", "conv-2")),
            loopFactory = { _, conversationId, _ ->
                hydratedConversationIds += conversationId
                FakeDesktopTimelineLoop(conversationId).also { it.completeHydrate() }
            },
        )

        controller.start()
        runCurrent()

        val state = controller.state.value
        assertEquals(listOf("conv-2"), state.conversations.map { it.id })
        assertEquals("conv-2", state.selectedConversationId)
        assertEquals(listOf("conv-2"), hydratedConversationIds)
        assertEquals(DesktopChatConnectionState.Live, state.connectionState)

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
        assertEquals(DesktopChatConnectionState.Live, state.connectionState)
        val sentMessage = gateway.sentRequests.single().messages?.single() as JsonObject
        assertEquals("user", sentMessage["role"]?.jsonPrimitive?.content)
        assertEquals("Ship live desktop chat", sentMessage["content"]?.jsonPrimitive?.content)
        assertTrue(state.selectedMessages.any { it.role == "assistant" && it.content == "Remote response" })

        controller.close()
    }

    @Test
    fun sendIncludesPendingImageAttachmentInContentPartsAndClearsComposer() = runTest {
        val gateway = FakeDesktopChatGateway()
        val controller = testController(gateway)
        val image = MessageContentPart.Image(base64 = "AAAA", mediaType = "image/png")

        controller.start()
        runCurrent()
        controller.updateComposerText("Describe this")
        controller.attachImage(image)
        controller.send()
        runCurrent()

        val state = controller.state.value
        assertTrue(state.pendingImageAttachments.isEmpty())
        val sentMessage = gateway.sentRequests.single().messages?.single() as JsonObject
        val contentParts = sentMessage["content"]!!.jsonArray
        assertEquals("text", contentParts[0].jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("Describe this", contentParts[0].jsonObject["text"]!!.jsonPrimitive.content)
        assertEquals("image", contentParts[1].jsonObject["type"]!!.jsonPrimitive.content)
        val source = contentParts[1].jsonObject["source"]!!.jsonObject
        assertEquals("base64", source["type"]!!.jsonPrimitive.content)
        assertEquals("image/png", source["media_type"]!!.jsonPrimitive.content)
        assertEquals("AAAA", source["data"]!!.jsonPrimitive.content)

        controller.close()
    }

    @Test
    fun sendFailureRestoresComposerTextAndAttachmentsForRetry() = runTest {
        val image = MessageContentPart.Image(base64 = "BBBB", mediaType = "image/jpeg")
        val loop = FakeDesktopTimelineLoop("conv-1", sendFailure = IllegalStateException("stream send failed"))
            .also { it.completeHydrate() }
        val controller = testController(
            gateway = FakeDesktopChatGateway(),
            loopFactory = { _, _, _ -> loop },
        )

        controller.start()
        runCurrent()
        controller.updateComposerText("try remote send")
        controller.attachImage(image)
        controller.send()
        runCurrent()

        val state = controller.state.value
        assertEquals(DesktopChatConnectionState.SendFailed, state.connectionState)
        assertEquals("try remote send", state.composerText)
        assertEquals(listOf(image), state.pendingImageAttachments)

        controller.close()
    }

    @Test
    fun attachmentLimitViolationsSurfaceComposerError() = runTest {
        val controller = testController(FakeDesktopChatGateway())

        controller.attachImage(MessageContentPart.Image(base64 = "1", mediaType = "image/png"))
        controller.attachImage(MessageContentPart.Image(base64 = "2", mediaType = "image/png"))
        controller.attachImage(MessageContentPart.Image(base64 = "3", mediaType = "image/png"))
        controller.attachImage(MessageContentPart.Image(base64 = "4", mediaType = "image/png"))
        controller.attachImage(MessageContentPart.Image(base64 = "5", mediaType = "image/png"))

        val state = controller.state.value
        assertEquals(4, state.pendingImageAttachments.size)
        assertEquals("Attach up to 4 images.", state.errorMessage)

        controller.close()
    }

    @Test
    fun unavailableBackendShowsOfflineStateWithoutLocalPreview() = runTest {
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
        assertEquals(DesktopChatConnectionState.Offline, state.connectionState)
        assertNotNull(state.errorMessage)
        assertNull(state.selectedConversationId)
        assertTrue(state.conversations.isEmpty())

        controller.close()
    }

    @Test
    fun blankServerUrlShowsConfigNeededWithoutConstructingGateway() = runTest {
        var gatewayConstructed = false
        val controller = DesktopChatController(
            bootstrapState = defaultDesktopBootstrapState(
                config = LettaConfig(
                    id = "blank",
                    mode = LettaConfig.Mode.LOCAL,
                    serverUrl = "",
                ),
            ),
            scope = this,
            gatewayFactory = {
                gatewayConstructed = true
                FakeDesktopChatGateway()
            },
        )

        controller.start()
        runCurrent()

        val state = controller.state.value
        assertEquals(DesktopChatConnectionState.ConfigNeeded, state.connectionState)
        assertFalse(state.isRemoteBacked)
        assertFalse(gatewayConstructed)
        assertTrue(state.conversations.isEmpty())

        controller.close()
    }

    @Test
    fun emptyConversationListShowsNoConversationsState() = runTest {
        val controller = testController(FakeDesktopChatGateway(conversationIds = emptyList()))

        controller.start()
        runCurrent()

        val state = controller.state.value
        assertTrue(state.isRemoteBacked)
        assertFalse(state.isLoading)
        assertEquals(DesktopChatConnectionState.NoConversations, state.connectionState)
        assertNull(state.selectedConversationId)
        assertTrue(state.conversations.isEmpty())

        controller.close()
    }

    @Test
    fun retryConnectionReloadsAfterOfflineFailure() = runTest {
        val gateways = ArrayDeque<DesktopChatGateway>()
        gateways += object : FakeDesktopChatGateway() {
            override suspend fun listConversations(limit: Int): List<Conversation> {
                error("first backend offline")
            }
        }
        gateways += FakeDesktopChatGateway()
        val controller = testController(gatewayFactory = { gateways.removeFirst() })

        controller.start()
        runCurrent()
        assertEquals(DesktopChatConnectionState.Offline, controller.state.value.connectionState)

        controller.retryConnection()
        runCurrent()

        val state = controller.state.value
        assertEquals(DesktopChatConnectionState.Live, state.connectionState)
        assertEquals("conv-1", state.selectedConversationId)
        assertTrue(state.isRemoteBacked)

        controller.close()
    }

    @Test
    fun sendFailureSurfacesSendFailedStateAndKeepsRemoteConversation() = runTest {
        val loop = FakeDesktopTimelineLoop("conv-1", sendFailure = IllegalStateException("stream send failed"))
            .also { it.completeHydrate() }
        val controller = testController(
            gateway = FakeDesktopChatGateway(),
            loopFactory = { _, _, _ -> loop },
        )

        controller.start()
        runCurrent()
        controller.updateComposerText("try remote send")
        controller.send()
        runCurrent()

        val state = controller.state.value
        assertEquals(DesktopChatConnectionState.SendFailed, state.connectionState)
        assertFalse(state.isSending)
        assertNotNull(state.errorMessage)
        assertEquals("conv-1", state.selectedConversationId)
        assertTrue(state.isRemoteBacked)

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
        assertEquals(DesktopChatConnectionState.Loading, loadingState.connectionState)

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
        assertEquals(DesktopChatConnectionState.Loading, controller.state.value.connectionState)
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
        assertEquals(DesktopChatConnectionState.Live, controller.state.value.connectionState)
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
        assertEquals(DesktopChatConnectionState.Loading, controller.state.value.connectionState)
        assertEquals(1, loops.first().closeCount)

        controller.close()
    }

    private fun TestScope.testController(
        gateway: DesktopChatGateway,
        agentNamesByIdProvider: suspend () -> Map<String, String> = { emptyMap() },
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
            agentNamesByIdProvider = agentNamesByIdProvider,
            loopFactory = loopFactory,
        )

    private fun TestScope.testController(
        gateway: DesktopChatGateway,
        agentNamesByIdProvider: suspend () -> Map<String, String> = { emptyMap() },
    ): DesktopChatController =
        DesktopChatController(
            bootstrapState = defaultDesktopBootstrapState(),
            scope = this,
            gatewayFactory = { gateway },
            agentNamesByIdProvider = agentNamesByIdProvider,
        )

    private fun TestScope.testController(
        gatewayFactory: () -> DesktopChatGateway,
        agentNamesByIdProvider: suspend () -> Map<String, String> = { emptyMap() },
    ): DesktopChatController =
        DesktopChatController(
            bootstrapState = defaultDesktopBootstrapState(),
            scope = this,
            gatewayFactory = gatewayFactory,
            agentNamesByIdProvider = agentNamesByIdProvider,
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
    private val sendFailure: Throwable? = null,
) : DesktopTimelineLoop {
    override val state = MutableStateFlow(Timeline(conversationId))
    val hydrateStarted = CompletableDeferred<Unit>()
    val sentMessages = mutableListOf<String>()
    val sentAttachments = mutableListOf<List<MessageContentPart.Image>>()
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
        sendFailure?.let { throw it }
        sentMessages += content
        sentAttachments += attachments
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
