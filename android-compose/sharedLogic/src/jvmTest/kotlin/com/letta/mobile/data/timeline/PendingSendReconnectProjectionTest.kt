package com.letta.mobile.data.timeline

import com.letta.mobile.data.chat.projection.ChatRenderItem
import com.letta.mobile.data.chat.projection.TimelineRowAssembly
import com.letta.mobile.data.chat.send.ChatSendCoordinator
import com.letta.mobile.data.chat.send.ChatSendUiSink
import com.letta.mobile.data.chat.send.QueueConversationId
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.AssistantMessage
import com.letta.mobile.data.model.Conversation
import com.letta.mobile.data.model.LettaConfig
import com.letta.mobile.data.model.LettaMessage
import com.letta.mobile.data.model.MessageId
import com.letta.mobile.data.model.UserMessage
import com.letta.mobile.data.repository.api.IConversationRepository
import com.letta.mobile.data.timeline.api.TimelinePendingRetryBinding
import com.letta.mobile.data.timeline.api.TimelineExternalTransportWriter
import com.letta.mobile.data.timeline.snapshot.TimelineScope
import com.letta.mobile.data.transport.ChannelTransportState
import com.letta.mobile.data.transport.ServerFrame
import com.letta.mobile.data.transport.TransportFrameEvent
import com.letta.mobile.data.transport.WsChatBridge
import com.letta.mobile.data.transport.WsTimelineEvent
import com.letta.mobile.data.transport.api.IChannelTransport
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import java.lang.reflect.Proxy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.test.fail

/** The send queue, bridge, scoped writer, history adapter, reducer, pager and rendered row assembly. */
@OptIn(ExperimentalCoroutinesApi::class)
class PendingSendReconnectProjectionTest {
    @Test fun retryReachesCapturedSenderThroughAnUnrecognisedWrapperWriter() = runTest {
        // ChatSendCoordinator binds before the first conversation is acquired or sent to.
        val f = Fixture(this)
        f.sendQueued("retry through wrapper")
        f.dropAndReconnect()
        val screen = f.screen(FIRST)
        assertTrue(screen.retry(MessageId("cm-test-1")), "Retry binding must cross any writer wrapper")
        runCurrent()
        assertEquals(listOf(FIRST to "cm-test-1", FIRST to "cm-test-1"), f.channel.accepted)
        assertEquals(1, f.minted)
        screen.close()
    }

    @Test fun neverAcknowledgedQueuedSendSurvivesReloadAsNotSentInSendTimeOrder() = runTest {
        val f = Fixture(this)
        val screen = f.screen(FIRST)
        f.sendQueued("hey")
        runCurrent()
        assertTrue(assertIs<ChatRenderItem.Single>(screen.live.value.single()).message.isPending)
        f.dropAndReconnect()
        // Same text from another client is deliberately NOT an echo of our send.
        f.history[FIRST] = listOf(f.user("other", "hey", "cm-other", 20), f.reply("answer", 30))
        f.reload(FIRST)
        val rows = f.render(screen)
        val failed = rows.single { it.key == "msg-cm-test-1" }
        assertTrue(assertIs<ChatRenderItem.Single>(failed).message.isSendFailed,
            "never-acked prompt must render Not sent after reconnect and history reload")
        assertFalse(failed.message.isPending)
        assertEquals(listOf("answer", "hey", "hey"), rows.flatMap { item ->
            when (item) {
                is ChatRenderItem.Single -> listOf(item.message.content)
                is ChatRenderItem.RunBlock -> item.messages.map { it.first.content }
            }
        })
        assertEquals(3, rows.size)
        assertTrue(rows.first().containsMessageId("answer"))
        assertEquals(listOf("msg-cm-other", "msg-cm-test-1"), rows.drop(1).map { it.key })
        assertEquals(listOf("cm-test-1"), f.pending(FIRST).map { it.otid },
            "FALSE PASS guard: reload must retain the unconfirmed record, never clear pending wholesale")
        screen.close()
    }

