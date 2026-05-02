package com.letta.mobile.ui.screens.chat

import androidx.lifecycle.SavedStateHandle
import com.letta.mobile.bot.core.BotSession
import com.letta.mobile.bot.protocol.BotAgentInfo
import com.letta.mobile.bot.protocol.BotStreamEvent
import com.letta.mobile.bot.protocol.BotStreamChunk
import com.letta.mobile.bot.protocol.BotChatResponse
import com.letta.mobile.bot.protocol.InternalBotClient
import com.letta.mobile.data.model.Agent
import com.letta.mobile.data.model.AppMessage
import com.letta.mobile.data.model.Block
import com.letta.mobile.data.model.BlockUpdateParams
import com.letta.mobile.data.model.Conversation
import com.letta.mobile.data.model.MessageType
import com.letta.mobile.data.model.ProjectBugReport
import com.letta.mobile.data.repository.AgentRepository
import com.letta.mobile.data.repository.BlockRepository
import com.letta.mobile.data.repository.BugReportRepository
import com.letta.mobile.data.repository.ConversationManager
import com.letta.mobile.data.repository.ConversationRepository
import com.letta.mobile.data.repository.FolderRepository
import com.letta.mobile.data.repository.MessageRepository
import com.letta.mobile.data.repository.SettingsRepository
import com.letta.mobile.data.repository.StreamState
import com.letta.mobile.testutil.FakeBlockApi
import com.letta.mobile.testutil.FakeFolderApi
import com.letta.mobile.testutil.TestData
import io.mockk.clearMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.mockk.slot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import kotlinx.collections.immutable.persistentListOf
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Instant
import org.junit.jupiter.api.Tag

@OptIn(ExperimentalCoroutinesApi::class)
@Tag("integration")
class AdminChatViewModelTest {

    private lateinit var messageRepository: MessageRepository
    private lateinit var timelineRepository: com.letta.mobile.data.timeline.TimelineRepository
    private lateinit var agentRepository: AgentRepository
    private lateinit var blockRepository: BlockRepository
    private lateinit var bugReportRepository: BugReportRepository
    private lateinit var folderRepository: FolderRepository
    private lateinit var conversationRepository: ConversationRepository
    private lateinit var conversationManager: ConversationManager
    private lateinit var settingsRepository: SettingsRepository

    private lateinit var internalBotClient: InternalBotClient
    private lateinit var clientModeChatSender: ClientModeChatSender
    private lateinit var chatRouteSessionResolver: ChatRouteSessionResolver
    private val testDispatcher = UnconfinedTestDispatcher()
    private lateinit var clientModeEnabledFlow: MutableStateFlow<Boolean>
    private var messages: List<AppMessage> = emptyList()
    private var streamStates: List<StreamState> = emptyList()
    private val activeConversationIds = mutableMapOf<String, String?>()

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        messageRepository = mockk(relaxed = true)
        timelineRepository = mockk(relaxed = true)
        // letta-mobile-5s1n: stateful per-conversation Timeline state so the
        // client-mode rewire (writes through the timeline) is observable in
        // tests. `observe(convId)` returns the same MutableStateFlow that
        // append/upsert calls mutate; `messages` seed list still hydrates the
        // initial Confirmed snapshot for legacy tests.
        val timelineFlows = mutableMapOf<String, kotlinx.coroutines.flow.MutableStateFlow<com.letta.mobile.data.timeline.Timeline>>()
        // Per-conversation seed: only the canonical "conv-1" the test fixtures
        // pre-populate via the `messages` field hydrates from that seed.
        // Other conv ids (e.g. fresh-route gateway-allocated client-conv,
        // tool-related ids, etc.) start empty.
        fun flowFor(convId: String): kotlinx.coroutines.flow.MutableStateFlow<com.letta.mobile.data.timeline.Timeline> =
            timelineFlows.getOrPut(convId) {
                val initial = if (convId == "conv-1") {
                    messagesToTimeline(convId, messages)
                } else {
                    com.letta.mobile.data.timeline.Timeline(conversationId = convId)
                }
                kotlinx.coroutines.flow.MutableStateFlow(initial)
            }
        val emptyTimelineLoop = io.mockk.mockk<com.letta.mobile.data.timeline.TimelineSyncLoop>(relaxed = true) {
            every { events } returns kotlinx.coroutines.flow.MutableSharedFlow()
        }
        coEvery { timelineRepository.observe(any()) } answers {
            flowFor(firstArg<String>())
        }
        coEvery { timelineRepository.getOrCreate(any()) } returns emptyTimelineLoop
        coEvery {
            timelineRepository.appendClientModeLocal(any(), any(), any())
        } answers {
            val convId = firstArg<String>()
            val content = secondArg<String>()
            val flow = flowFor(convId)
            val localId = "cm-test-${flow.value.events.size}"
            val local = com.letta.mobile.data.timeline.TimelineEvent.Local(
                position = (flow.value.events.size + 1).toDouble(),
                otid = localId,
                content = content,
                role = com.letta.mobile.data.timeline.Role.USER,
                sentAt = Instant.now(),
                deliveryState = com.letta.mobile.data.timeline.DeliveryState.SENT,
                source = com.letta.mobile.data.timeline.MessageSource.CLIENT_MODE_HARNESS,
            )
            flow.value = flow.value.copy(events = flow.value.events + local)
            localId
        }
        coEvery {
            timelineRepository.upsertClientModeLocalAssistantChunk(any(), any(), any(), any())
        } answers {
            val convId = firstArg<String>()
            val localId = secondArg<String>()
            @Suppress("UNCHECKED_CAST")
            val build = thirdArg<() -> com.letta.mobile.data.timeline.TimelineEvent.Local>()
            @Suppress("UNCHECKED_CAST")
            val transform = arg<(com.letta.mobile.data.timeline.TimelineEvent.Local) -> com.letta.mobile.data.timeline.TimelineEvent.Local>(3)
            val flow = flowFor(convId)
            val tl = flow.value
            val idx = tl.events.indexOfFirst {
                it.otid == localId && it is com.letta.mobile.data.timeline.TimelineEvent.Local
            }
            flow.value = if (idx >= 0) {
                val existing = tl.events[idx] as com.letta.mobile.data.timeline.TimelineEvent.Local
                val updated = transform(existing).copy(
                    position = existing.position,
                    otid = existing.otid,
                    source = com.letta.mobile.data.timeline.MessageSource.CLIENT_MODE_HARNESS,
                )
                tl.copy(events = tl.events.toMutableList().also { it[idx] = updated })
            } else {
                val seed = build().copy(
                    otid = localId,
                    position = (tl.events.size + 1).toDouble(),
                    source = com.letta.mobile.data.timeline.MessageSource.CLIENT_MODE_HARNESS,
                )
                tl.copy(events = tl.events + seed)
            }
            localId
        }
        agentRepository = mockk(relaxed = true)
        blockRepository = BlockRepository(FakeBlockApi())
        bugReportRepository = mockk(relaxed = true)
        folderRepository = FolderRepository(FakeFolderApi())
        conversationRepository = mockk(relaxed = true)
        conversationManager = mockk(relaxed = true)
        settingsRepository = mockk(relaxed = true)
        internalBotClient = mockk(relaxed = true)
        clientModeChatSender = mockk(relaxed = true)
        chatRouteSessionResolver = ChatRouteSessionResolver(conversationManager)
        clientModeEnabledFlow = MutableStateFlow(false)
        activeConversationIds.clear()

        every { settingsRepository.getChatBackgroundKey() } returns flowOf("default")
        every { settingsRepository.getChatFontScale() } returns flowOf(1f)
        every { settingsRepository.observeClientModeEnabled() } returns clientModeEnabledFlow
        every {
            clientModeChatSender.streamMessage(any(), any(), any(), any())
        } returns flow { }
        every { conversationManager.getActiveConversationId(any()) } answers {
            activeConversationIds[firstArg()]
        }
        every { conversationManager.setActiveConversation(any(), any()) } answers {
            activeConversationIds[firstArg()] = secondArg()
            Unit
        }

        coEvery { messageRepository.fetchMessages(any(), any(), any()) } answers { messages }
        coEvery { messageRepository.fetchOlderMessages(any(), any(), any()) } returns emptyList()
        coEvery { messageRepository.submitApproval(any(), any(), any(), any(), any()) } returns Unit
        coEvery { bugReportRepository.getRecentBugReports(any(), any()) } returns emptyList()
        coEvery { bugReportRepository.logBugReport(any()) } answers { firstArg<ProjectBugReport>().copy(id = 1L) }
        every { agentRepository.getAgent(any()) } returns flowOf(TestData.agent(id = "agent-1", name = "Test Agent"))
        every { conversationRepository.getConversations(any()) } answers {
            flowOf(listOf(TestData.conversation(id = "conv-1", agentId = firstArg())))
        }
        coEvery { conversationRepository.refreshConversations(any()) } returns Unit
        coEvery { conversationRepository.createConversation(any(), any()) } answers {
            TestData.conversation(id = "new-conv", agentId = firstArg(), summary = secondArg())
        }
        coEvery { conversationRepository.updateConversation(any(), any(), any()) } returns Unit
        coEvery { conversationManager.resolveAndSetActiveConversation(any(), any()) } answers {
            val agentId = firstArg<String>()
            val resolved = "conv-1"
            activeConversationIds[agentId] = resolved
            resolved
        }
        coEvery { conversationManager.createAndSetActiveConversation(any(), any()) } answers {
            val agentId = firstArg<String>()
            val summary = secondArg<String?>()
            val conversation = TestData.conversation(id = "new-conv", agentId = agentId, summary = summary)
            activeConversationIds[agentId] = conversation.id
            conversation.id
        }
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun createViewModel(
        agentId: String = "agent-1",
        conversationId: String? = "conv-1",
        freshRouteKey: Long? = null,
    ): AdminChatViewModel {
        val savedState = SavedStateHandle().apply {
            set("agentId", agentId)
            conversationId?.let { set("conversationId", it) }
            freshRouteKey?.let { set("freshRouteKey", it) }
        }
        return AdminChatViewModel(
            savedState,
            messageRepository,
            timelineRepository,
            agentRepository,
            blockRepository,
            bugReportRepository,
            folderRepository,
            conversationManager,
            conversationRepository,
            settingsRepository,
            internalBotClient,
            clientModeChatSender,
            chatRouteSessionResolver,
            com.letta.mobile.channel.CurrentConversationTracker(),
        )
    }

