package com.letta.mobile.desktop.chat

import com.letta.mobile.data.chat.runtime.ChatGatewayExtras
import com.letta.mobile.data.chat.runtime.ConversationSummaryUpdate
import com.letta.mobile.data.model.Agent
import com.letta.mobile.data.model.AgentCreateParams
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.AssistantMessage
import com.letta.mobile.data.model.Conversation
import com.letta.mobile.data.model.ConversationId
import com.letta.mobile.data.model.LettaMessage
import com.letta.mobile.data.model.LettaConfig
import com.letta.mobile.data.model.LlmModel
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
    fun startHydratesDefaultShimConversationThroughAgentMessages() = runTest {
        val gateway = FakeDesktopChatGateway(conversationIds = listOf("conv-default-agent-1", "conv-2"))
        val controller = testController(gateway)

        controller.start()
        runCurrent()

        val state = controller.state.value
        assertEquals(listOf("conv-default-agent-1", "conv-2"), state.conversations.map { it.id })
        assertEquals("conv-default-agent-1", state.selectedConversationId)
        assertTrue(state.selectedMessages.any { it.role == "user" && it.content == "Hello from agent-0 history" })
        assertEquals(listOf<Pair<String, String?>>("agent-0" to null), gateway.agentMessageRequests)
        assertEquals(DesktopChatConnectionState.Live, state.connectionState)

        controller.close()
    }

    @Test
    fun selectingAnotherDefaultShimConversationHydratesThroughItsAgentMessages() = runTest {
        val gateway = FakeDesktopChatGateway(
            conversationIds = listOf("conv-default-agent-1", "conv-default-agent-2"),
        )
        val controller = testController(gateway)

        controller.start()
        runCurrent()
        controller.selectConversation("conv-default-agent-2")
        runCurrent()

        val state = controller.state.value
        assertEquals("conv-default-agent-2", state.selectedConversationId)
        assertTrue(state.selectedMessages.any { it.role == "user" && it.content == "Hello from agent-1 history" })
        assertEquals(
            listOf<Pair<String, String?>>(
                "agent-0" to null,
                "agent-1" to null,
            ),
            gateway.agentMessageRequests,
        )
        assertTrue(gateway.conversationMessageRequests.isEmpty())
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
    fun firstSubstantiveSendPersistsStableConversationTitle() = runTest {
        val gateway = FakeExtrasDesktopChatGateway()
        val controller = testController(gateway)

        controller.start()
        runCurrent()
        controller.updateComposerText("Plan the Windows release\nwith a checklist")
        controller.send()
        runCurrent()

        assertEquals(listOf("conv-1" to "Plan the Windows release"), gateway.summaryUpdates)
        assertEquals("Plan the Windows release", controller.state.value.conversations.single().title)
        controller.close()
    }

    @Test
    fun sendMarksConversationThinkingUntilAgentReplyLands() = runTest {
        val gateway = FakeDesktopChatGateway()
        val controller = testController(gateway)

        controller.start()
        runCurrent()
        controller.updateComposerText("hey")
        controller.send()
        // Thinking is set the instant the prompt is sent (before the reply lands).
        assertEquals("conv-1", controller.thinkingConversationId.value)

        runCurrent()
        // Once the agent's reply lands, the thinking indicator clears.
        assertNull(controller.thinkingConversationId.value)
        assertTrue(controller.state.value.selectedMessages.any { it.role == "assistant" })

        controller.close()
    }

    @Test
    fun thinkingPersistsWhileAwaitingAgentReply() = runTest {
        val loop = FakeDesktopTimelineLoop("conv-1").also { it.completeHydrate() }
        val controller = testController(
            gateway = FakeDesktopChatGateway(),
            loopFactory = { _, _, _ -> loop },
        )

        controller.start()
        runCurrent()
        controller.updateComposerText("hey")
        controller.send()
        runCurrent()

        // No assistant reply has streamed back yet, so the conversation stays
        // in the thinking state (no premature clear from isSending).
        assertEquals("conv-1", controller.thinkingConversationId.value)

        controller.close()
    }

    @Test
    fun sendFailureClearsThinking() = runTest {
        val loop = FakeDesktopTimelineLoop("conv-1", sendFailure = IllegalStateException("boom"))
            .also { it.completeHydrate() }
        val controller = testController(
            gateway = FakeDesktopChatGateway(),
            loopFactory = { _, _, _ -> loop },
        )

        controller.start()
        runCurrent()
        controller.updateComposerText("hey")
        controller.send()
        runCurrent()

        assertNull(controller.thinkingConversationId.value)

        controller.close()
    }

    @Test
    fun replyPresenceStreamsWhileSendInFlightThenClears() = runTest {
        val loop = SuspendingSendDesktopLoop("conv-1")
        val controller = testController(
            gateway = FakeDesktopChatGateway(),
            loopFactory = { _, _, _ -> loop },
        )

        controller.start()
        runCurrent()
        // Idle before any send.
        assertFalse(controller.replyPresence.value.isStreaming)

        controller.updateComposerText("hey")
        controller.send()
        runCurrent()
        // The reply stream is in flight (send suspends), so the shared presence
        // policy reports streaming for the selected conversation.
        assertTrue(controller.replyPresence.value.isStreaming)

        loop.releaseSend()
        runCurrent()
        // Send completed → reply stream cleared → presence settles to idle.
        assertFalse(controller.replyPresence.value.isStreaming)

        controller.close()
    }

    @Test
    fun replyPresenceClearsOnceAgentReplyLands() = runTest {
        val controller = testController(FakeDesktopChatGateway())

        controller.start()
        runCurrent()
        controller.updateComposerText("hey")
        controller.send()
        runCurrent()

        // The agent reply landed and the send completed, so presence is idle.
        assertFalse(controller.replyPresence.value.isStreaming)
        assertTrue(controller.state.value.selectedMessages.any { it.role == "assistant" })

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
                override suspend fun listConversations(limit: Int, archiveStatus: String?): List<Conversation> {
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
            override suspend fun listConversations(limit: Int, archiveStatus: String?): List<Conversation> {
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
            loopFactory = { _, conversation, _ ->
                FakeDesktopTimelineLoop(conversation.id)
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
            loopFactory = { _, conversation, _ ->
                FakeDesktopTimelineLoop(conversation.id).also { loops += it }
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
        agentNamesByIdProvider: suspend (Set<String>) -> Map<String, String> = { emptyMap() },
        loopFactory: (
            gateway: DesktopChatGateway,
            conversation: DesktopConversationSummary,
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
        agentNamesByIdProvider: suspend (Set<String>) -> Map<String, String> = { emptyMap() },
    ): DesktopChatController =
        DesktopChatController(
            bootstrapState = defaultDesktopBootstrapState(),
            scope = this,
            gatewayFactory = { gateway },
            agentNamesByIdProvider = agentNamesByIdProvider,
        )

    private fun TestScope.testController(
        gatewayFactory: suspend () -> DesktopChatGateway,
        agentNamesByIdProvider: suspend (Set<String>) -> Map<String, String> = { emptyMap() },
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
    val conversationMessageRequests = mutableListOf<String>()
    val agentMessageRequests = mutableListOf<Pair<String, String?>>()

    override suspend fun listConversations(limit: Int, archiveStatus: String?): List<Conversation> =
        conversationIds.mapIndexed { index, conversationId ->
            Conversation(
                id = ConversationId(conversationId),
                agentId = AgentId("agent-$index"),
                summary = conversationSummary(index),
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
    ): List<LettaMessage> {
        conversationMessageRequests += conversationId
        return listOf(
            UserMessage(
                id = "user-1",
                contentRaw = JsonPrimitive("Hello from history"),
                date = "2026-06-07T01:00:00Z",
            ),
        )
    }

    override suspend fun listAgentMessages(
        agentId: String,
        limit: Int?,
        order: String?,
        conversationId: String?,
    ): List<LettaMessage> {
        agentMessageRequests += agentId to conversationId
        return listOf(
            UserMessage(
                id = "agent-user-$agentId",
                contentRaw = JsonPrimitive("Hello from $agentId history"),
                date = "2026-06-07T01:00:00Z",
            ),
        )
    }

    protected open fun conversationSummary(index: Int): String? =
        if (index == 0) "Remote planning" else "Remote planning $index"
}

private class FakeExtrasDesktopChatGateway : FakeDesktopChatGateway(), ChatGatewayExtras {
    val summaryUpdates = mutableListOf<Pair<String, String>>()

    override fun conversationSummary(index: Int): String = ""

    override suspend fun setConversationSummary(update: ConversationSummaryUpdate): Conversation {
        summaryUpdates += update.conversationId.value to update.summary.value
        return getConversation(update.conversationId.value).copy(summary = update.summary.value)
    }

    override suspend fun createConversation(agentId: String, summary: String?): Conversation =
        getConversation("conv-1")

    override suspend fun createAgent(params: AgentCreateParams): Agent = error("not used")

    override suspend fun listLlmModels(): List<LlmModel> = emptyList()

    override suspend fun setConversationModel(conversationId: String, model: String): Conversation =
        getConversation(conversationId)

    override suspend fun setConversationArchived(conversationId: String, archived: Boolean): Conversation =
        getConversation(conversationId).copy(archived = archived)
}

private class CloseTrackingGateway(
    private val listConversationsBlock: suspend () -> List<Conversation>,
) : FakeDesktopChatGateway(), AutoCloseable {
    var closeCount = 0
        private set

    override suspend fun listConversations(limit: Int, archiveStatus: String?): List<Conversation> =
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

    override suspend fun hydrate(request: DesktopTimelineHydrateRequest) {
        hydrateStarted.complete(Unit)
        hydrateGate.await()
    }

    override suspend fun send(request: DesktopTimelineSendRequest): String {
        sendFailure?.let { throw it }
        sentMessages += request.content.value
        sentAttachments += request.attachments
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

/**
 * A loop whose `send` stays suspended until [releaseSend], so a reply stream can
 * be observed mid-flight (e.g. for streaming-presence assertions). Hydrate
 * completes immediately so the conversation reaches Live.
 */
private class SuspendingSendDesktopLoop(conversationId: String) : DesktopTimelineLoop {
    override val state = MutableStateFlow(Timeline(conversationId))
    private val sendGate = CompletableDeferred<Unit>()
    var closeCount = 0
        private set

    override suspend fun hydrate(request: DesktopTimelineHydrateRequest) = Unit

    override suspend fun send(request: DesktopTimelineSendRequest): String {
        sendGate.await()
        return "client-suspending"
    }

    fun releaseSend() {
        sendGate.complete(Unit)
    }

    override fun close() {
        closeCount++
        sendGate.cancel(CancellationException("closed"))
    }
}

private suspend fun awaitCancellation(): Nothing =
    CompletableDeferred<Nothing>().await()