    @Test fun persistedEchoReconcilesUnderTheOriginalOtidKeyWithoutContentDeduplication() = runTest {
        val f = Fixture(this)
        val screen = f.screen(FIRST)
        f.sendQueued("hey")
        runCurrent()
        val optimisticKey = screen.live.value.single().key
        f.dropAndReconnect()
        f.history[FIRST] = listOf(
            f.user("persisted", "hey", "cm-test-1", 10),
            f.user("equal-text", "hey", "cm-other", 20),
            f.reply("answer", 30),
        )
        f.reload(FIRST)
        val rows = f.render(screen)
        assertEquals(1, rows.count { it.key == optimisticKey })
        val echoed = assertIs<ChatRenderItem.Single>(rows.single { it.key == optimisticKey }).message
        assertEquals("cm-test-1", echoed.clientMessageId)
        assertEquals("persisted", echoed.id)
        assertFalse(echoed.isPending)
        assertFalse(echoed.isSendFailed)
        assertEquals(3, rows.size, "distinct persisted rows with equal text must both survive")
        assertEquals(emptyList(), f.pending(FIRST))
        screen.close()
    }

    @Test fun selectionSwitchAndLateReloadCannotMovePendingOrRetryToAnotherConversation() = runTest {
        val f = Fixture(this)
        val firstScreen = f.screen(FIRST)
        val secondScreen = f.screen(SECOND)
        f.sendQueued("hey", switchWhileQueued = true)
        assertEquals(listOf(FIRST), f.channel.accepted.map { it.first })
        f.dropAndReconnect()
        f.history[SECOND] = listOf(f.user("foreign-echo", "hey", "cm-test-1", 10))
        f.reload(SECOND)
        assertTrue(f.pending(FIRST).single().toRenderItem().message.isSendFailed)
        assertEquals(emptyList(), f.pending(SECOND))
        val second = f.render(secondScreen)
        assertEquals(listOf("foreign-echo"), second.map { assertIs<ChatRenderItem.Single>(it).message.id })
        f.reload(FIRST)
        assertEquals(listOf("msg-cm-test-1"), f.render(firstScreen).map { it.key })
        assertTrue(firstScreen.retry(MessageId("cm-test-1")))
        runCurrent()
        assertEquals(listOf(FIRST, FIRST), f.channel.accepted.map { it.first })
        assertEquals(listOf("cm-test-1", "cm-test-1"), f.channel.accepted.map { it.second })
        assertEquals(1, f.minted, "Retry must never call the identity generator")
        assertEquals(SECOND, f.selected, "a background Retry must not switch the visible conversation")
        assertFalse(firstScreen.retry(MessageId("cm-test-1")), "two Retry clicks must not send twice")
        assertFalse(secondScreen.retry(MessageId("cm-test-1")), "a confirmed or foreign record cannot be retried")
        firstScreen.close()
        secondScreen.close()
    }

    @Test fun restartReloadRetainsEveryUnconfirmedPromptAndOnlyConfirmsMatchingOtid() = runTest {
        val f = Fixture(this)
        f.sendQueued("first")
        f.sender.send("second")
        runCurrent()
        f.sender.send("persisted")
        runCurrent()
        // Lose the send owner entirely: recovery cannot depend on its disconnect callback.
        val restarted = CanonicalTimelineCoordinator(f.store, f.transport)
        val owner = restarted.acquire(f.scope(FIRST))
        f.history[FIRST] = listOf(f.user("persisted", "persisted", "cm-test-3", 10))
        assertEquals(TimelineEnginePageOutcome.Applied, restarted.reconcileRecent(owner))
        val screen = CanonicalTimelinePresentation.open(restarted, owner, backgroundScope)
        val rows = f.render(screen)
        assertEquals(setOf("cm-test-1", "cm-test-2"), owner.session.pending.value.map { it.otid }.toSet(),
            "FALSE PASS guard: a successful reload must not clear all pending records")
        assertEquals(2, rows.filterIsInstance<ChatRenderItem.Single>().count { it.message.isSendFailed })
        assertEquals(3, rows.size)
        screen.close()
    }