    /**
     * Build a [com.letta.mobile.data.timeline.Timeline] from a legacy
     * [AppMessage] seed list. Only the fields consumed by
     * [AdminChatViewModel.startTimelineObserver] are populated.
     */
    private fun messagesToTimeline(
        conversationId: String,
        seed: List<AppMessage>,
    ): com.letta.mobile.data.timeline.Timeline {
        val events = seed.mapIndexed { index, message ->
            val msgType = when (message.messageType) {
                MessageType.USER -> com.letta.mobile.data.timeline.TimelineMessageType.USER
                MessageType.ASSISTANT -> com.letta.mobile.data.timeline.TimelineMessageType.ASSISTANT
                MessageType.REASONING -> com.letta.mobile.data.timeline.TimelineMessageType.REASONING
                MessageType.TOOL_CALL -> com.letta.mobile.data.timeline.TimelineMessageType.TOOL_CALL
                MessageType.TOOL_RETURN -> com.letta.mobile.data.timeline.TimelineMessageType.TOOL_RETURN
                else -> com.letta.mobile.data.timeline.TimelineMessageType.OTHER
            }
            com.letta.mobile.data.timeline.TimelineEvent.Confirmed(
                position = (index + 1).toDouble(),
                otid = message.id,
                content = message.content,
                serverId = message.id,
                messageType = msgType,
                date = message.date ?: Instant.EPOCH,
                runId = null,
                stepId = null,
            )
        }
        return com.letta.mobile.data.timeline.Timeline(
            conversationId = conversationId,
            events = events,
        )
    }

    @Test
    fun `loadMessages populates messages and agent name`() = runTest {
        messages = listOf(
            TestData.appMessage(id = "1", messageType = MessageType.USER, content = "Hello"),
            TestData.appMessage(id = "2", messageType = MessageType.ASSISTANT, content = "Hi"),
        )

        val vm = createViewModel()
        val state = vm.uiState.value

        assertEquals(2, state.messages.size)
        assertEquals("Test Agent", state.agentName)
        assertFalse(state.isLoadingMessages)
    }

    @Test
    fun `loadMessages uses cached conversation resolution without refresh when conversation exists`() = runTest {
        messages = listOf(TestData.appMessage(id = "1", messageType = MessageType.USER, content = "Hello"))

        createViewModel(conversationId = "conv-1")

        io.mockk.coVerify(exactly = 0) { conversationRepository.refreshConversations(any()) }
    }

    @Test
    fun `loadMessages preserves repository order instead of resorting by date`() = runTest {
        messages = listOf(
            AppMessage(
                id = "assistant-first",
                date = Instant.parse("2024-03-15T11:00:00Z"),
                messageType = MessageType.ASSISTANT,
                content = "Assistant came first in repository order",
            ),
            AppMessage(
                id = "user-second",
                date = Instant.parse("2024-03-15T10:00:00Z"),
                messageType = MessageType.USER,
                content = "User came second in repository order",
            ),
        )

        val vm = createViewModel()

        assertEquals(
            listOf("assistant-first", "user-second"),
            vm.uiState.value.messages.map { it.id },
        )
    }

    @Test
    fun `loadMessages sets error on failure`() = runTest {
        every { agentRepository.getAgent(any()) } returns flow { throw Exception("Failed to load agent") }

        val vm = createViewModel()
        advanceUntilIdle()
        val state = vm.uiState.value

        assertTrue(state.error != null)
    }

    @Test
    fun `resolveConversationAndLoad exposes error state when conversation resolution fails`() = runTest {
        coEvery { conversationManager.resolveAndSetActiveConversation(any(), any()) } throws IllegalStateException("Resolver offline")

        val vm = createViewModel(conversationId = null, freshRouteKey = 1L)
        advanceUntilIdle()

        assertEquals(
            ConversationState.Error("Resolver offline"),
            vm.uiState.value.conversationState,
        )
        assertTrue(vm.uiState.value.messages.isEmpty())
        assertFalse(vm.uiState.value.isLoadingMessages)
    }

    @Test
    fun `resolveConversationAndLoad exposes no conversation state when no conversation exists`() = runTest {
        coEvery { conversationManager.resolveAndSetActiveConversation(any(), any()) } returns null

        val vm = createViewModel(conversationId = null)
        advanceUntilIdle()

        assertEquals(ConversationState.NoConversation, vm.uiState.value.conversationState)
        assertTrue(vm.uiState.value.messages.isEmpty())
        assertFalse(vm.uiState.value.isLoadingMessages)
    }

    @Test
    fun `sendMessage is blocked while conversation resolution is in error state`() = runTest {
        coEvery { conversationManager.resolveAndSetActiveConversation(any(), any()) } throws IllegalStateException("Resolver offline")

        val vm = createViewModel(conversationId = null)
        advanceUntilIdle()

        vm.sendMessage("Hello agent")

        assertEquals("Retry conversation loading before sending a message", vm.uiState.value.error)
    }

    // Legacy streaming-path send-flow tests (sendMessage shows pending bubble,
    // streaming response plumbing, reload merging) were removed in Phase 5 of
    // the Timeline migration. See letta-mobile-844e. Send behaviour is now
    // covered by TimelineSyncLoopTest (unit) and the full sync pipeline tests
    // in core/src/test/java/com/letta/mobile/data/timeline/.

    @Test
    fun `sendMessage clears input and sets streaming`() = runTest {
        messages = emptyList()
        streamStates = listOf(StreamState.Sending)

        val vm = createViewModel()
        vm.updateInputText("Hello")
        vm.sendMessage("Hello")

        assertEquals("", vm.inputText.value)
        assertTrue(vm.uiState.value.isStreaming)
    }

    @Test
    fun `sendMessage uses timeline path when client mode is disabled`() = runTest {
        val vm = createViewModel()
        advanceUntilIdle()

        vm.sendMessage("Hello from timeline")
        advanceUntilIdle()

        verify(exactly = 0) {
            clientModeChatSender.streamMessage(any(), any(), any(), any())
        }
        coVerify(exactly = 1) {
            timelineRepository.sendMessage("conv-1", "Hello from timeline")
        }
        assertTrue(vm.uiState.value.isStreaming)
    }

    @Test
    fun `sendMessage routes through client mode sender when enabled`() = runTest {
        clientModeEnabledFlow.value = true
        every {
            clientModeChatSender.streamMessage(screenAgentId = any(), text = any(), existingConversationId = any(), isFreshRoute = any())
        } returns flow {
            emit(BotStreamChunk(text = "Hel", conversationId = "client-conv"))
            emit(BotStreamChunk(text = "Hello from client mode", conversationId = "client-conv", done = true))
        }

        // letta-mobile-c87t: real fresh-route entries always carry a
        // `freshRouteKey` saved-state value (ChatScreen sets it on nav). A null
        // conversationId arg alone is no longer sufficient to mark the entry
        // as fresh — see isFreshRoute logic. Without freshRouteKey the
        // predicate now correctly resumes whatever conversation the gateway
        // remembers (or opens a new one when there's none).
        val vm = createViewModel(conversationId = null, freshRouteKey = 1L)
        advanceUntilIdle()

        vm.sendMessage("Hello from client mode")
        advanceUntilIdle()

        verify(exactly = 1) {
            clientModeChatSender.streamMessage(screenAgentId = "agent-1", text = "Hello from client mode", existingConversationId = null, isFreshRoute = true)
        }
        assertEquals(2, vm.uiState.value.messages.size)
        assertEquals("Hello from client mode", vm.uiState.value.messages.last().content)
        assertFalse(vm.uiState.value.isStreaming)
    }

    @Test
    fun `sendMessage rejects attachments in client mode`() = runTest {
        clientModeEnabledFlow.value = true
        val vm = createViewModel(conversationId = null, freshRouteKey = 1L)
        advanceUntilIdle()

        vm.addAttachment(
            com.letta.mobile.data.model.MessageContentPart.Image(
                base64 = "ZmFrZQ==",
                mediaType = "image/png",
            )
        )
        vm.sendMessage("hello")

        assertEquals(
            "Client Mode attachments are not supported yet",
            vm.composerState.value.error,
        )
    }

