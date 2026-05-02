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
    private lateinit var settingsRepository: SettingsRepository

    private lateinit var internalBotClient: InternalBotClient
    private lateinit var clientModeChatSender: ClientModeChatSender
    private val testDispatcher = UnconfinedTestDispatcher()
    private lateinit var clientModeEnabledFlow: MutableStateFlow<Boolean>
    private var messages: List<AppMessage> = emptyList()
    private var streamStates: List<StreamState> = emptyList()
    // letta-mobile-w2hx.6: per-agent "what's the most-recent server-side
    // conversation" fixture. Pre-w2hx.6 this seeded the singleton
    // ConversationManager.activeConversationIds map; now it seeds
    // conversationRepository.getCachedConversations(agentId) so the VM's
    // resolveMostRecentConversation helper can pick it up. Same shape, same
    // ergonomics for tests — the routing key just lives on the VM now.
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
        settingsRepository = mockk(relaxed = true)
        internalBotClient = mockk(relaxed = true)
        clientModeChatSender = mockk(relaxed = true)
        clientModeEnabledFlow = MutableStateFlow(false)
        activeConversationIds.clear()

        every { settingsRepository.getChatBackgroundKey() } returns flowOf("default")
        every { settingsRepository.getChatFontScale() } returns flowOf(1f)
        every { settingsRepository.observeClientModeEnabled() } returns clientModeEnabledFlow
        every {
            clientModeChatSender.streamMessage(any(), any(), any())
        } returns flow { }
        // letta-mobile-w2hx.6: seed conversationRepository.getCachedConversations
        // from the activeConversationIds fixture map. resolveMostRecentConversation
        // sorts by lastMessageAt/createdAt desc, so we set both to a fixed
        // value for the seeded conv to make it the unambiguous "most recent".
        every { conversationRepository.getCachedConversations(any()) } answers {
            val agentId = firstArg<String>()
            val convId = activeConversationIds[agentId]
            if (convId == null) emptyList() else listOf(
                com.letta.mobile.data.model.Conversation(
                    id = convId,
                    agentId = agentId,
                    summary = "Seeded test conversation",
                    createdAt = "2026-04-27T00:00:00Z",
                    lastMessageAt = "2026-04-28T00:00:00Z",
                ),
            )
        }
        coEvery { conversationRepository.refreshConversationsIfStale(any(), any()) } returns false

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
        // letta-mobile-w2hx.6: default fixture — every agent has "conv-1" cached
        // as its most-recent conversation. This matches the legacy default
        // (the old conversationManager.resolveAndSetActiveConversation stub
        // returned "conv-1" for any agent). Tests can override per-agent by
        // mutating `activeConversationIds`.
        activeConversationIds.getOrPut("agent-1") { "conv-1" }
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun createViewModel(
        agentId: String = "agent-1",
        conversationId: String? = "conv-1",
        freshRouteKey: Long? = null,
        initialMessage: String? = null,
    ): AdminChatViewModel {
        val savedState = SavedStateHandle().apply {
            set("agentId", agentId)
            conversationId?.let { set("conversationId", it) }
            freshRouteKey?.let { set("freshRouteKey", it) }
            initialMessage?.let { set("initialMessage", it) }
        }
        return AdminChatViewModel(
            savedState,
            messageRepository,
            timelineRepository,
            agentRepository,
            blockRepository,
            bugReportRepository,
            folderRepository,
            conversationRepository,
            settingsRepository,
            internalBotClient,
            clientModeChatSender,
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
        // letta-mobile-w2hx.6: resolution now goes through ConversationRepository
        // directly (refreshConversationsIfStale + getCachedConversations);
        // make the refresh fail to simulate the same offline-resolver path.
        coEvery { conversationRepository.refreshConversationsIfStale(any(), any()) } throws IllegalStateException("Resolver offline")

        val vm = createViewModel(conversationId = null)
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
        // letta-mobile-w2hx.6: empty cache → no most-recent conv to resolve.
        activeConversationIds.remove("agent-1")

        val vm = createViewModel(conversationId = null)
        advanceUntilIdle()

        assertEquals(ConversationState.NoConversation, vm.uiState.value.conversationState)
        assertTrue(vm.uiState.value.messages.isEmpty())
        assertFalse(vm.uiState.value.isLoadingMessages)
    }

    @Test
    fun `sendMessage is blocked while conversation resolution is in error state`() = runTest {
        coEvery { conversationRepository.refreshConversationsIfStale(any(), any()) } throws IllegalStateException("Resolver offline")

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
            clientModeChatSender.streamMessage(any(), any(), any())
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
            clientModeChatSender.streamMessage(
                screenAgentId = any(),
                text = any(),
                conversationId = any(),
            )
        } returns flow {
            // letta-mobile (lettabot-uww.11): WS gateway emits PURE DELTAS.
            // This test verifies routing through the client-mode sender,
            // not merge semantics; emit a single delta that already reads
            // as the final bubble content so the assertion below stays
            // focused on routing. Adversarial multi-delta merge coverage
            // lives in the byte-perfect reassembly tests below.
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

        coVerify(exactly = 1) {
            conversationRepository.createConversation("agent-1", "Hello from client mode")
        }
        verify(exactly = 1) {
            clientModeChatSender.streamMessage(
                screenAgentId = "agent-1",
                text = "Hello from client mode",
                conversationId = "new-conv",
            )
        }
        assertEquals(2, vm.uiState.value.messages.size)
        assertEquals("Hello from client mode", vm.uiState.value.messages.last().content)
        assertFalse(vm.uiState.value.isStreaming)
    }

    /**
     * Apr 26 default-load: when entering an agent chat in Client Mode without
     * an explicit conversationId nav arg and without a freshRouteKey, the VM
     * should resolve the agent's most-recent server-side conversation and
     * land the user there (rather than starting on an empty NoConversation
     * screen). Fresh routes (`freshRouteKey != null`) keep their "new
     * conversation" semantics.
     */
    @Test
    fun `client mode resolves to most recent conversation when no nav arg and not fresh`() = runTest {
        clientModeEnabledFlow.value = true

        val vm = createViewModel(conversationId = null, freshRouteKey = null)
        advanceUntilIdle()

        // letta-mobile-w2hx.6: resolution now goes through ConversationRepository.
        // Verify the VM ran the cached-conversations refresh + lookup for this
        // agent, and landed on the most-recent ("conv-1" per the fixture).
        coVerify(atLeast = 1) {
            conversationRepository.refreshConversationsIfStale("agent-1", any())
        }
        verify(atLeast = 1) {
            conversationRepository.getCachedConversations("agent-1")
        }
        assertEquals(
            ConversationState.Ready("conv-1"),
            vm.uiState.value.conversationState,
        )
    }

    /**
     * Apr 26 default-load: a fresh route (`freshRouteKey != null`) must NOT
     * trigger the most-recent fallback in Client Mode — the user explicitly
     * chose to start a new conversation, so we keep the in-memory path.
     */
    @Test
    fun `client mode does not resolve recent conversation on fresh route`() = runTest {
        clientModeEnabledFlow.value = true

        val vm = createViewModel(conversationId = null, freshRouteKey = 1L)
        advanceUntilIdle()

        // letta-mobile-w2hx.6: fresh route → no resolve refresh fired.
        coVerify(exactly = 0) {
            conversationRepository.refreshConversationsIfStale(any(), any())
        }
        assertEquals(
            ConversationState.NoConversation,
            vm.uiState.value.conversationState,
        )
    }

    @Test
    fun `sendMessage rejects attachments in client mode`() = runTest {
        clientModeEnabledFlow.value = true
        val vm = createViewModel(conversationId = null)
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
            clientModeChatSender.streamMessage(
                screenAgentId = any(),
                text = any(),
                conversationId = any(),
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
    fun `client mode ignores immediate stop emitted by send button recomposition race`() = runTest {
        clientModeEnabledFlow.value = true
        val chunks = Channel<BotStreamChunk>(capacity = Channel.UNLIMITED)
        every {
            clientModeChatSender.streamMessage(
                screenAgentId = any(),
                text = any(),
                conversationId = any(),
            )
        } returns chunks.consumeAsFlow()

        val vm = createViewModel(conversationId = "conv-1")
        advanceUntilIdle()

        vm.sendMessage("ping")
        advanceUntilIdle()
        assertTrue(vm.uiState.value.isStreaming)
        assertTrue(vm.uiState.value.isAgentTyping)

        // On device, the send button can recompose to the stop button between
        // press-down and release after sendMessage flips isStreaming=true. The
        // resulting synthetic stop lands within a few hundred ms and must not
        // cancel the stream before session_init / first assistant payload.
        vm.interruptRun()
        advanceUntilIdle()

        assertTrue(
            "Immediate interrupt should be ignored so the thinking indicator remains visible",
            vm.uiState.value.isStreaming,
        )
        assertTrue(
            "Immediate interrupt should not clear the typing indicator",
            vm.uiState.value.isAgentTyping,
        )
        coVerify(exactly = 0) { messageRepository.cancelMessage(any(), any()) }

        chunks.send(BotStreamChunk(text = "reply", conversationId = "conv-1", done = true))
        chunks.close()
        advanceUntilIdle()

        assertFalse(vm.uiState.value.isStreaming)
        assertTrue(
            "Assistant response should still arrive after ignored immediate stop",
            vm.uiState.value.messages.any { it.role == "assistant" && it.content.contains("reply") },
        )
    }

    @Test
    fun `fresh client mode bootstrap shows streaming before conversation create completes and blocks duplicate submit`() = runTest {
        clientModeEnabledFlow.value = true
        activeConversationIds.clear()
        messages = emptyList()

        val createGate = kotlinx.coroutines.CompletableDeferred<Unit>()
        var createCalls = 0
        coEvery { conversationRepository.createConversation("agent-1", "hello fresh") } coAnswers {
            createCalls += 1
            createGate.await()
            TestData.conversation(id = "new-conv", agentId = "agent-1", summary = "hello fresh")
        }

        val chunks = Channel<BotStreamChunk>(capacity = Channel.UNLIMITED)
        every {
            clientModeChatSender.streamMessage(
                screenAgentId = any(),
                text = any(),
                conversationId = any(),
            )
        } returns chunks.consumeAsFlow()

        val vm = createViewModel(conversationId = null, freshRouteKey = 4242L)
        advanceUntilIdle()

        vm.updateInputText("hello fresh")
        vm.sendMessage("hello fresh")

        assertEquals(
            "Fresh bootstrap must clear the composer before createConversation returns",
            "",
            vm.inputText.value,
        )
        val optimisticUserBubbles = vm.uiState.value.messages.filter {
            it.role == "user" && it.content == "hello fresh"
        }
        assertEquals(
            "Fresh bootstrap must render one optimistic user bubble while createConversation is still pending; " +
                "messages=${vm.uiState.value.messages.map { "${it.role}=${it.content}" }}",
            1,
            optimisticUserBubbles.size,
        )
        assertTrue(
            "Fresh bootstrap must show streaming while createConversation is still pending",
            vm.uiState.value.isStreaming,
        )
        assertTrue(
            "Fresh bootstrap must show typing while createConversation is still pending",
            vm.uiState.value.isAgentTyping,
        )
        assertEquals(ConversationState.NoConversation, vm.uiState.value.conversationState)

        vm.submitComposer("second tap")
        advanceUntilIdle()

        assertEquals(
            "Second composer submit during bootstrap must not start another create/send",
            1,
            createCalls,
        )
        assertTrue(vm.uiState.value.isStreaming)
        verify(exactly = 0) {
            clientModeChatSender.streamMessage(
                screenAgentId = any(),
                text = "second tap",
                conversationId = any(),
            )
        }

        createGate.complete(Unit)
        advanceUntilIdle()

        verify(exactly = 1) {
            clientModeChatSender.streamMessage(
                screenAgentId = "agent-1",
                text = "hello fresh",
                conversationId = "new-conv",
            )
        }

        chunks.send(BotStreamChunk(text = "reply", conversationId = "new-conv", done = true))
        chunks.close()
        advanceUntilIdle()
        val finalUserBubbles = vm.uiState.value.messages.filter {
            it.role == "user" && it.content == "hello fresh"
        }
        assertEquals(
            "Optimistic user bubble must collapse to one timeline-backed user bubble after bootstrap; " +
                "messages=${vm.uiState.value.messages.map { "${it.role}=${it.content}" }}",
            1,
            finalUserBubbles.size,
        )
        assertTrue(
            "Assistant response should appear once after fresh bootstrap",
            vm.uiState.value.messages.count { it.role == "assistant" && it.content.contains("reply") } == 1,
        )
        assertFalse(vm.uiState.value.isStreaming)
    }

    @Test
    fun `resetMessages clears client mode conversation state`() = runTest {
        clientModeEnabledFlow.value = true
        every {
            clientModeChatSender.streamMessage(
                screenAgentId = any(),
                text = any(),
                conversationId = any(),
            )
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
     * with a NULL conversationId on the first chunk, then echoes the requested
     * pre-created conversationId starting on chunk #2. Under letta-mobile-vynx
     * fresh routes already have a blank bootstrap conversation, so chunk #1
     * should still stream through the timeline path using that known id.
     *
     * Contract: every text fragment emitted by the gateway must be visible
     * in the final assistant bubble, regardless of whether the individual
     * chunk carrying it echoed the conversationId yet.
     */
    @Test
    fun `client mode preserves first chunk text when conversationId arrives only on chunk 2`() = runTest {
        clientModeEnabledFlow.value = true
        every {
            clientModeChatSender.streamMessage(
                screenAgentId = any(),
                text = any(),
                conversationId = any(),
            )
        } returns flow {
            // letta-mobile-flk.4 regression repro: realistic DELTA wire
            // shape (each frame contributes a NEW fragment, not a
            // cumulative snapshot — verified against the live lettabot WS
            // gateway). Earlier this test used snapshot-shape data
            // ["Hel","Hello","Hello world"] which masked the migration
            // carry-over bug because chunks #2/#3 happened to be
            // prefix-shaped relative to the timeline's seed-from-chunk-#2
            // content. With genuine delta shape, the absence of carry-over
            // produces a final bubble of "lo world" instead of "Hello world"
            // — which is exactly the "lost first few characters" symptom
            // Emmanuel reported. Markdown rendering then breaks if the
            // dropped leading characters contained markdown openers (`**`,
            // `# `, ``` ``` `, etc.).
            //
            // Chunk #1 — gateway hasn't echoed conversationId yet, but the
            // VM should use the pre-created bootstrap id; accumulated
            // assistant content is "Hel".
            emit(BotStreamChunk(text = "Hel", conversationId = null, event = BotStreamEvent.ASSISTANT))
            // Chunk #2 — conversationId now present. It must append to the
            // existing timeline Local that chunk #1 created; otherwise the
            // assistant Local would seed with just "lo " and the "Hel"
            // prefix would be lost forever.
            emit(BotStreamChunk(text = "lo ", conversationId = "new-conv", event = BotStreamEvent.ASSISTANT))
            // Chunk #3 — pure delta on top of the timeline seed.
            emit(BotStreamChunk(text = "world", conversationId = "new-conv", event = BotStreamEvent.ASSISTANT))
            emit(BotStreamChunk(conversationId = "new-conv", done = true))
        }

        val vm = createViewModel(conversationId = null, freshRouteKey = 1L)
        advanceUntilIdle()

        vm.sendMessage("hi")
        advanceUntilIdle()

        val assistant = vm.uiState.value.messages.lastOrNull { it.role == "assistant" }
        assertNotNull("assistant bubble must be rendered", assistant)
        assertEquals(
            "final assistant bubble must include the chunk-1 fragment even " +
                "when that chunk did not echo a conversationId",
            "Hello world",
            assistant!!.content,
        )
        assertFalse("isStreaming must clear once stream completes", vm.uiState.value.isStreaming)
        assertFalse("isAgentTyping must clear once stream completes", vm.uiState.value.isAgentTyping)
    }

    /**
     * letta-mobile-flk.4 regression test (markdown-specific): when the
     * dropped leading chars contained markdown openers, the entire bubble
     * renders unformatted (the unmatched closer `**` becomes literal text).
     * This pins the carry-over so we don't regress on markdown rendering.
     */
    @Test
    fun `client mode carries markdown openers across migration`() = runTest {
        clientModeEnabledFlow.value = true
        every {
            clientModeChatSender.streamMessage(
                screenAgentId = any(),
                text = any(),
                conversationId = any(),
            )
        } returns flow {
            // Chunk #1 — no echoed conv id yet, contains the OPENING `**`.
            emit(BotStreamChunk(text = "**bold", conversationId = null, event = BotStreamEvent.ASSISTANT))
            // Chunk #2 — echoed conv id present, contains the CLOSING `**`.
            // If chunk #1 is dropped, the bubble shows " text**" with an
            // unmatched closer (literal asterisks).
            emit(BotStreamChunk(text = " text**", conversationId = "new-conv", event = BotStreamEvent.ASSISTANT))
            emit(BotStreamChunk(conversationId = "new-conv", done = true))
        }

        val vm = createViewModel(conversationId = null, freshRouteKey = 1L)
        advanceUntilIdle()

        vm.sendMessage("hi")
        advanceUntilIdle()

        val assistant = vm.uiState.value.messages.lastOrNull { it.role == "assistant" }
        assertNotNull("assistant bubble must be rendered", assistant)
        assertEquals(
            "final assistant bubble must contain the full markdown including the chunk-1 opener",
            "**bold text**",
            assistant!!.content,
        )
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
            clientModeChatSender.streamMessage(
                screenAgentId = any(),
                text = any(),
                conversationId = any(),
            )
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
            clientModeChatSender.streamMessage(
                screenAgentId = any(),
                text = any(),
                conversationId = any(),
            )
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

    // letta-mobile (lettabot-uww.11): the prior test
    // `client mode chunks survive accidental cumulative-snapshot frames`
    // intentionally codified the wucn-snapshot-recovery client-side defense
    // (collapse a frame whose text equals the running accumulator). That
    // defense was the bug: it silently dropped legitimate deltas whose
    // head matched a prefix of the accumulator (the "A[LLM snapshots]" →
    // "A[LLMapshots|" field repro). The contract — verified by the
    // server-side gateway e2e suite ws-gateway.e2e.test.ts §
    // "assistant text reassembly" (37 byte-perfect reassembly cases) —
    // is that the gateway emits PURE DELTAS. If we ever see a true
    // duplicate frame on the wire, that's a server-side gateway bug to
    // fix at the source, not papered over on the client. The byte-perfect
    // reassembly tests below (prefix collisions, mermaid char-by-char,
    // exact field repro) already enforce the new contract.

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
            clientModeChatSender.streamMessage(any(), any(), any())
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
            clientModeChatSender.streamMessage(any(), any(), any())
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
            clientModeChatSender.streamMessage(any(), any(), any())
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
     * lettabot-uww.11 regression: assistant-text deltas must concatenate
     * byte-for-byte regardless of prefix collisions between deltas and
     * the running accumulator. Without this guarantee the rendered
     * bubble silently drops user-visible characters at chunk boundaries.
     *
     * Pre-fix bug (letta-mobile-wucn): the merge cascade dropped any
     * delta whose head matched a prefix of the accumulator (branch
     * `existing.content.startsWith(delta) -> existing.content`) and
     * destructively replaced the accumulator on >=32-char "near-
     * snapshots". Field repro 2026-04-26: rendered mermaid block
     * `A[LLM snapshots]` came out as `A[LLMapshots|`.
     *
     * Post-fix contract (per ws-gateway.e2e.test.ts § "assistant text
     * reassembly"): the gateway emits PURE DELTAS. Trust the contract;
     * append.
     *
     * This test drives the legacy in-memory upsertClientModeAssistantMessage
     * path with a delta sequence engineered to exercise every defective
     * branch the wucn cascade had:
     *   - delta head equals accumulator (the silent-drop branch)
     *   - delta is >=32 chars AND >= half the accumulator (the
     *     destructive-replace branch)
     *   - duplicate deltas (idempotency under retransmit)
     */
    @Test
    fun `client mode legacy path concatenates deltas byte-for-byte under prefix collisions`() = runTest {
        clientModeEnabledFlow.value = true
        // Each fragment was hand-picked so the running accumulator's
        // tail-prefixes collide with the next fragment's head — exactly
        // the shape that misfired the wucn `existing.content.startsWith`
        // branch in production.
        val fragments = listOf(
            "The ",                                                 //  4 chars
            "quick brown fox ",                                     // 16
            "jumps over ",                                          // 11
            "the lazy dog ",                                        // 13 (note: starts with "the" — collides with "The " head when lowercased? we use exact match so no, but...)
            "The quick brown fox jumps over the lazy dog ",         // 44 chars — head EQUALS the accumulator so far → wucn would have silently dropped this whole 44-char delta
            "again, ",                                              //  7
            "and again — quietly in the moonlight.",                // 38 — >=32 chars, >=50% of accumulator → wucn would have destructively replaced everything before it
        )
        val expected = fragments.joinToString("")

        every {
            clientModeChatSender.streamMessage(any(), any(), any())
        } returns flow {
            fragments.forEach { f ->
                emit(BotStreamChunk(text = f, conversationId = "client-conv", event = BotStreamEvent.ASSISTANT))
            }
            emit(BotStreamChunk(conversationId = "client-conv", done = true))
        }

        val vm = createViewModel(conversationId = null, freshRouteKey = 1L)
        advanceUntilIdle()
        vm.sendMessage("hi")
        advanceUntilIdle()

        val assistant = vm.uiState.value.messages.lastOrNull { it.role == "assistant" }
        assertNotNull("assistant bubble must be rendered", assistant)
        // The smoking-gun assertion. If this fails, characters were
        // silently dropped or the accumulator was destructively
        // replaced — the exact bug shape from the field repro.
        assertEquals(
            "lettabot-uww.11: assistant text must concatenate byte-for-byte across prefix-colliding deltas",
            expected,
            assistant!!.content,
        )
    }

    /**
     * lettabot-uww.11 regression: the mermaid field repro itself.
     * Streams the literal block `A[LLM snapshots] --> B[Coalesce]`
     * split character-by-character (the worst-case adversarial
     * chunking exercised on the server side by ws-gateway.e2e.test.ts).
     * Asserts byte-perfect reassembly so we'd catch a regression of
     * the original screenshot.
     */
    @Test
    fun `client mode legacy path reassembles mermaid block char-by-char`() = runTest {
        clientModeEnabledFlow.value = true
        val mermaid = "A[LLM snapshots] --> B[Coalesce?]"
        val chunks = mermaid.map { it.toString() }

        every {
            clientModeChatSender.streamMessage(any(), any(), any())
        } returns flow {
            chunks.forEach { c ->
                emit(BotStreamChunk(text = c, conversationId = "client-conv", event = BotStreamEvent.ASSISTANT))
            }
            emit(BotStreamChunk(conversationId = "client-conv", done = true))
        }

        val vm = createViewModel(conversationId = null, freshRouteKey = 1L)
        advanceUntilIdle()
        vm.sendMessage("hi")
        advanceUntilIdle()

        val assistant = vm.uiState.value.messages.lastOrNull { it.role == "assistant" }
        assertNotNull(assistant)
        assertEquals(
            "lettabot-uww.11: mermaid char-by-char reassembly must be byte-perfect",
            mermaid,
            assistant!!.content,
        )
        // Guard against the specific corruption signature from the
        // 2026-04-26 field repro. If either of these substrings is
        // missing, the bubble looks like `A[LLMapshots|`.
        assertTrue(
            "missing 'A[LLM snapshots]' (silent character drop signature)",
            assistant.content.contains("A[LLM snapshots]"),
        )
        assertTrue(
            "missing closing bracket before --> (destructive-replace signature)",
            assistant.content.contains("] --> B[Coalesce?]"),
        )
    }

    @Test
    fun `client mode renders tool call and tool result chunks as tool card`() = runTest {
        clientModeEnabledFlow.value = true
        every {
            clientModeChatSender.streamMessage(any(), any(), any())
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

        // letta-mobile-c87t / Apr 26 default-load: a null conversationId
        // arg WITHOUT a freshRouteKey now resolves to the agent's most
        // recent server-side conversation (here: conv-1 via the fake
        // conversationManager.resolveAndSetActiveConversation). To exercise
        // the fresh-route streaming path the test setup assumes (chunks
        // landing on the gateway-allocated "client-conv" timeline), we
        // pass an explicit freshRouteKey so isFreshRoute=true.
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
    fun `toggling client mode off keeps precreated fresh client conversation`() = runTest {
        clientModeEnabledFlow.value = true
        messages = listOf(
            TestData.appMessage(id = "timeline-user", messageType = MessageType.USER, content = "Timeline hello"),
            TestData.appMessage(id = "timeline-assistant", messageType = MessageType.ASSISTANT, content = "Timeline reply"),
        )
        every {
            clientModeChatSender.streamMessage(any(), any(), any())
        } returns flow {
            emit(BotStreamChunk(text = "Client reply", conversationId = "new-conv", done = true))
        }

        // letta-mobile-vynx: fresh Client Mode routes now pre-create a blank
        // Letta conversation before sending so toggling Client Mode off should
        // stay on that newly-created conversation, not restore old conv-1.
        val vm = createViewModel(conversationId = null, freshRouteKey = 1L)
        advanceUntilIdle()

        vm.sendMessage("hello")
        advanceUntilIdle()
        assertTrue(vm.uiState.value.messages.any { it.content == "Client reply" })

        clientModeEnabledFlow.value = false
        advanceUntilIdle()

        assertEquals(ConversationState.Ready("new-conv"), vm.uiState.value.conversationState)
        assertTrue(vm.uiState.value.messages.any { it.content == "hello" })
        assertTrue(vm.uiState.value.messages.any { it.content == "Client reply" })
        assertTrue(vm.uiState.value.messages.none { it.id == "timeline-user" })
        assertTrue(vm.uiState.value.messages.none { it.id == "timeline-assistant" })
        assertFalse(vm.uiState.value.isStreaming)
    }

    @Test
    fun `blank conversation id in saved state behaves like fresh client mode route`() = runTest {
        clientModeEnabledFlow.value = true
        every {
            clientModeChatSender.streamMessage(
                screenAgentId = any(),
                text = any(),
                conversationId = any(),
            )
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
            conversationRepository,
            settingsRepository,
            internalBotClient,
            clientModeChatSender,
            com.letta.mobile.channel.CurrentConversationTracker(),
        )
        advanceUntilIdle()

        vm.sendMessage("hello")
        advanceUntilIdle()

        coVerify(exactly = 1) {
            conversationRepository.createConversation("agent-1", "hello")
        }
        verify(exactly = 1) {
            clientModeChatSender.streamMessage(
                screenAgentId = "agent-1",
                text = "hello",
                conversationId = "new-conv",
            )
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
            clientModeChatSender.streamMessage(
                screenAgentId = any(),
                text = any(),
                conversationId = any(),
            )
        } returns flow {
            emit(BotStreamChunk(text = "Fresh client reply", conversationId = "client-conv", done = true))
        }

        val vm = createViewModel(conversationId = null, freshRouteKey = 123L)
        advanceUntilIdle()

        assertEquals(ConversationState.NoConversation, vm.uiState.value.conversationState)
        assertTrue(vm.uiState.value.messages.isEmpty())

        // Regression for letta-mobile-vynx: a second Client Mode resolve before
        // the first send must stay in the isolated bootstrap state instead of
        // hydrating the cached prior conversation for this agent.
        vm.retryConversationLoad()
        advanceUntilIdle()
        assertEquals(ConversationState.NoConversation, vm.uiState.value.conversationState)
        assertTrue(vm.uiState.value.messages.none { it.content == "Old timeline" })
        coVerify(exactly = 0) { timelineRepository.observe("conv-1") }

        vm.sendMessage("hello")
        advanceUntilIdle()

        coVerify(exactly = 1) {
            conversationRepository.createConversation("agent-1", "hello")
        }
        verify(exactly = 1) {
            clientModeChatSender.streamMessage(
                screenAgentId = "agent-1",
                text = "hello",
                conversationId = "new-conv",
            )
        }
        coVerify(exactly = 0) { timelineRepository.sendMessage(any(), any()) }
        coVerify(exactly = 0) { timelineRepository.observe("conv-1") }
        assertTrue(vm.uiState.value.messages.none { it.content == "Old timeline" })
        assertTrue(vm.uiState.value.messages.any { it.content == "Fresh client reply" })
    }

    @Test
    fun `existing conversation route hydrates passed conversation instead of falling back to empty state`() = runTest {
        messages = listOf(
            TestData.appMessage(id = "existing-user", messageType = MessageType.USER, content = "Earlier message"),
            TestData.appMessage(id = "existing-assistant", messageType = MessageType.ASSISTANT, content = "Existing reply"),
        )
        // letta-mobile-w2hx.6: empty cache → resolve fallback would yield none.
        // The VM must still hydrate the explicit `conv-1` nav arg rather than
        // falling back to NoConversation.
        activeConversationIds.remove("agent-1")

        val vm = createViewModel(conversationId = "conv-1")
        advanceUntilIdle()

        assertEquals(ConversationState.Ready("conv-1"), vm.uiState.value.conversationState)
        assertTrue(vm.uiState.value.messages.any { it.id == "existing-user" })
        assertTrue(vm.uiState.value.messages.any { it.id == "existing-assistant" })
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
            conversationRepository,
            settingsRepository,
            internalBotClient,
            clientModeChatSender,
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
            conversationRepository,
            settingsRepository,
            internalBotClient,
            clientModeChatSender,
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
            conversationRepository,
            settingsRepository,
            internalBotClient,
            clientModeChatSender,
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
            conversationRepository,
            settingsRepository,
            internalBotClient,
            clientModeChatSender,
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
            conversationRepository,
            settingsRepository,
            internalBotClient,
            clientModeChatSender,
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
            conversationRepository,
            settingsRepository,
            internalBotClient,
            clientModeChatSender,
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
     * Regression for letta-mobile-nw2e — preserved post-w2hx.6 via per-VM
     * binding instead of cross-VM mutation of a shared singleton.
     *
     * Original failure mode: `startTimelineObserver` used
     * `if (timelineObserverJob?.isActive == true) return`, silently ignoring
     * conversation switches once bound to conv-A.
     *
     * Pre-w2hx.6 the test mutated the agent-keyed ConversationManager map
     * and called `vm.retryConversationLoad()` to force a re-resolve to
     * conv-B in the same VM. After w2hx.6 the VM owns its own
     * `activeConversationId` (no shared singleton), and conversation
     * switching is modeled by navigation creating a NEW VM with the new
     * conversationId nav arg — which is what we exercise here. Each VM
     * must bind its own observer to its own conv. The original
     * `isActive == true` guard would have meant a single VM only ever
     * observed once; the per-VM equivalent failure mode is "two VMs sharing
     * an observer" or "neither VM ever binds". We assert both VMs bind
     * their respective conversation ids.
     */
    @Test
    fun `switching conversations triggers fresh timeline observer bind`() = runTest {
        activeConversationIds["agent-1"] = "conv-A"

        val vmA = createViewModel(agentId = "agent-1", conversationId = "conv-A")
        advanceUntilIdle()

        coVerify(atLeast = 1) { timelineRepository.observe("conv-A") }
        coVerify(atLeast = 1) { timelineRepository.getOrCreate("conv-A") }

        // Simulate user picking conv-B in the conversation picker, which in
        // production triggers a nav re-entry with a new conversationId arg
        // → fresh VM under that nav route.
        val vmB = createViewModel(agentId = "agent-1", conversationId = "conv-B")
        advanceUntilIdle()

        coVerify(atLeast = 1) { timelineRepository.observe("conv-B") }
        coVerify(atLeast = 1) { timelineRepository.getOrCreate("conv-B") }

        // Sanity: each VM is on its own conv.
        assertEquals(ConversationState.Ready("conv-A"), vmA.uiState.value.conversationState)
        assertEquals(ConversationState.Ready("conv-B"), vmB.uiState.value.conversationState)
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
            clientModeChatSender.streamMessage(
                screenAgentId = any(),
                text = any(),
                conversationId = any(),
            )
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
            clientModeChatSender.streamMessage(
                screenAgentId = "agent-1",
                text = "hi",
                conversationId = "conv-existing",
            )
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
            clientModeChatSender.streamMessage(
                screenAgentId = any(),
                text = any(),
                conversationId = any(),
            )
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
            clientModeChatSender.streamMessage(
                screenAgentId = any(),
                text = any(),
                conversationId = any(),
            )
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
            clientModeChatSender.streamMessage(
                screenAgentId = any(),
                text = any(),
                conversationId = any(),
            )
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
            clientModeChatSender.streamMessage(
                screenAgentId = any(),
                text = any(),
                conversationId = any(),
            )
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

    /**
     * letta-mobile-w2hx.5: two chats opened on different agents must each
     * resolve their own agent header from conversation/agent metadata, and
     * a send in chat A must NOT route through chat B's conversation.
     *
     * This is the bound-agent no-bleedover acceptance test. The chat row
     * (today: a (agentId, conversationId) nav route) is the routing key —
     * conversation_id is the primary identity that flows into the
     * timeline send path. There is no shared per-agent state that can
     * leak chat A's text into chat B's conversation.
     *
     * Note: removing the residual ConversationManager in-memory map keyed
     * on agentId is captured by w2hx.6.
     */
    @Test
    fun `w2hx_5 two chats different agents do not bleed over conversation routing`() = runTest {
        // Per-agent metadata so each VM's agent header is distinguishable.
        every { agentRepository.getAgent("agent-A") } returns
            flowOf(TestData.agent(id = "agent-A", name = "Agent Alpha"))
        every { agentRepository.getAgent("agent-B") } returns
            flowOf(TestData.agent(id = "agent-B", name = "Agent Beta"))

        // Each agent's "active conversation" is its own server-side conv.
        // Post-w2hx.6 there's no shared agent-keyed map: each VM (chat row)
        // owns its own conversation_id, and routing is by conversation_id,
        // so there's no place for state to leak between sibling chats.
        // Seed the cached-conversations fixture so the per-VM resolve
        // helper maps each agent → its own most-recent conv.
        activeConversationIds["agent-A"] = "conv-A"
        activeConversationIds["agent-B"] = "conv-B"

        val vmA = createViewModel(agentId = "agent-A", conversationId = "conv-A")
        val vmB = createViewModel(agentId = "agent-B", conversationId = "conv-B")
        advanceUntilIdle()

        // Each chat resolves its own agent header from metadata, not a
        // shared bound-agent slot.
        assertEquals("Agent Alpha", vmA.uiState.value.agentName)
        assertEquals("Agent Beta", vmB.uiState.value.agentName)
        assertEquals(
            ConversationState.Ready("conv-A"),
            vmA.uiState.value.conversationState,
        )
        assertEquals(
            ConversationState.Ready("conv-B"),
            vmB.uiState.value.conversationState,
        )

        // Send in chat A. The send must target conv-A, never conv-B.
        vmA.sendMessage("hello from A")
        advanceUntilIdle()

        coVerify(exactly = 1) {
            timelineRepository.sendMessage("conv-A", "hello from A")
        }
        coVerify(exactly = 0) {
            timelineRepository.sendMessage("conv-B", any())
        }

        // Chat B's conversation state is untouched by chat A's send.
        assertEquals(
            ConversationState.Ready("conv-B"),
            vmB.uiState.value.conversationState,
        )
        assertFalse(vmB.uiState.value.isStreaming)

        // Now send in chat B. Routing is to conv-B, never conv-A.
        vmB.sendMessage("hello from B")
        advanceUntilIdle()

        coVerify(exactly = 1) {
            timelineRepository.sendMessage("conv-B", "hello from B")
        }
        coVerify(exactly = 1) {
            // Still exactly one A send across the whole test — no
            // duplication, no cross-talk.
            timelineRepository.sendMessage("conv-A", "hello from A")
        }
    }

    /**
     * letta-mobile-9pfm regression repro:
     *
     * Fresh-route Client Mode send must show the user bubble optimistically
     * BEFORE any gateway chunk arrives. The send coroutine writes to
     * `clientModeMessages` + `_uiState.messages` synchronously at lines
     * 1107–1109 in AdminChatViewModel, with `isStreaming=true` set right
     * after. If anything between the optimistic write and the first
     * stream chunk clobbers `_uiState.messages`, the user sees no
     * echo of their input.
     *
     * This test pins the optimistic-bubble contract: send → assert the
     * "user" message is present BEFORE we deliver any gateway chunk.
     */
    @Test
    fun `fresh route client mode shows optimistic user bubble before any chunk arrives`() = runTest {
        clientModeEnabledFlow.value = true
        // Empty active-conv map → fresh route. No timeline pre-seed.
        activeConversationIds.clear()
        messages = emptyList()

        // Channel-backed flow: nothing emitted until we explicitly send.
        val chunks = Channel<BotStreamChunk>(capacity = Channel.UNLIMITED)
        every {
            clientModeChatSender.streamMessage(
                screenAgentId = any(),
                text = any(),
                conversationId = any(),
            )
        } returns chunks.consumeAsFlow()

        val vm = createViewModel(conversationId = null, freshRouteKey = 9999L)
        advanceUntilIdle()

        // Pre-condition: empty.
        assertEquals(ConversationState.NoConversation, vm.uiState.value.conversationState)
        assertTrue(vm.uiState.value.messages.isEmpty())

        vm.sendMessage("hello fresh")
        // Drive the send coroutine up to the first chunks.receive() suspend.
        advanceUntilIdle()

        // CRITICAL: user bubble must be visible before any chunk arrives.
        val userBubbles = vm.uiState.value.messages.filter { it.role == "user" }
        assertEquals(
            "Expected exactly one optimistic user bubble before stream chunks; " +
                "messages=${vm.uiState.value.messages.map { "${it.role}=${it.content}" }}",
            1,
            userBubbles.size,
        )
        assertEquals("hello fresh", userBubbles.first().content)

        // And isStreaming/isAgentTyping must be true so the spinner shows.
        assertTrue("isStreaming must be true mid-flight", vm.uiState.value.isStreaming)
        assertTrue("isAgentTyping must be true before first chunk", vm.uiState.value.isAgentTyping)

        // Cleanup
        chunks.send(BotStreamChunk(text = "reply", conversationId = "client-conv", done = true))
        chunks.close()
        advanceUntilIdle()
    }

    @Test
    fun `fresh route client mode skips optimistic append when bootstrap user already hydrated`() = runTest {
        clientModeEnabledFlow.value = true
        activeConversationIds.clear()
        messages = emptyList()

        val hydratedBootstrapTimeline = kotlinx.coroutines.flow.MutableStateFlow(
            com.letta.mobile.data.timeline.Timeline(
                conversationId = "new-conv",
                events = listOf(
                    com.letta.mobile.data.timeline.TimelineEvent.Confirmed(
                        position = 1.0,
                        otid = "server-bootstrap-user",
                        content = "hello fresh",
                        serverId = "server-bootstrap-user",
                        messageType = com.letta.mobile.data.timeline.TimelineMessageType.USER,
                        date = Instant.now(),
                        runId = null,
                        stepId = null,
                    ),
                ),
            ),
        )
        coEvery { timelineRepository.observe("new-conv") } returns hydratedBootstrapTimeline

        val chunks = Channel<BotStreamChunk>(capacity = Channel.UNLIMITED)
        every {
            clientModeChatSender.streamMessage(
                screenAgentId = any(),
                text = any(),
                conversationId = any(),
            )
        } returns chunks.consumeAsFlow()

        val vm = createViewModel(conversationId = null, freshRouteKey = 10001L)
        advanceUntilIdle()

        vm.sendMessage("hello fresh")
        advanceUntilIdle()

        verify(exactly = 1) {
            clientModeChatSender.streamMessage(
                screenAgentId = "agent-1",
                text = "hello fresh",
                conversationId = "new-conv",
            )
        }
        coVerify(exactly = 0) {
            timelineRepository.appendClientModeLocal(
                conversationId = "new-conv",
                content = "hello fresh",
                attachments = emptyList(),
            )
        }
        val userBubbles = vm.uiState.value.messages.filter { it.role == "user" && it.content == "hello fresh" }
        assertEquals(
            "Hydrated bootstrap user message should not be duplicated; " +
                "messages=${vm.uiState.value.messages.map { "${it.role}=${it.content}" }}",
            1,
            userBubbles.size,
        )
        assertTrue("isStreaming must remain true mid-flight", vm.uiState.value.isStreaming)
        assertTrue("isAgentTyping must remain true mid-flight", vm.uiState.value.isAgentTyping)

        chunks.send(BotStreamChunk(text = "reply", conversationId = "new-conv", done = true))
        chunks.close()
        advanceUntilIdle()
    }

    @Test
    fun `fresh route initial message in client mode shows optimistic bubble before any chunk arrives`() = runTest {
        clientModeEnabledFlow.value = true
        activeConversationIds.clear()
        messages = emptyList()

        val chunks = Channel<BotStreamChunk>(capacity = Channel.UNLIMITED)
        every {
            clientModeChatSender.streamMessage(
                screenAgentId = any(),
                text = any(),
                conversationId = any(),
            )
        } returns chunks.consumeAsFlow()

        val vm = createViewModel(
            conversationId = null,
            freshRouteKey = 1234L,
            initialMessage = "hello initial",
        )
        advanceUntilIdle()

        coVerify(exactly = 1) {
            conversationRepository.createConversation("agent-1", "hello initial")
        }
        verify(exactly = 1) {
            clientModeChatSender.streamMessage(
                screenAgentId = "agent-1",
                text = "hello initial",
                conversationId = "new-conv",
            )
        }
        coVerify(exactly = 0) {
            timelineRepository.sendMessage(any(), "hello initial")
        }

        val userBubbles = vm.uiState.value.messages.filter { it.role == "user" }
        assertEquals(
            "Initial route message should render exactly one optimistic user bubble; " +
                "messages=${vm.uiState.value.messages.map { "${it.role}=${it.content}" }}",
            1,
            userBubbles.size,
        )
        assertEquals("hello initial", userBubbles.first().content)
        assertTrue("isStreaming must be true for initial message", vm.uiState.value.isStreaming)
        assertTrue("isAgentTyping must be true for initial message", vm.uiState.value.isAgentTyping)

        chunks.send(BotStreamChunk(text = "reply", conversationId = "client-conv", done = true))
        chunks.close()
        advanceUntilIdle()
    }

    @Test
    fun `initial route message is consumed once across client mode re-resolves`() = runTest {
        clientModeEnabledFlow.value = true
        activeConversationIds.clear()
        messages = emptyList()

        every {
            clientModeChatSender.streamMessage(
                screenAgentId = any(),
                text = any(),
                conversationId = any(),
            )
        } returns flow {
            emit(BotStreamChunk(text = "reply", conversationId = "client-conv", done = true))
        }

        createViewModel(
            conversationId = null,
            freshRouteKey = 5678L,
            initialMessage = "send me once",
        )
        advanceUntilIdle()

        clientModeEnabledFlow.value = false
        advanceUntilIdle()
        clientModeEnabledFlow.value = true
        advanceUntilIdle()

        coVerify(exactly = 1) {
            conversationRepository.createConversation("agent-1", "send me once")
        }
        verify(exactly = 1) {
            clientModeChatSender.streamMessage(
                screenAgentId = "agent-1",
                text = "send me once",
                conversationId = "new-conv",
            )
        }
        coVerify(exactly = 0) {
            timelineRepository.sendMessage(any(), "send me once")
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