    private class Fixture(private val test: TestScope) {
        val store = ScopedStore()
        val history = mutableMapOf<String, List<LettaMessage>>()
        val channel = Channel()
        var selected = FIRST
        var minted = 0
        private val baseMillis = timelineCurrentTimeMillis()
        fun at(seconds: Int) = Instant.fromEpochMilliseconds(baseMillis + seconds * 1_000).toString()
        fun scope(conversation: String) = TimelineScope("backend", conversation, "agent")
        val transport = object : TimelineTransport by unexpectedTimelineTransport() {
            override suspend fun listConversationMessagePage(request: TimelineRemotePageRequest, progress: TimelinePageProgress?) =
                TimelineRemotePageAdapter.fromMessages(request, history[request.scope.conversationId].orEmpty(), hasMore = false)
        }
        private val coordinator = CanonicalTimelineCoordinator(store, transport)
        private val maintenance = IndexedCanonicalTimelineMaintenance(coordinator, test.backgroundScope, { _, _, _ -> }, { throw it })
        private val admitted = AdmittedTimelineExternalWriter(
            CanonicalExternalTransportWriter(coordinator, { _, conversation -> scope(conversation) }, maintenance, { at(10) }),
            TimelineLegacyAdmission(),
        )
        private val writer = object : TimelineExternalTransportWriter by admitted {
            private val pendingRetry = TimelinePendingRetryBinding()
            override fun bindPendingRetry(agent: AgentId, sender: suspend (com.letta.mobile.data.model.ConversationId, CanonicalPendingLocalStore.Record) -> Unit) =
                pendingRetry.bind(agent, sender)
            override suspend fun appendExternalTransportLocal(agentId: String?, conversationId: String, content: String, otid: String, attachments: List<com.letta.mobile.data.model.MessageContentPart.Image>) =
                admitted.also(pendingRetry::resolved).appendExternalTransportLocal(agentId, conversationId, content, otid, attachments)
        }
        val sender = ChatSendCoordinator(
            scope = test.backgroundScope, agentId = "agent",
            activeConfig = { LettaConfig("test", LettaConfig.Mode.SELF_HOSTED, "http://localhost:8291", "token") },
            wsChatBridge = WsChatBridge(channel), timelineRepository = writer,
            conversationRepository = object : IConversationRepository {
                override fun getConversations(agentId: AgentId) = emptyFlow<List<Conversation>>()
                override fun getCachedConversations(agentId: AgentId) = emptyList<Conversation>()
            },
            ui = Sink(), clearComposerAfterSend = {}, activeConversationId = { selected },
            setActiveConversationId = { selected = it }, startTimelineObserver = {},
            clientVersion = { "test" }, otidGenerator = { "cm-test-${++minted}" },
        )

        suspend fun screen(conversation: String) =
            CanonicalTimelinePresentation.open(coordinator, coordinator.acquire(scope(conversation)), test.backgroundScope)

        suspend fun sendQueued(text: String, switchWhileQueued: Boolean = false) {
            channel.busy = true
            sender.send(text)
            test.runCurrent()
            assertEquals(1, sender.sendQueue.state.value[QueueConversationId(FIRST)]?.items?.size)
            assertEquals(emptyList(), pending(FIRST), "queued sends acquire a bubble only when dispatched")
            if (switchWhileQueued) selected = SECOND
            channel.busy = false
            sender.sendQueue.resume(QueueConversationId(FIRST))
            test.runCurrent()
            assertEquals(listOf("cm-test-1"), pending(FIRST).map { it.otid })
        }

        suspend fun dropAndReconnect() {
            channel.state.value = ChannelTransportState.Idle
            sender.handleEvent(WsTimelineEvent.Disconnected(1006, "transport dropped", willReconnect = true))
            channel.state.value = ChannelTransportState.Connected("server", "session-2", "device")
        }

        suspend fun reload(conversation: String) {
            assertIs<RecentMessagesReconcileOutcome.Applied>(writer.reconcileRecentMessages(
                "agent", conversation, "redial-recovery", forceRefresh = true, connectionGeneration = 2,
            ))
        }