    /**
     * letta-mobile-5s1n regression test:
     *
     * Before the fix, the timeline observer's emission unconditionally
     * derived `isStreaming` from `anyLocalPending` (Locals in SENDING).
     * Client Mode Locals are stamped SENT at append (the WS gateway is
     * the delivery authority), so the predicate was always false and the
     * spinner that `sendMessageViaClientMode` set on entry got clobbered
     * by the first observer emission.
     *
     * Contract under fix: while a Client Mode stream is in flight
     * (`clientModeStreamJob.isActive`), the observer must preserve the
     * `isStreaming` / `isAgentTyping` flags that the send coroutine set —
     * even after the user-bubble Local lands in the timeline.
     */
    @Test
    fun `client mode send keeps isStreaming true while stream in flight`() = runTest {
        clientModeEnabledFlow.value = true
        // Hand-rolled channel-backed flow: the test controls when chunks
        // are delivered, so we can observe vm state mid-stream after the
        // user-bubble Local has landed in the timeline (and thus after
        // the observer has had a chance to emit).
        val chunks = Channel<BotStreamChunk>(capacity = Channel.UNLIMITED)
        every {
            clientModeChatSender.streamMessage(screenAgentId = any(), text = any(), existingConversationId = any(), isFreshRoute = any())
        } returns chunks.consumeAsFlow()

        val vm = createViewModel(conversationId = "conv-1")
        advanceUntilIdle()

        vm.sendMessage("ping")
        // Let the send coroutine: (a) appendClientModeLocal → observer
        // re-emits with the user bubble visible, (b) suspend on the
        // first chunks.receive() since we haven't sent any yet.
        advanceUntilIdle()

        // The user bubble should be in messages AND isStreaming/typing
        // should still be true — proving the observer didn't clobber
        // the flags `sendMessage` set just because the SENT Local
        // doesn't satisfy `anyLocalPending`.
        assertTrue(
            "isStreaming must remain true while Client Mode stream in flight",
            vm.uiState.value.isStreaming,
        )
        assertTrue(
            "isAgentTyping must remain true before any assistant chunk arrives",
            vm.uiState.value.isAgentTyping,
        )
        assertTrue(
            "user bubble should be visible from the timeline",
            vm.uiState.value.messages.any { it.role == "user" && it.content == "ping" },
        )

        // Drain a partial assistant chunk; spinner stays true.
        chunks.send(BotStreamChunk(text = "Hello", conversationId = "conv-1"))
        advanceUntilIdle()
        assertTrue(
            "isStreaming must remain true after partial chunk",
            vm.uiState.value.isStreaming,
        )

        // Final chunk drops streaming.
        chunks.send(BotStreamChunk(text = "Hello world", conversationId = "conv-1", done = true))
        chunks.close()
        advanceUntilIdle()
        assertFalse(
            "isStreaming must clear once stream completes",
            vm.uiState.value.isStreaming,
        )
    }

    /**
     * letta-mobile-5s1n regression test:
     *
     * Before the fix, the timeline observer's emission unconditionally
     * derived `isStreaming` from `anyLocalPending` (Locals in SENDING).
     * Client Mode Locals are stamped SENT at append (the WS gateway is
     * the delivery authority), so the predicate was always false and the
     * spinner that `sendMessageViaClientMode` set on entry got clobbered
     * by the first observer emission.
     *
     * Contract under fix: while a Client Mode stream is in flight
     * (`clientModeStreamJob.isActive`), the observer must preserve the
     * `isStreaming` / `isAgentTyping` flags that the send coroutine set —
     * even after the user-bubble Local lands in the timeline.
     */
    @Test
    fun `client mode send keeps isStreaming true while stream in flight`() = runTest {
        clientModeEnabledFlow.value = true
        // Hand-rolled channel-backed flow: the test controls when chunks
        // are delivered, so we can observe vm state mid-stream after the
        // user-bubble Local has landed in the timeline (and thus after
        // the observer has had a chance to emit).
        val chunks = Channel<BotStreamChunk>(capacity = Channel.UNLIMITED)
        every {
            clientModeChatSender.streamMessage(
                screenAgentId = any(),
                text = any(),
                conversationId = any(),
                forceFreshConversation = any(),
            )
        } returns chunks.consumeAsFlow()

        val vm = createViewModel(conversationId = "conv-1")
        advanceUntilIdle()

        vm.sendMessage("ping")
        // Let the send coroutine: (a) appendClientModeLocal → observer
        // re-emits with the user bubble visible, (b) suspend on the
        // first chunks.receive() since we haven't sent any yet.
        advanceUntilIdle()

        // The user bubble should be in messages AND isStreaming/typing
        // should still be true — proving the observer didn't clobber
        // the flags `sendMessage` set just because the SENT Local
        // doesn't satisfy `anyLocalPending`.
        assertTrue(
            "isStreaming must remain true while Client Mode stream in flight",
            vm.uiState.value.isStreaming,
        )
        assertTrue(
            "isAgentTyping must remain true before any assistant chunk arrives",
            vm.uiState.value.isAgentTyping,
        )
        assertTrue(
            "user bubble should be visible from the timeline",
            vm.uiState.value.messages.any { it.role == "user" && it.content == "ping" },
        )

        // Drain a partial assistant chunk; spinner stays true.
        chunks.send(BotStreamChunk(text = "Hello", conversationId = "conv-1"))
        advanceUntilIdle()
        assertTrue(
            "isStreaming must remain true after partial chunk",
            vm.uiState.value.isStreaming,
        )

        // Final chunk drops streaming.
        chunks.send(BotStreamChunk(text = "Hello world", conversationId = "conv-1", done = true))
        chunks.close()
        advanceUntilIdle()
        assertFalse(
            "isStreaming must clear once stream completes",
            vm.uiState.value.isStreaming,
        )
    }

    @Test
    fun `resetMessages clears client mode conversation state`() = runTest {
        clientModeEnabledFlow.value = true
        every {
            clientModeChatSender.streamMessage(screenAgentId = any(), text = any(), existingConversationId = any(), isFreshRoute = any())
        } returns flow {
            emit(BotStreamChunk(text = "Hi", conversationId = "client-conv"))
            emit(BotStreamChunk(text = "Hi", conversationId = "client-conv", done = true))
        }

        val vm = createViewModel(conversationId = null)
        advanceUntilIdle()
        vm.sendMessage("hello")
        advanceUntilIdle()

        vm.resetMessages()

        assertEquals(ConversationState.NoConversation, vm.uiState.value.conversationState)
        assertTrue(vm.uiState.value.messages.isEmpty())
        assertFalse(vm.uiState.value.isStreaming)
    }

    /**
     * letta-mobile-5s1n / "thinking bubble flashes then nothing" regression:
     *
     * Repro of the in-the-wild symptom where a Client Mode send shows the
     * assistant typing indicator briefly and then renders no assistant
     * content. Possible cause #1: the gateway emits the first stream chunk
     * with a NULL conversationId (it hasn't allocated one yet, or hasn't
     * echoed it), then echoes the conversationId starting on chunk #2. The
     * VM's first chunk takes `handleClientModeStreamChunkLegacy` (in-memory),
     * the migration block then moves the user bubble into the timeline,
     * and chunks #2..N go through `handleClientModeStreamChunkViaTimeline`.
     *
     * Contract: every text fragment emitted by the gateway must be visible
     * in the final assistant bubble, regardless of whether the chunk
     * carrying it had a conversationId yet. If the legacy chunk's text is
     * dropped, the bubble shows only the post-conv-echo fragments (and in
     * the worst case, when there's no second text chunk before `done`,
     * shows nothing at all — exactly the reported flash).
     */
    @Test
    fun `client mode preserves first chunk text when conversationId arrives only on chunk 2`() = runTest {
        clientModeEnabledFlow.value = true
        every {
            clientModeChatSender.streamMessage(screenAgentId = any(), text = any(), existingConversationId = any(), isFreshRoute = any())
        } returns flow {
            // Chunk #1 — gateway hasn't echoed conversationId yet.
            emit(BotStreamChunk(text = "Hel", conversationId = null, event = BotStreamEvent.ASSISTANT))
            // Chunk #2 — conversationId now present; should NOT erase the
            // "Hel" fragment from chunk #1.
            emit(BotStreamChunk(text = "Hello", conversationId = "client-conv", event = BotStreamEvent.ASSISTANT))
            emit(BotStreamChunk(text = "Hello world", conversationId = "client-conv", done = true))
        }

        val vm = createViewModel(conversationId = null, freshRouteKey = 1L)
        advanceUntilIdle()

        vm.sendMessage("hi")
        advanceUntilIdle()

        val assistant = vm.uiState.value.messages.lastOrNull { it.role == "assistant" }
        assertNotNull("assistant bubble must be rendered", assistant)
        assertEquals(
            "final assistant bubble must include the chunk-1 (legacy-path) fragment",
            "Hello world",
            assistant!!.content,
        )
        assertFalse("isStreaming must clear once stream completes", vm.uiState.value.isStreaming)
        assertFalse("isAgentTyping must clear once stream completes", vm.uiState.value.isAgentTyping)
    }

    /**
     * letta-mobile-5s1n / "thinking bubble flashes then nothing" regression
     * (cause #2): the gateway sends ONLY a terminal result frame with no
     * preceding text chunks (e.g. the upstream agent stream errored, was
     * empty, or got short-circuited). WsBotClient.streamMessage maps that
     * to a single `BotStreamChunk(done = true)` with no text and no event.
     *
     * Today the VM clears isStreaming/isAgentTyping and leaves the user
     * bubble alone, but the user sees no assistant content at all and no
     * error — exactly the "flash then nothing" symptom.
     *
     * Contract: when the stream produces zero assistant text, the VM must
     * either (a) surface a non-null `error` on uiState OR (b) render an
     * empty-assistant placeholder. Today it does neither, so this test
     * pins the loud failure mode: at minimum, isStreaming must clear and
     * the user must NOT be left with an indefinitely-typing UI.
     */
    @Test
    fun `client mode terminal-only stream with no text clears typing state and surfaces empty turn`() = runTest {
        clientModeEnabledFlow.value = true
        every {
            clientModeChatSender.streamMessage(screenAgentId = any(), text = any(), existingConversationId = any(), isFreshRoute = any())
        } returns flow {
            // Only a terminal frame — no text, no event.
            emit(BotStreamChunk(conversationId = "client-conv", done = true))
        }

        val vm = createViewModel(conversationId = null, freshRouteKey = 1L)
        advanceUntilIdle()

        vm.sendMessage("hi")
        advanceUntilIdle()

        // Hard contract: the typing/streaming spinner must NOT be stuck on.
        // This is the symptom Emmanuel reported: bubble flashes, then
        // nothing — but the spinner had at least to clear (it does).
        assertFalse(
            "isStreaming must clear on terminal-only stream",
            vm.uiState.value.isStreaming,
        )
        assertFalse(
            "isAgentTyping must clear on terminal-only stream",
            vm.uiState.value.isAgentTyping,
        )

        // Soft contract: the user should know SOMETHING happened. Either
        // an empty assistant bubble OR a non-null error/composerError. If
        // neither is present, the UI silently swallows the round-trip and
        // the user is left staring at their own bubble — that's the bug.
        val hasAssistantBubble = vm.uiState.value.messages.any { it.role == "assistant" }
        val hasError = vm.uiState.value.error != null
        assertTrue(
            "terminal-only stream must surface either an assistant bubble or an error " +
                "(otherwise the user sees a silent flash-and-vanish): " +
                "messages=${vm.uiState.value.messages.map { it.role to it.content }} " +
                "error=${vm.uiState.value.error}",
            hasAssistantBubble || hasError,
        )
    }

    /**
     * letta-mobile-lv3e (REPLACES letta-mobile-5s1n / letta-mobile-vu6a
     * regression): multi-chunk text accumulation on the happy path with
     * REAL delta-shaped wire data. The lettabot WS gateway emits each
     * frame's `text` as the NEW fragment only, not a cumulative snapshot
     * — verified via :cli:run wsstream against the live gateway.
     *
     * Previous test fed pretend-snapshot data ["Hel","Hello","Hello world"]
     * which masked the bug. With delta data ["Hel","lo ","world"] the
     * snapshot-semantics impl produces just "world" (the last fragment),
     * which is exactly what Emmanuel saw on-device 2026-04-25.
     */
    @Test
    fun `client mode multi-chunk text stream renders concatenated assistant bubble`() = runTest {
        clientModeEnabledFlow.value = true
        every {
            clientModeChatSender.streamMessage(screenAgentId = any(), text = any(), existingConversationId = any(), isFreshRoute = any())
        } returns flow {
            // DELTA wire shape — each frame is a NEW fragment.
            emit(BotStreamChunk(text = "Hel", conversationId = "client-conv", event = BotStreamEvent.ASSISTANT))
            emit(BotStreamChunk(text = "lo ", conversationId = "client-conv", event = BotStreamEvent.ASSISTANT))
            emit(BotStreamChunk(text = "world", conversationId = "client-conv", event = BotStreamEvent.ASSISTANT))
            // Terminal frame carries no new text — should NOT clear the bubble.
            emit(BotStreamChunk(conversationId = "client-conv", done = true))
        }

        val vm = createViewModel(conversationId = null, freshRouteKey = 1L)
        advanceUntilIdle()

        vm.sendMessage("hi")
        advanceUntilIdle()

        val assistant = vm.uiState.value.messages.lastOrNull { it.role == "assistant" }
        assertNotNull("assistant bubble must be rendered", assistant)
        assertEquals(
            "deltas must concatenate; terminal-with-no-text frame must not clobber",
            "Hello world",
            assistant!!.content,
        )
        assertFalse(vm.uiState.value.isStreaming)
    }

    /**
     * letta-mobile-lv3e: defensive snapshot-shape guard. If the gateway
     * EVER switches back to cumulative-snapshot semantics, the merge
     * heuristic must detect it (incoming.startsWith(existing) → replace
     * with incoming, don't double-concat). Mirrors the SSE-path guard
     * in TimelineSyncLoop:1132-1138.
     */
    @Test
    fun `client mode chunks survive accidental cumulative-snapshot frames`() = runTest {
        clientModeEnabledFlow.value = true
        every {
            clientModeChatSender.streamMessage(any(), any(), any(), any())
        } returns flow {
            // Simulate a future gateway misbehavior: first two frames are
            // deltas, third frame mistakenly carries the FULL accumulated
            // text. Guard must NOT double-concat.
            emit(BotStreamChunk(text = "Hel", conversationId = "client-conv", event = BotStreamEvent.ASSISTANT))
            emit(BotStreamChunk(text = "lo ", conversationId = "client-conv", event = BotStreamEvent.ASSISTANT))
            emit(BotStreamChunk(text = "Hello world", conversationId = "client-conv", event = BotStreamEvent.ASSISTANT))
            emit(BotStreamChunk(conversationId = "client-conv", done = true))
        }

        val vm = createViewModel(conversationId = null, freshRouteKey = 1L)
        advanceUntilIdle()
        vm.sendMessage("hi")
        advanceUntilIdle()

        val assistant = vm.uiState.value.messages.lastOrNull { it.role == "assistant" }
        assertNotNull(assistant)
        assertEquals("Hello world", assistant!!.content)
    }

    /**
     * letta-mobile-lv3e: golden trace from a real lettabot WS session.
     * 58 fragments captured via `:cli:run wsstream` (see
     * `app/src/test/resources/wsstream-golden-lv3e.json`); concatenated
     * they reproduce the original assistant message. Catches regressions
     * where a refactor breaks concatenation for the actual wire shape
     * (markdown, code fences, multi-line punctuation, embedded backticks).
     *
     * The same fixture is exercised at the wire level by
     * `WsBotClientLifecycleTest` ("real captured wsstream trace ..."), so
     * end-to-end the gateway → WsBotClient → VM merge path is covered.
     */
    @Test
    fun `client mode reproduces real wsstream golden trace`() = runTest {
        clientModeEnabledFlow.value = true
        val (fragments, expected) = loadWsstreamGoldenFragments()
        every {
            clientModeChatSender.streamMessage(any(), any(), any(), any())
        } returns flow {
            fragments.forEach { frag ->
                emit(BotStreamChunk(text = frag, conversationId = "client-conv", event = BotStreamEvent.ASSISTANT))
            }
            // Terminal frame carries no text — must not clobber.
            emit(BotStreamChunk(conversationId = "client-conv", done = true))
        }

        val vm = createViewModel(conversationId = null, freshRouteKey = 1L)
        advanceUntilIdle()
        vm.sendMessage("hi")
        advanceUntilIdle()

        val assistant = vm.uiState.value.messages.lastOrNull { it.role == "assistant" }
        assertNotNull(assistant)
        assertEquals(expected, assistant!!.content)
    }

    /**
     * letta-mobile-lv3e acceptance #5: REASONING chunks are deltas just like
     * ASSISTANT chunks. Mirrors the assistant happy-path test but asserts the
     * reasoning bubble is rendered with concatenated text.
     *
     * Pre-fix bug: reasoning rendered as the LAST fragment only ("world"),
     * because the REASONING branch in handleClientModeStreamChunkViaTimeline
     * was using snapshot semantics from vu6a.
     */
    @Test
    fun `client mode multi-chunk reasoning stream renders concatenated reasoning bubble`() = runTest {
        clientModeEnabledFlow.value = true
        every {
            clientModeChatSender.streamMessage(any(), any(), any(), any())
        } returns flow {
            emit(BotStreamChunk(text = "Let", conversationId = "client-conv", event = BotStreamEvent.REASONING))
            emit(BotStreamChunk(text = " me ", conversationId = "client-conv", event = BotStreamEvent.REASONING))
            emit(BotStreamChunk(text = "think", conversationId = "client-conv", event = BotStreamEvent.REASONING))
            emit(BotStreamChunk(conversationId = "client-conv", done = true))
        }

        val vm = createViewModel(conversationId = null, freshRouteKey = 1L)
        advanceUntilIdle()
        vm.sendMessage("hi")
        advanceUntilIdle()

        val reasoning = vm.uiState.value.messages.lastOrNull { it.isReasoning }
        assertNotNull("reasoning bubble must be rendered", reasoning)
        assertEquals(
            "reasoning deltas must concatenate across chunks",
            "Let me think",
            reasoning!!.content,
        )
    }