        suspend fun pending(conversation: String) = CanonicalPendingLocalStore(store).load(scope(conversation))
        fun user(id: String, text: String, otid: String, second: Int) =
            UserMessage(id, contentRaw = JsonPrimitive(text), otid = otid, date = at(second))
        fun reply(id: String, second: Int) = AssistantMessage(id, contentRaw = JsonPrimitive(id), date = at(second))

        fun render(screen: CanonicalTimelinePresentation): List<ChatRenderItem> {
            val presenter = RecordingPresenter<CanonicalTimelinePresentation.Row>(StandardTestDispatcher(test.testScheduler))
            val residency = test.backgroundScope.launch {
                presenter.onPagesUpdatedFlow.collect { screen.onResidentRows(presenter.snapshot().items) }
            }
            val collection = test.backgroundScope.launch { screen.settled.collectLatest { presenter.collectFrom(it) } }
            test.runCurrent()
            presenter.assertIdle()
            val settled = presenter.snapshot().items
            screen.onResidentRows(settled)
            test.runCurrent()
            val assembly = TimelineRowAssembly.assemble(screen.live.value, settled.map { it.item.key })
            assertEquals(emptyMap(), assembly.duplicates)
            collection.cancel()
            residency.cancel()
            return assembly.live + settled.map { it.item }
        }
    }

    private class ScopedStore : TimelineBoundedStore {
        private val stores = mutableMapOf<TimelineScope, InMemoryTimelineStore>()
        private fun store(scope: TimelineScope) = stores.getOrPut(scope) { InMemoryTimelineStore() }
        override suspend fun <T> read(scope: TimelineScope, block: suspend TimelineStoreReader.() -> T): T = store(scope).read(scope, block)
        override suspend fun <T> transaction(scope: TimelineScope, block: suspend TimelineStoreTransaction.() -> T): T =
            store(scope).transaction(scope, block)
    }

    private class Channel : IChannelTransport by unexpectedChannel() {
        override val state = MutableStateFlow<ChannelTransportState>(ChannelTransportState.Connected("server", "session", "device"))
        override val events = MutableSharedFlow<ServerFrame>()
        override val frameEvents = MutableSharedFlow<TransportFrameEvent>()
        override val hostCapabilities = emptySet<String>()
        override val hostNodeId: String? = null
        override val hasAnyActiveChatTurn: Boolean get() = busy
        var busy = false
        val accepted = mutableListOf<Pair<String, String?>>()
        override fun hasActiveChatTurn(conversationId: String) = busy
        override fun send(
            agentId: String, conversationId: String, text: String, otid: String?, contentParts: JsonArray?, startNewConversation: Boolean,
        ): Boolean {
            if (busy) return false
            accepted += conversationId to otid
            return true
        }
    }

    private class Sink : ChatSendUiSink {
        override fun currentError(): String? = null
        override fun isStreaming() = false
        override fun isAgentTyping() = false
        override fun onSendDispatched(conversationId: String?) = Unit
        override fun onSendQueued(conversationId: String) = Unit
        override fun onSendFailed(message: String) = fail(message)
        override fun onError(message: String?) = Unit
        override fun onTurnStarted(conversationId: String) = Unit
        override fun onMessageDelta(conversationId: String) = Unit
        override fun onUsage(promptTokens: Int, completionTokens: Int, totalTokens: Int) = Unit
        override fun onTurnFinished(error: String?) = Unit
        override fun onTurnVisuallyComplete() = Unit
        override fun onTransientDisconnect(hasActiveSend: Boolean) = Unit
        override fun onDisconnectFailure(error: String) = Unit
    }

    private companion object {
        const val FIRST = "local-conv-95805990"
        const val SECOND = "local-conv-95805992"
        fun unexpectedChannel(): IChannelTransport = Proxy.newProxyInstance(
            IChannelTransport::class.java.classLoader, arrayOf(IChannelTransport::class.java),
        ) { _, method, _ -> fail("unexpected channel call: ${method.name}") } as IChannelTransport
    }
}