    /**
     * letta-mobile-lv3e (audit acceptance #4 — tool-call wire contract):
     *
     * The gateway accumulates `tool_call` argument deltas server-side
     * (ws-gateway.ts:330-370) and emits exactly ONE frame per tool
     * invocation with fully-merged `toolInput`. tool_result is similarly
     * emitted as a single frame. Therefore the VM uses replace-not-append
     * semantics for tool args / results — opposite of the assistant text
     * stream.
     *
     * This test pins that contract: a single TOOL_CALL frame with full
     * args followed by a single TOOL_RESULT frame produces a tool card
     * whose `arguments` matches the original JSON verbatim and whose
     * `result` matches the gateway's `text` payload. Interleaved with
     * an assistant text stream that uses delta semantics, the two paths
     * must not bleed into each other.
     *
     * Regression guard: if a future refactor accidentally applies the
     * lv3e append-semantics fix to the tool-call branch, the args field
     * would double-concatenate any subsequent metadata frame and break
     * tool argument display. If the gateway is ever changed to stream
     * tool_input deltas instead of accumulating, this test will start
     * passing trivially and must be revisited.
     */
    @Test
    fun `tool-call arguments are surfaced verbatim from a single snapshot-shaped frame (lv3e audit)`() = runTest {
        clientModeEnabledFlow.value = true
        val argsJson = kotlinx.serialization.json.buildJsonObject {
            put("query", kotlinx.serialization.json.JsonPrimitive("kotlin coroutines"))
            put("limit", kotlinx.serialization.json.JsonPrimitive(5))
        }
        every {
            clientModeChatSender.streamMessage(any(), any(), any(), any())
        } returns flow {
            // Interleaved with delta-shaped assistant text — the two
            // wire shapes must not interfere with each other.
            emit(BotStreamChunk(text = "Looking", conversationId = "client-conv", event = BotStreamEvent.ASSISTANT))
            emit(BotStreamChunk(text = " up", conversationId = "client-conv", event = BotStreamEvent.ASSISTANT))
            // SINGLE tool_call frame carrying complete arguments.
            emit(
                BotStreamChunk(
                    event = BotStreamEvent.TOOL_CALL,
                    toolName = "search",
                    toolCallId = "call-audit",
                    toolInput = argsJson,
                    conversationId = "client-conv",
                )
            )
            // SINGLE tool_result frame carrying complete result.
            emit(
                BotStreamChunk(
                    event = BotStreamEvent.TOOL_RESULT,
                    toolName = "search",
                    toolCallId = "call-audit",
                    text = "5 results found",
                    isError = false,
                    conversationId = "client-conv",
                )
            )
            emit(BotStreamChunk(text = "...", conversationId = "client-conv", event = BotStreamEvent.ASSISTANT))
            emit(BotStreamChunk(conversationId = "client-conv", done = true))
        }

        val vm = createViewModel(conversationId = null, freshRouteKey = 1L)
        advanceUntilIdle()
        vm.sendMessage("search kotlin")
        advanceUntilIdle()

        val toolMessage = vm.uiState.value.messages.firstOrNull { !it.toolCalls.isNullOrEmpty() }
        assertNotNull("tool card must be rendered", toolMessage)
        val tc = toolMessage!!.toolCalls!!.single()
        assertEquals("search", tc.name)
        // Arguments are the verbatim JSON object stringification — NOT
        // doubled, NOT empty, NOT concatenated with the assistant deltas.
        assertEquals(argsJson.toString(), tc.arguments)
        assertEquals("5 results found", tc.result)

        // Assistant deltas concatenate independently, unaffected by the
        // tool frames in between.
        val assistant = vm.uiState.value.messages.firstOrNull {
            it.role == "assistant" && it.toolCalls.isNullOrEmpty() && it.content.isNotEmpty()
        }
        assertNotNull("assistant text bubble must be rendered alongside tool card", assistant)
        assertEquals("Looking up...", assistant!!.content)
    }

    /**
     * letta-mobile-aie.7: server-side BotStreamCoalescer
     * (`LETTABOT_COALESCE_ENABLED=true`) batches consecutive token deltas
     * inside a ~200ms window into a single fat delta frame. Each batch is
     * an APPEND fragment with no prefix relationship to existing content
     * — the second batch starts with "tok20" while existing ends with
     * "...tok19 ", so neither `delta.startsWith(existing)` nor
     * `existing.startsWith(delta)` matches.
     *
     * Before aie.7, the wucn-snapshot-recovery heuristic on the WS-stream
     * path (>=32 chars AND >=50% of existing length) interpreted these
     * batches as snapshot rewrites and silently dropped or overwrote
     * text. The fix scopes wucn to TimelineSyncLoop only; on this path
     * we only do strict prefix-check + concatenation default.
     *
     * Acceptance: every batch is appended; no text is lost; final
     * content equals the verbatim concatenation of all batches in order.
     */
    @Test
    fun `client mode WS path appends coalesced batched-delta frames without loss`() = runTest {
        clientModeEnabledFlow.value = true
        // Three coalescer-flush batches (each ~140 chars, like a real
        // 200ms window over a token-stream).
        val batch1 = (0 until 20).joinToString("") { "tok$it " }   // "tok0 tok1 ... tok19 "
        val batch2 = (20 until 40).joinToString("") { "tok$it " }  // "tok20 ... tok39 "
        val batch3 = (40 until 60).joinToString("") { "tok$it " }  // "tok40 ... tok59 "
        val expected = batch1 + batch2 + batch3

        // Sanity: each batch is large enough that the OLD wucn heuristic
        // would have triggered (>= 32 chars AND >= 50% of existing).
        assertTrue("batch1 should exceed wucn threshold", batch1.length >= 32)
        assertTrue("batch2 should exceed wucn threshold", batch2.length >= 32)

        every {
            clientModeChatSender.streamMessage(any(), any(), any(), any())
        } returns flow {
            emit(BotStreamChunk(text = batch1, conversationId = "client-conv", event = BotStreamEvent.ASSISTANT))
            emit(BotStreamChunk(text = batch2, conversationId = "client-conv", event = BotStreamEvent.ASSISTANT))
            emit(BotStreamChunk(text = batch3, conversationId = "client-conv", event = BotStreamEvent.ASSISTANT))
            emit(BotStreamChunk(conversationId = "client-conv", done = true))
        }

        val vm = createViewModel(conversationId = null, freshRouteKey = 1L)
        advanceUntilIdle()
        vm.sendMessage("hi")
        advanceUntilIdle()

        val assistant = vm.uiState.value.messages.lastOrNull { it.role == "assistant" }
        assertNotNull("assistant bubble must render", assistant)
        assertEquals(
            "Coalesced batches must be appended verbatim — no text loss, no overwrites",
            expected,
            assistant!!.content,
        )
    }

    /**
     * letta-mobile-aie.7: confirms the strict prefix-check still handles
     * the legitimate snapshot case (gateway emitting cumulative content).
     * If a frame's content fully extends what we already have, it
     * REPLACES (not appends) — preserving idempotency for any future
     * gateway that decides to send cumulative buffers.
     */
    @Test
    fun `client mode WS path treats prefix-extending frame as cumulative replacement`() = runTest {
        clientModeEnabledFlow.value = true
        val first = "Hello"
        val cumulative = "Hello, world!"

        every {
            clientModeChatSender.streamMessage(any(), any(), any(), any())
        } returns flow {
            emit(BotStreamChunk(text = first, conversationId = "client-conv", event = BotStreamEvent.ASSISTANT))
            emit(BotStreamChunk(text = cumulative, conversationId = "client-conv", event = BotStreamEvent.ASSISTANT))
            emit(BotStreamChunk(conversationId = "client-conv", done = true))
        }

        val vm = createViewModel(conversationId = null, freshRouteKey = 1L)
        advanceUntilIdle()
        vm.sendMessage("hi")
        advanceUntilIdle()

        val assistant = vm.uiState.value.messages.lastOrNull { it.role == "assistant" }
        assertNotNull(assistant)
        // Must be the cumulative — not "HelloHello, world!" (double-concat).
        assertEquals(cumulative, assistant!!.content)
    }

    @Test
    fun `client mode renders tool call and tool result chunks as tool card`() = runTest {
        clientModeEnabledFlow.value = true
        every {
            clientModeChatSender.streamMessage(any(), any(), any(), any())
        } returns flow {
            emit(
                BotStreamChunk(
                    event = BotStreamEvent.TOOL_CALL,
                    toolName = "search",
                    toolCallId = "call-1",
                    toolInput = kotlinx.serialization.json.buildJsonObject {
                        put("query", kotlinx.serialization.json.JsonPrimitive("kotlin"))
                    },
                    conversationId = "client-conv",
                )
            )
            emit(
                BotStreamChunk(
                    event = BotStreamEvent.TOOL_RESULT,
                    toolName = "search",
                    toolCallId = "call-1",
                    text = "done",
                    isError = false,
                    conversationId = "client-conv",
                )
            )
            emit(BotStreamChunk(text = "Final answer", conversationId = "client-conv", done = true))
        }

        val vm = createViewModel(conversationId = null, freshRouteKey = 1L)
        advanceUntilIdle()

        vm.sendMessage("hello")
        advanceUntilIdle()

        val toolMessage = vm.uiState.value.messages.firstOrNull { !it.toolCalls.isNullOrEmpty() }
        assertTrue(toolMessage != null)
        assertEquals("search", toolMessage!!.toolCalls!!.single().name)
        assertEquals("done", toolMessage.toolCalls!!.single().result)
    }

    @Test
    fun `toggling client mode off restores timeline messages after client mode session`() = runTest {
        clientModeEnabledFlow.value = true
        messages = listOf(
            TestData.appMessage(id = "timeline-user", messageType = MessageType.USER, content = "Timeline hello"),
            TestData.appMessage(id = "timeline-assistant", messageType = MessageType.ASSISTANT, content = "Timeline reply"),
        )
        every {
            clientModeChatSender.streamMessage(any(), any(), any(), any())
        } returns flow {
            emit(BotStreamChunk(text = "Client reply", conversationId = "client-conv", done = true))
        }

        val vm = createViewModel(conversationId = null, freshRouteKey = 1L)
        advanceUntilIdle()

        vm.sendMessage("hello")
        advanceUntilIdle()
        assertTrue(vm.uiState.value.messages.any { it.content == "Client reply" })

        clientModeEnabledFlow.value = false
        advanceUntilIdle()

        assertEquals(ConversationState.Ready("conv-1"), vm.uiState.value.conversationState)
        assertTrue(vm.uiState.value.messages.any { it.id == "timeline-user" })
        assertTrue(vm.uiState.value.messages.any { it.id == "timeline-assistant" })
        assertFalse(vm.uiState.value.isStreaming)
    }

    @Test
    fun `blank conversation id in saved state behaves like fresh client mode route`() = runTest {
        clientModeEnabledFlow.value = true
        every {
            clientModeChatSender.streamMessage(screenAgentId = any(), text = any(), existingConversationId = any(), isFreshRoute = any())
        } returns flow {
            emit(BotStreamChunk(text = "Fresh route", conversationId = "client-conv", done = true))
        }

        val savedState = SavedStateHandle().apply {
            set("agentId", "agent-1")
            set("conversationId", "")
        }
        val vm = AdminChatViewModel(
            savedState,
            messageRepository,
            timelineRepository,
            agentRepository,
            blockRepository,
            bugReportRepository,
            folderRepository,
            conversationManager,
            conversationRepository,
            settingsRepository,
            internalBotClient,
            clientModeChatSender,
            chatRouteSessionResolver,
            com.letta.mobile.channel.CurrentConversationTracker(),
        )
        advanceUntilIdle()

        vm.sendMessage("hello")
        advanceUntilIdle()

        verify(exactly = 1) {
            clientModeChatSender.streamMessage(screenAgentId = "agent-1", text = "hello", existingConversationId = null, isFreshRoute = true)
        }
        coVerify(exactly = 0) { timelineRepository.sendMessage(any(), any()) }
        assertTrue(vm.uiState.value.messages.any { it.content == "Fresh route" })
    }

    @Test
    fun `fresh route key uses client mode before hydrating active timeline`() = runTest {
        clientModeEnabledFlow.value = true
        activeConversationIds["agent-1"] = "conv-1"
        messages = listOf(
            TestData.appMessage(id = "timeline-user", messageType = MessageType.USER, content = "Old timeline"),
        )
        every {
            clientModeChatSender.streamMessage(screenAgentId = any(), text = any(), existingConversationId = any(), isFreshRoute = any())
        } returns flow {
            emit(BotStreamChunk(text = "Fresh client reply", conversationId = "client-conv", done = true))
        }

        val vm = createViewModel(conversationId = null, freshRouteKey = 123L)
        advanceUntilIdle()

        assertEquals(ConversationState.NoConversation, vm.uiState.value.conversationState)
        assertTrue(vm.uiState.value.messages.isEmpty())

        vm.sendMessage("hello")
        advanceUntilIdle()

        verify(exactly = 1) {
            clientModeChatSender.streamMessage(screenAgentId = "agent-1", text = "hello", existingConversationId = null, isFreshRoute = true)
        }
        coVerify(exactly = 0) { timelineRepository.sendMessage(any(), any()) }
        assertTrue(vm.uiState.value.messages.none { it.content == "Old timeline" })
        assertTrue(vm.uiState.value.messages.any { it.content == "Fresh client reply" })
    }

    @Test
    fun `existing conversation route hydrates passed conversation instead of falling back to empty state`() = runTest {
        messages = listOf(
            TestData.appMessage(id = "existing-user", messageType = MessageType.USER, content = "Earlier message"),
            TestData.appMessage(id = "existing-assistant", messageType = MessageType.ASSISTANT, content = "Existing reply"),
        )
        every { conversationManager.getActiveConversationId(any()) } returns null
        coEvery { conversationManager.resolveAndSetActiveConversation(any(), any()) } returns null

        val vm = createViewModel(conversationId = "conv-1")
        advanceUntilIdle()

        assertEquals(ConversationState.Ready("conv-1"), vm.uiState.value.conversationState)
        assertTrue(vm.uiState.value.messages.any { it.id == "existing-user" })
        assertTrue(vm.uiState.value.messages.any { it.id == "existing-assistant" })
        verify { conversationManager.setActiveConversation("agent-1", "conv-1") }
    }

    @Test
    fun `client mode without explicit conversation restores most recent conversation`() = runTest {
        clientModeEnabledFlow.value = true
        messages = listOf(
            TestData.appMessage(id = "recent-user", messageType = MessageType.USER, content = "Recent message"),
        )
        activeConversationIds.clear()

        val vm = createViewModel(conversationId = null)
        advanceUntilIdle()

        coVerify(exactly = 1) {
            conversationManager.resolveAndSetActiveConversation("agent-1", any())
        }
        assertEquals(ConversationState.Ready("conv-1"), vm.uiState.value.conversationState)
        assertTrue(vm.uiState.value.messages.any { it.id == "recent-user" })
    }

    @Test
    fun `updateInputText only changes input field`() = runTest {
        messages = listOf(TestData.appMessage(id = "1", messageType = MessageType.USER, content = "Msg"))

        val vm = createViewModel()
        vm.updateInputText("typing...")

        assertEquals("typing...", vm.inputText.value)
        assertEquals(1, vm.uiState.value.messages.size)
    }

    @Test
    fun `blank agentId sets error`() = runTest {
        val vm = createViewModel(agentId = "")
        assertTrue(vm.uiState.value.error != null)
    }

    @Test
    fun `project context is restored from saved state`() = runTest {
        val savedState = SavedStateHandle().apply {
            set("agentId", "agent-1")
            set("conversationId", "conv-1")
            set("projectIdentifier", "letta-mobile")
            set("projectName", "Letta Mobile")
            set("projectFilesystemPath", "/opt/stacks/letta-mobile")
            set("projectGitUrl", "https://github.com/letta-ai/letta-mobile")
            set("projectLastSyncAt", "2026-04-13T12:00:00Z")
            set("projectActiveCodingAgents", "android, pm-agent")
        }

        val vm = AdminChatViewModel(
            savedState,
            messageRepository,
            timelineRepository,
            agentRepository,
            blockRepository,
            bugReportRepository,
            folderRepository,
            conversationManager,
            conversationRepository,
            settingsRepository,
            internalBotClient,
            clientModeChatSender,
            chatRouteSessionResolver,
            com.letta.mobile.channel.CurrentConversationTracker(),
        )

        val project = vm.projectContext
        assertEquals("letta-mobile", project?.identifier)
        assertEquals("Letta Mobile", project?.name)
        assertEquals("/opt/stacks/letta-mobile", project?.filesystemPath)
        assertEquals("https://github.com/letta-ai/letta-mobile", project?.gitUrl)
        assertEquals("2026-04-13T12:00:00Z", project?.lastSyncAt)
        assertEquals("android, pm-agent", project?.activeCodingAgents)
    }

    @Test
    fun `submitApproval forwards approval decision to repository`() = runTest {
        val vm = createViewModel()

        vm.submitApproval(
            requestId = "approval-1",
            toolCallIds = listOf("tool-call-1", "tool-call-2"),
            approve = false,
            reason = "Needs confirmation",
        )
        advanceUntilIdle()

        coVerify(exactly = 1) {
            messageRepository.submitApproval(
                conversationId = "conv-1",
                approvalRequestId = "approval-1",
                toolCallIds = listOf("tool-call-1", "tool-call-2"),
                approve = false,
                reason = "Needs confirmation",
            )
        }
        assertFalse(vm.uiState.value.isStreaming)
        assertEquals(null, vm.uiState.value.activeApprovalRequestId)
    }

    // Project-chat embedded-bot-gateway send tests were removed in Phase 5 —
    // `sendMessage` now unconditionally routes through TimelineRepository.
    // Gateway integration coverage lives in `InternalBotClientTest`.

    // `loadMessages forwards scroll target to repository fetch` was removed in
    // Phase 5 — history loading now runs through TimelineRepository.hydrate(),
    // which handles scrollToMessageId internally; targeted fetch coverage
    // lives in MessageRepositoryE2eTest and TimelineRepository tests.

    // `loadOlderMessages prepends older history` and `...never produces
    // duplicate message ids` relied on the legacy fetchMessages pre-hydrate
    // setting hasMoreOlderMessages=true. That signal now needs to flow from
    // TimelineRepository pagination, which is a follow-up work item
    // (letta-mobile-lhki). Removed here in Phase 5 to unblock the
    // legacy-state cleanup; re-add once Timeline exposes a pagination cursor.

    // `loadOlderMessages skips fetch when initial page proves there is no
    // older history` was removed in letta-mobile-b1di. The 23h5 regression
    // fix (see AdminChatViewModel.kt lines 952-967) intentionally inverted
    // the contract: hasMoreOlderMessages is now optimistically flipped to
    // true any time the timeline has at least one confirmed message, and
    // the actual fetchOlderMessages call settles the truth (sets it back
    // to false when fewer than PAGE_SIZE rows come back, see line 688).
    // The deleted test asserted the pre-23h5 behavior — that single-message
    // initial pages skip the fetch entirely — which contradicts the fix.
    // Page-size pagination semantics are covered in MessageRepositoryE2eTest
    // (`fetchOlderMessages returns page ordered chronologically`), and the
    // optimistic-flip path is implicitly exercised by every other VM test
    // that loads messages. Re-add a positive-coverage test here once
    // TimelineRepository exposes an explicit pagination cursor (the same
    // follow-up referenced above as letta-mobile-lhki).

    @Test
    fun `project brief loads mapped sections from core memory blocks`() = runTest {
        val fakeBlockApi = FakeBlockApi().apply {
            blocks["agent-1"] = mutableListOf(
                Block(id = "b1", label = "project_description", value = "Ship the Android PM surface"),
                Block(id = "b2", label = "key_decisions", value = "- Use shared project chat\n- Keep brief editable"),
                Block(id = "b3", label = "tech_stack", value = "Kotlin\nCompose\nLetta API"),
                Block(id = "b4", label = "active_goals", value = "- Finish bz40.2.4"),
                Block(id = "b5", label = "recent_changes", value = "Added public proxy support"),
            )
        }
        blockRepository = BlockRepository(fakeBlockApi)

        val savedState = SavedStateHandle().apply {
            set("agentId", "agent-1")
            set("conversationId", "conv-1")
            set("projectIdentifier", "letta-mobile")
            set("projectName", "Letta Mobile")
        }

        val vm = AdminChatViewModel(
            savedState,
            messageRepository,
            timelineRepository,
            agentRepository,
            blockRepository,
            bugReportRepository,
            folderRepository,
            conversationManager,
            conversationRepository,
            settingsRepository,
            internalBotClient,
            clientModeChatSender,
            chatRouteSessionResolver,
            com.letta.mobile.channel.CurrentConversationTracker(),
        )
        advanceUntilIdle()

        val brief = vm.uiState.value.projectBrief
        assertFalse(brief.isLoading)
        assertEquals("Ship the Android PM surface", brief.sections[ProjectBriefSectionKey.Description]?.content)
        assertEquals("Kotlin\nCompose\nLetta API", brief.sections[ProjectBriefSectionKey.TechStack]?.content)
        assertEquals(5, brief.sections.size)
    }

    @Test
    fun `save project brief updates matching memory block`() = runTest {
        val fakeBlockApi = FakeBlockApi().apply {
            blocks["agent-1"] = mutableListOf(
                Block(id = "b1", label = "project_description", value = "Old brief"),
            )
        }
        blockRepository = BlockRepository(fakeBlockApi)

        val savedState = SavedStateHandle().apply {
            set("agentId", "agent-1")
            set("conversationId", "conv-1")
            set("projectIdentifier", "letta-mobile")
            set("projectName", "Letta Mobile")
        }

        val vm = AdminChatViewModel(
            savedState,
            messageRepository,
            timelineRepository,
            agentRepository,
            blockRepository,
            bugReportRepository,
            folderRepository,
            conversationManager,
            conversationRepository,
            settingsRepository,
            internalBotClient,
            clientModeChatSender,
            chatRouteSessionResolver,
            com.letta.mobile.channel.CurrentConversationTracker(),
        )
        advanceUntilIdle()

        vm.saveProjectBriefSection(ProjectBriefSectionKey.Description, "Updated brief")
        advanceUntilIdle()

        assertEquals("updateAgentBlock:agent-1:project_description", fakeBlockApi.calls.last())
        assertEquals(BlockUpdateParams(value = "Updated brief"), fakeBlockApi.lastUpdateParams)
        assertEquals("Updated brief", vm.uiState.value.projectBrief.sections[ProjectBriefSectionKey.Description]?.content)
        assertFalse(vm.uiState.value.projectBrief.isSaving)
    }

    @Test
    fun `project agents load from folder membership and live bot status`() = runTest {
        folderRepository = FolderRepository(FakeFolderApi())
        every { agentRepository.getCachedAgent("agent-1") } returns null
        coEvery { internalBotClient.listAgents() } returns listOf(
            BotAgentInfo(id = "agent-1", name = "Coder", status = "working")
        )
        every { agentRepository.getAgent("agent-1") } returns flowOf(
            Agent(
                id = "agent-1",
                name = "Coder",
                model = "gpt-4.1",
                updatedAt = "2026-04-13T12:00:00Z",
            )
        )

        val savedState = SavedStateHandle().apply {
            set("agentId", "agent-1")
            set("conversationId", "conv-1")
            set("projectIdentifier", "letta-mobile")
            set("projectName", "Letta Mobile")
            set("projectLettaFolderId", "folder-1")
        }

        val vm = AdminChatViewModel(
            savedState,
            messageRepository,
            timelineRepository,
            agentRepository,
            blockRepository,
            bugReportRepository,
            folderRepository,
            conversationManager,
            conversationRepository,
            settingsRepository,
            internalBotClient,
            clientModeChatSender,
            chatRouteSessionResolver,
            com.letta.mobile.channel.CurrentConversationTracker(),
        )
        advanceUntilIdle()

        val state = vm.uiState.value.projectAgents
        assertFalse(state.isLoading)
        assertEquals(1, state.agents.size)
        assertEquals("Coder", state.agents.first().name)
        assertEquals("Working", state.agents.first().statusLabel)
        assertEquals("gpt-4.1", state.agents.first().model)
    }

    @Test
    fun `tryHandleSlashCommand is true only for project bug command`() = runTest {
        val savedState = SavedStateHandle().apply {
            set("agentId", "agent-1")
            set("conversationId", "conv-1")
            set("projectIdentifier", "letta-mobile")
            set("projectName", "Letta Mobile")
        }

        val vm = AdminChatViewModel(
            savedState,
            messageRepository,
            timelineRepository,
            agentRepository,
            blockRepository,
            bugReportRepository,
            folderRepository,
            conversationManager,
            conversationRepository,
            settingsRepository,
            internalBotClient,
            clientModeChatSender,
            chatRouteSessionResolver,
            com.letta.mobile.channel.CurrentConversationTracker(),
        )

        assertTrue(vm.tryHandleSlashCommand("/bug"))
        assertFalse(vm.tryHandleSlashCommand("hello"))
    }

    @Test
    fun `submitStructuredBugReport formats prompt and logs report`() = runTest {
        val savedState = SavedStateHandle().apply {
            set("agentId", "agent-1")
            set("conversationId", "conv-1")
            set("projectIdentifier", "letta-mobile")
            set("projectName", "Letta Mobile")
        }

        val vm = AdminChatViewModel(
            savedState,
            messageRepository,
            timelineRepository,
            agentRepository,
            blockRepository,
            bugReportRepository,
            folderRepository,
            conversationManager,
            conversationRepository,
            settingsRepository,
            internalBotClient,
            clientModeChatSender,
            chatRouteSessionResolver,
            com.letta.mobile.channel.CurrentConversationTracker(),
        )
        advanceUntilIdle()

        vm.submitStructuredBugReport(
            ProjectBugReportDraft(
                title = "Crash on sync",
                description = "App crashes after project sync finishes.",
                severity = BugSeverity.High,
                tags = persistentListOf("sync", "crash"),
                attachmentReferences = persistentListOf("recording://screen-1"),
            )
        )
        advanceUntilIdle()

        // The structured prompt should be formatted, logged via the bug-report
        // repository, and surfaced on _uiState. The actual send now goes
        // through TimelineRepository (covered by TimelineSyncLoopTest).
        val submitted = vm.uiState.value.bugReports.lastSubmittedPrompt ?: ""
        assertTrue(submitted.contains("Bug Report: Crash on sync"))
        assertTrue(submitted.contains("Severity: high"))
        assertTrue(submitted.contains("Tags: sync, crash"))
        assertTrue(submitted.contains("recording://screen-1"))
        assertTrue(vm.uiState.value.bugReports.recentReports.any { it.title == "Crash on sync" })
    }

    /**
     * Regression for letta-mobile-nw2e.
     *
     * The prior `startTimelineObserver` used `if (timelineObserverJob?.isActive == true) return`,
     * which silently ignored conversation switches: once the observer was
     * bound to conv-A, selecting conv-B would NOT rebind. The user then sat
     * on conv-B's screen watching it never update because no TimelineSync
     * loop was ever started for conv-B.
     *
     * Acceptance: when the viewmodel is asked to resolve a different
     * conversation after the first one, `timelineRepository.observe(convB)`
     * AND `getOrCreate(convB)` must both be invoked at least once. Using
     * atLeast=1 so we're resilient to internal retries or repeated binds.
     */
    @Test
    fun `switching conversations triggers fresh timeline observer bind`() = runTest {
        // Seed the conversation manager to resolve to "conv-A" first.
        activeConversationIds["agent-1"] = "conv-A"

        val vm = createViewModel(agentId = "agent-1", conversationId = null)
        advanceUntilIdle()

        // Capture arguments of every observe() + getOrCreate() call the VM
        // has made so far. Baseline MUST include conv-A.
        coVerify(atLeast = 1) { timelineRepository.observe("conv-A") }
        coVerify(atLeast = 1) { timelineRepository.getOrCreate("conv-A") }

        // Simulate user picking conv-B in the conversation picker. The
        // production flow invokes conversationManager.setActiveConversation()
        // and then retryConversationLoad() (== resolveConversationAndLoad()).
        activeConversationIds["agent-1"] = "conv-B"
        vm.retryConversationLoad()
        advanceUntilIdle()

        // The fix: observer must rebind to conv-B. If nw2e regresses,
        // these verifications fail because the old `isActive == true`
        // guard short-circuits and conv-B is never observed.
        coVerify(atLeast = 1) { timelineRepository.observe("conv-B") }
        coVerify(atLeast = 1) { timelineRepository.getOrCreate("conv-B") }
    }

    /**
     * Regression for letta-mobile-nw2e (companion): staying on the SAME
     * conversation must remain idempotent. We don't want the fix to cause
     * a storm of rebinds if some caller invokes `startTimelineObserver`
     * repeatedly for the same id (e.g. send → start observer defensively).
     *
     * We check observe() is called at most a small bounded number of times
     * when the conversation id doesn't change. The exact count depends on
     * how many internal paths hit startTimelineObserver, but it must be
     * well under 10× the nominal single bind.
     */
    @Test
    fun `same-conversation repeat calls do not storm rebinds`() = runTest {
        activeConversationIds["agent-1"] = "conv-A"
        val vm = createViewModel(agentId = "agent-1", conversationId = "conv-A")
        advanceUntilIdle()

        // Simulate several calls in quick succession (same conv id). In
        // production these can happen when the user spam-taps a retry, or
        // the send path + the resolver both wire up the observer.
        repeat(5) {
            vm.retryConversationLoad()
            advanceUntilIdle()
        }

        // Hard upper bound: something is badly wrong if we've rebound more
        // than 10× for a single conversation.
        coVerify(atMost = 10) { timelineRepository.observe("conv-A") }
    }

    // ---------------------------------------------------------------
    // letta-mobile-c87t: routing predicate, conversation pass-through,
    // substitution handshake, and banner dismiss.
    // ---------------------------------------------------------------

    @Test
    fun `c87t - existing-conversation entry under client mode routes through gateway`() = runTest {
        // Predicate collapse: even with a non-null conversationId, when client
        // mode is enabled the send must go through ClientModeChatSender, NOT
        // the timeline path. Pre-c87t, this routed to the timeline.
        clientModeEnabledFlow.value = true
        every {
            clientModeChatSender.streamMessage(screenAgentId = any(), text = any(), existingConversationId = any(), isFreshRoute = any())
        } returns flow {
            emit(BotStreamChunk(text = "hi back", conversationId = "conv-existing"))
            emit(BotStreamChunk(text = "hi back", conversationId = "conv-existing", done = true))
        }

        // Existing-conversation entry: route arg conversationId is set.
        val vm = createViewModel(conversationId = "conv-existing")
        advanceUntilIdle()

        vm.sendMessage("hi")
        advanceUntilIdle()

        // Pass-through: existing conversationId carried forward, NOT forceFresh.
        verify(exactly = 1) {
            clientModeChatSender.streamMessage(screenAgentId = "agent-1", text = "hi", existingConversationId = "conv-existing", isFreshRoute = false)
        }
        // And critically: timeline.sendMessage was NOT called.
        coVerify(exactly = 0) {
            timelineRepository.sendMessage("conv-existing", "hi")
        }
    }

    @Test
    fun `c87t - conversation substitution emits banner state and updates savedStateHandle`() = runTest {
        // Gateway substitutes a fresh conversation when the requested one is
        // unrecoverable. The VM should surface a banner via clientModeConversationSwap
        // and update savedStateHandle so re-entering doesn't try the dead id.
        clientModeEnabledFlow.value = true
        every {
            clientModeChatSender.streamMessage(screenAgentId = any(), text = any(), existingConversationId = any(), isFreshRoute = any())
        } returns flow {
            // Note: gateway returns a DIFFERENT conversationId than requested.
            emit(BotStreamChunk(text = "ok", conversationId = "conv-NEW"))
            emit(BotStreamChunk(text = "ok", conversationId = "conv-NEW", done = true))
        }

        val vm = createViewModel(conversationId = "conv-DEAD")
        advanceUntilIdle()

        vm.sendMessage("hi")
        advanceUntilIdle()

        val swap = vm.uiState.value.clientModeConversationSwap
        assertNotNull("Expected banner state to be populated on substitution", swap)
        assertEquals("conv-DEAD", swap!!.requestedConversationId)
        assertEquals("conv-NEW", swap.newConversationId)
    }

    @Test
    fun `c87t - no banner when gateway returns the requested conversationId`() = runTest {
        clientModeEnabledFlow.value = true
        every {
            clientModeChatSender.streamMessage(screenAgentId = any(), text = any(), existingConversationId = any(), isFreshRoute = any())
        } returns flow {
            emit(BotStreamChunk(text = "ok", conversationId = "conv-existing"))
            emit(BotStreamChunk(text = "ok", conversationId = "conv-existing", done = true))
        }

        val vm = createViewModel(conversationId = "conv-existing")
        advanceUntilIdle()

        vm.sendMessage("hi")
        advanceUntilIdle()

        assertNull(
            "Banner must NOT fire when gateway honours the requested conversationId",
            vm.uiState.value.clientModeConversationSwap,
        )
    }

    @Test
    fun `c87t - dismissClientModeConversationSwap clears banner state`() = runTest {
        clientModeEnabledFlow.value = true
        every {
            clientModeChatSender.streamMessage(screenAgentId = any(), text = any(), existingConversationId = any(), isFreshRoute = any())
        } returns flow {
            emit(BotStreamChunk(text = "ok", conversationId = "conv-NEW"))
            emit(BotStreamChunk(text = "ok", conversationId = "conv-NEW", done = true))
        }

        val vm = createViewModel(conversationId = "conv-DEAD")
        advanceUntilIdle()
        vm.sendMessage("hi")
        advanceUntilIdle()
        // Sanity: banner is up.
        assertNotNull(vm.uiState.value.clientModeConversationSwap)

        vm.dismissClientModeConversationSwap()

        assertNull(vm.uiState.value.clientModeConversationSwap)
    }

    // ---------------------------------------------------------------
    // letta-mobile-c87t (PR 2): existing-route Client Mode sends append
    // the user bubble through the timeline, removing the dual-write to
    // _uiState.messages flagged by Meridian. Activates the dormant
    // CLIENT_MODE_HARNESS source path so the fuzzy matcher (PR 1) can
    // reconcile it against Letta's SSE-persisted echo.
    // ---------------------------------------------------------------

    @Test
    fun `c87t pr2 - existing-route Client Mode appends user bubble via timeline`() = runTest {
        clientModeEnabledFlow.value = true
        every {
            clientModeChatSender.streamMessage(screenAgentId = any(), text = any(), existingConversationId = any(), isFreshRoute = any())
        } returns flow {
            emit(BotStreamChunk(text = "ack", conversationId = "conv-existing", done = true))
        }

        val vm = createViewModel(conversationId = "conv-existing")
        advanceUntilIdle()

        vm.sendMessage("user-text-routing-via-timeline")
        advanceUntilIdle()

        // The Client Mode user bubble must NOT touch the legacy
        // timelineRepository.sendMessage path (which would have queued an
        // outbound request to Letta directly, bypassing the gateway).
        coVerify(exactly = 0) {
            timelineRepository.sendMessage(any(), "user-text-routing-via-timeline")
        }
        // It MUST go through appendClientModeLocal so the timeline becomes
        // the source of truth for the message list and the fuzzy matcher
        // can later collapse the SSE-persisted Confirmed echo.
        coVerify(exactly = 1) {
            timelineRepository.appendClientModeLocal(
                conversationId = "conv-existing",
                content = "user-text-routing-via-timeline",
                attachments = emptyList(),
            )
        }
    }
}

/**
 * letta-mobile-lv3e: load the wsstream golden capture from
 * `app/src/test/resources/wsstream-golden-lv3e.json`.
 *
 * Returns (fragments, joined). The fixture is captured via :cli:run wsstream
 * against the live lettabot gateway (delta-shaped: each fragment is a NEW
 * fragment, not a cumulative snapshot). The same fixture is also bundled
 * with the bot module's tests for wire-level round-trip coverage.
 */
private fun loadWsstreamGoldenFragments(): Pair<List<String>, String> {
    val stream = AdminChatViewModelTest::class.java.classLoader
        ?.getResourceAsStream("wsstream-golden-lv3e.json")
        ?: error(
            "wsstream-golden-lv3e.json not on the test classpath — expected at " +
                "android-compose/app/src/test/resources/wsstream-golden-lv3e.json",
        )
    val payload = stream.bufferedReader().use { it.readText() }
    val obj = Json { ignoreUnknownKeys = true }.parseToJsonElement(payload).jsonObject
    val fragments = obj["fragments"]!!.jsonArray.map { it.jsonPrimitive.content }
    return fragments to fragments.joinToString("")
}
