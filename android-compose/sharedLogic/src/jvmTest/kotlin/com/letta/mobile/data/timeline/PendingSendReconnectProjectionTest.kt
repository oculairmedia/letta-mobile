package com.letta.mobile.data.timeline

import com.letta.mobile.data.chat.projection.ChatRenderItem
import com.letta.mobile.data.chat.projection.TimelineRowAssembly
import com.letta.mobile.data.chat.send.ChatSendCoordinator
import com.letta.mobile.data.chat.send.ChatSendUiSink
import com.letta.mobile.data.chat.send.QueueConversationId
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.AssistantMessage
import com.letta.mobile.data.model.Conversation
import com.letta.mobile.data.model.ConversationId
import com.letta.mobile.data.model.LettaConfig
import com.letta.mobile.data.model.LettaMessage
import com.letta.mobile.data.model.MessageId
import com.letta.mobile.data.model.MessageContentPart
import com.letta.mobile.data.model.UserMessage
import com.letta.mobile.data.repository.api.IConversationRepository
import com.letta.mobile.data.timeline.api.TimelinePendingRetryBinding
import com.letta.mobile.data.timeline.api.TimelinePendingSendAttempt
import com.letta.mobile.data.timeline.api.TimelineExternalTransportWriter
import com.letta.mobile.data.timeline.snapshot.TimelineScope
import com.letta.mobile.data.transport.ChannelTransportState
import com.letta.mobile.data.transport.ServerFrame
import com.letta.mobile.data.transport.TransportFrameEvent
import com.letta.mobile.data.transport.WsChatBridge
import com.letta.mobile.data.transport.WsTimelineEvent
import com.letta.mobile.data.transport.api.IChannelTransport
import com.letta.mobile.data.transport.api.RedialAwareChannelTransport
import com.letta.mobile.data.transport.api.RedialWhileTurnActive
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import kotlinx.serialization.json.JsonPrimitive
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** The send queue, bridge, scoped writer, history adapter, reducer, pager and rendered row assembly. */
@OptIn(ExperimentalCoroutinesApi::class)
class PendingSendReconnectProjectionTest {
    @Test fun confirmedRetryQueuedBehindActiveTurnNeverDispatchesAgain() = resolveQueuedRetry(dismiss = false)
    @Test fun dismissedRetryQueuedBehindActiveTurnNeverDispatchesAgain() = resolveQueuedRetry(dismiss = true)

    private fun resolveQueuedRetry(dismiss: Boolean) = runTest {
        val f = Fixture(this)
        f.sendQueued("active turn")
        f.sender.handleEvent(WsTimelineEvent.TurnStarted(
            agentId = "agent", conversationId = FIRST.value, turnId = "turn-active", runId = "run-active",
        ))
        val screen = f.screen(FIRST)
        val retry = Prompt(MessageId("retry-otid"), "already persisted")
        f.failed(FIRST, retry)
        f.channel.busy = true
        assertTrue(screen.retry(retry.otid))
        // The retry waits in the outbox until the sender's subscription submits it.
        runCurrent()
        assertEquals(1, f.sender.sendQueue.state.value[QueueConversationId(FIRST.value)]?.items?.size)
        if (dismiss) {
            f.writer.markPendingSend(com.letta.mobile.data.timeline.api.TimelinePendingSendAttempt(
                AgentId("agent"), FIRST, retry.otid, 1,
            ), CanonicalPendingLocalStore.Delivery.Failed)
            assertTrue(screen.dismiss(retry.otid))
        } else {
            f.history[FIRST] = listOf(f.user(HistoryUser(MessageId("echo"), retry, 20.seconds)))
            f.reload(FIRST)
        }
        assertTrue(f.pending(FIRST).none { it.otid == retry.otid.value })
        f.channel.busy = false
        f.sender.handleEvent(WsTimelineEvent.TurnDone(
            "turn-active", "run-active", com.letta.mobile.data.transport.BridgeTurnStatus.Completed,
        ))
        runCurrent()
        assertEquals(listOf("cm-test-1"), f.channel.accepted.map { it.otid?.value })
        assertTrue(f.sender.sendQueue.state.value.isEmpty())
        screen.close()
    }

    @Test fun transientRedialPreservesAnOwnedTurnButFailsAnUnownedUnacknowledgedSend() = runTest {
        val f = Fixture(this)
        f.sendQueued("survive redial")
        f.channel.busy = true
        try {
            f.dropAndReconnect()
            assertEquals(CanonicalPendingLocalStore.Delivery.Sending, f.pending(FIRST).single().delivery)
        } finally {
            f.channel.busy = false
        }
        f.dropAndReconnect()
        assertEquals(CanonicalPendingLocalStore.Delivery.Failed, f.pending(FIRST).single().delivery)
    }

    @Test fun redialWithoutResumedFramesFailsPendingWithinThirtySeconds() = runTest {
        val f = Fixture(this)
        f.sendQueued("lost stream")
        f.streaming = true
        f.sender.handleEvent(WsTimelineEvent.TurnStarted("turn", "agent", FIRST.value, "run"))
        f.channel.busy = true
        f.dropAndReconnect()
        advanceTimeBy(29.seconds)
        runCurrent()
        assertEquals(CanonicalPendingLocalStore.Delivery.Sent, f.pending(FIRST).single().delivery)
        advanceTimeBy(1.seconds)
        runCurrent()
        assertEquals(CanonicalPendingLocalStore.Delivery.Failed, f.pending(FIRST).single().delivery)
    }

    @Test fun failedRedialRecoveryReleasesLocalAndTransportOwnership() = runTest {
        val f = Fixture(this)
        f.sendQueued("failed recovery")
        f.streaming = true
        f.sender.handleEvent(WsTimelineEvent.TurnStarted("turn", "agent", FIRST.value, "run"))
        f.channel.busy = true
        f.dropAndReconnect()
        f.historyFailure = IllegalStateException("history unavailable")
        f.channel.redials.emit(RedialWhileTurnActive("agent", FIRST.value, "turn", "run", 2))
        runCurrent()
        assertEquals(CanonicalPendingLocalStore.Delivery.Failed, f.pending(FIRST).single().delivery)
    }

    @Test fun hungRedialHistoryReadTimesOutAndReleasesOwnership() = runTest {
        val f = Fixture(this)
        f.sendQueued("hung recovery")
        f.streaming = true
        f.sender.handleEvent(WsTimelineEvent.TurnStarted("turn", "agent", FIRST.value, "run"))
        f.channel.busy = true
        f.dropAndReconnect()
        f.historyGate = CompletableDeferred()
        f.channel.redials.emit(RedialWhileTurnActive("agent", FIRST.value, "turn", "run", 2))
        runCurrent()
        advanceTimeBy(30.seconds)
        runCurrent()
        assertEquals(CanonicalPendingLocalStore.Delivery.Failed, f.pending(FIRST).single().delivery)
    }

    // softs (#2258 deferred defect 1): the redial history read no longer holds the turn lock.
    @Test fun streamFramesAreNotHeldBehindASlowRedialHistoryRead() = runTest {
        val f = Fixture(this)
        f.sendQueued("slow recovery")
        f.streaming = true
        f.sender.handleEvent(WsTimelineEvent.TurnStarted("turn", "agent", FIRST.value, "run"))
        f.channel.busy = true
        f.dropAndReconnect()
        f.historyGate = CompletableDeferred()
        f.channel.redials.emit(RedialWhileTurnActive("agent", FIRST.value, "turn", "run", 2))
        runCurrent()
        val delta = launch {
            f.sender.handleEvent(WsTimelineEvent.MessageDelta(
                // Its own id: message-delta dedupe keys are process-wide, and resumedFramesCancelTheRedialDeadline
                // sends "live" in the same fixture conversation.
                message = f.reply(MessageId("live-during-read"), 5.seconds), agentId = "agent", conversationId = FIRST.value,
                turnId = "turn",
            ))
        }
        runCurrent()
        assertTrue(delta.isCompleted, "a resumed stream frame must not wait for the history read")
        // The read then times out, but the stream that resumed the turn owns it now.
        advanceTimeBy(30.seconds)
        runCurrent()
        assertEquals(CanonicalPendingLocalStore.Delivery.Sent, f.pending(FIRST).single().delivery)
    }

    @Test fun resumedFramesCancelTheRedialDeadline() = runTest {
        val f = Fixture(this)
        f.sendQueued("resumed stream")
        f.streaming = true
        f.sender.handleEvent(WsTimelineEvent.TurnStarted("turn", "agent", FIRST.value, "run"))
        f.dropAndReconnect()
        advanceTimeBy(20.seconds)
        f.sender.handleEvent(WsTimelineEvent.MessageDelta(
            message = f.reply(MessageId("live"), 20.seconds), agentId = "agent", conversationId = FIRST.value, turnId = "turn",
        ))
        advanceTimeBy(20.seconds)
        runCurrent()
        assertEquals(CanonicalPendingLocalStore.Delivery.Sent, f.pending(FIRST).single().delivery)
    }

    @Test fun fullPendingBudgetRefusesSendBeforeTransportAndShowsErrorWithoutEviction() = runTest {
        val f = Fixture(this, captureSendFailures = true)
        repeat(CanonicalPendingLocalStore.MAX_RECORDS) { index -> f.failed(FIRST, Prompt(MessageId("old-$index"), "keep $index")) }
        val previous = f.pending(FIRST)
        f.sender.send("overflow")
        runCurrent()
        assertTrue(f.channel.accepted.isEmpty(), "the transport must not accept an unrecordable send")
        assertEquals(previous, f.pending(FIRST), "every existing pending record must survive")
        assertTrue(f.sendFailures.single().contains("Dismiss an unsent message"))
        assertTrue(f.sender.sendQueue.state.value.isEmpty(), "a refused send is not silently queued")
    }

    @Test fun staleFailureAndSentCallbacksCannotChangeActiveRetry() = runTest {
        val f = Fixture(this)
        f.sendQueued("retry me")
        f.dropAndReconnect()
        val screen = f.screen(FIRST)
        assertTrue(screen.retry(MessageId("cm-test-1")))
        val stale = com.letta.mobile.data.timeline.api.TimelinePendingSendAttempt(
            AgentId("agent"), FIRST, MessageId("cm-test-1"), 0,
        )
        f.writer.markPendingSend(stale, CanonicalPendingLocalStore.Delivery.Failed)
        f.writer.markPendingSend(stale, CanonicalPendingLocalStore.Delivery.Sent)
        f.writer.markExternalTransportLocalFailed("agent", FIRST.value, "cm-test-1")
        f.writer.markExternalTransportLocalSent("agent", FIRST.value, "cm-test-1")
        assertEquals(CanonicalPendingLocalStore.Delivery.Sending, f.pending(FIRST).single().delivery)
        f.writer.markPendingSend(stale.copy(generation = 1), CanonicalPendingLocalStore.Delivery.Failed)
        assertEquals(CanonicalPendingLocalStore.Delivery.Failed, f.pending(FIRST).single().delivery)
        screen.close()
    }

    @Test fun replacementRecoveryKeepsALiveSendUntilItsProcessIsGone() = runTest {
        val f = Fixture(this)
        f.sendQueued("still active")
        val replacement = CanonicalTimelineCoordinator(f.store, f.transport)
        val owner = replacement.acquire(f.scope(FIRST))
        assertEquals(TimelineEnginePageOutcome.Applied, replacement.reconcileRecent(owner))
        assertEquals(CanonicalPendingLocalStore.Delivery.Sending, f.pending(FIRST).single().delivery)
        // Within the horizon a retired sender's prompt stays in flight; a later process lets history decide.
        f.retireSender()
        assertEquals(TimelineEnginePageOutcome.Applied, replacement.reconcileRecent(owner))
        assertEquals(CanonicalPendingLocalStore.Delivery.Sending, f.pending(FIRST).single().delivery)
        f.store.fromAnotherProcess(f.scope(FIRST))
        assertEquals(TimelineEnginePageOutcome.Applied, replacement.reconcileRecent(owner))
        assertEquals(CanonicalPendingLocalStore.Delivery.Failed, f.pending(FIRST).single().delivery)
    }

    @Test fun replacementRecoveryKeepsServerOwnedTurnAfterSenderRetirement() = runTest {
        val f = Fixture(this)
        f.sendQueued("accepted server turn")
        f.channel.busy = true
        f.retireSender(stopTransport = false)
        val replacement = CanonicalTimelineCoordinator(f.store, f.transport)
        val owner = replacement.acquire(f.scope(FIRST))
        try {
            assertEquals(TimelineEnginePageOutcome.Applied, replacement.reconcileRecent(owner))
            assertEquals(CanonicalPendingLocalStore.Delivery.Sending, f.pending(FIRST).single().delivery)
        } finally {
            f.channel.busy = false
        }
        f.store.fromAnotherProcess(f.scope(FIRST))
        assertEquals(TimelineEnginePageOutcome.Applied, replacement.reconcileRecent(owner))
        assertEquals(CanonicalPendingLocalStore.Delivery.Failed, f.pending(FIRST).single().delivery)
    }

    @Test fun dismissLiveFailureIsScopedToItsOwnConversationAndOtid() = runTest {
        val f = Fixture(this)
        val first = f.screen(FIRST)
        val second = f.screen(SECOND)
        f.sendQueued("original")
        assertFalse(first.dismiss(MessageId("cm-test-1")), "in-flight sends cannot be dismissed")
        f.dropAndReconnect()
        f.failed(FIRST, Prompt(MessageId("cm-extra"), "keep this failure"))
        f.failed(SECOND, Prompt(MessageId("cm-test-1"), "same OTID in another conversation"))
        runCurrent()
        assertEquals(2, first.live.value.filterIsInstance<ChatRenderItem.Single>().count { it.message.isSendFailed })
        f.selected = SECOND
        assertFalse(second.dismiss(MessageId("cm-extra")), "another conversation cannot dismiss this bubble")
        assertTrue(first.dismiss(MessageId("cm-test-1")))
        runCurrent()
        assertEquals(listOf("cm-extra"), f.pending(FIRST).map { it.otid })
        assertEquals(listOf("cm-test-1"), f.pending(SECOND).map { it.otid })
        assertEquals(listOf("msg-cm-extra"), first.live.value.map { it.key })
        assertFalse(first.dismiss(MessageId("cm-test-1")), "a second click is harmless")
        first.close()
        second.close()
    }

    @Test fun dismissPagedFailureRemovesOnlyItsPendingRow() = runTest {
        val f = Fixture(this)
        val screen = f.screen(FIRST)
        f.sendQueued("dismiss from history")
        f.dropAndReconnect()
        f.history[FIRST] = listOf(f.reply(MessageId("newer"), 30.seconds))
        f.reload(FIRST)
        val rows = f.render(screen)
        assertEquals(2, rows.size)
        assertTrue(rows.first().containsMessageId("newer"))
        assertEquals("msg-cm-test-1", rows.last().key)
        assertTrue(screen.live.value.none { it.key == "msg-cm-test-1" }, "failure must be in the paged path")
        assertTrue(screen.dismiss(MessageId("cm-test-1")))
        assertEquals(emptyList(), f.pending(FIRST))
        assertTrue(f.render(screen).none { it.key == "msg-cm-test-1" })
        screen.close()
    }

    @Test fun retryReachesCapturedSenderThroughAnUnrecognisedWrapperWriter() = runTest {
        // ChatSendCoordinator subscribes before the first conversation is acquired or sent to.
        val f = Fixture(this)
        f.sendQueued("retry through wrapper")
        f.dropAndReconnect()
        val screen = f.screen(FIRST)
        assertTrue(screen.retry(MessageId("cm-test-1")), "Queued retries must cross any writer wrapper")
        runCurrent()
        assertEquals(
            listOf(AcceptedSend(FIRST, MessageId("cm-test-1")), AcceptedSend(FIRST, MessageId("cm-test-1"))),
            f.channel.accepted.toList(),
        )
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
        f.history[FIRST] = listOf(
            f.user(HistoryUser(MessageId("other"), Prompt(MessageId("cm-other"), "hey"), 20.seconds)),
            f.reply(MessageId("answer"), 30.seconds),
        )
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
            f.user(HistoryUser(MessageId("persisted"), Prompt(MessageId("cm-test-1"), "hey"), 10.seconds)),
            f.user(HistoryUser(MessageId("equal-text"), Prompt(MessageId("cm-other"), "hey"), 20.seconds)),
            f.reply(MessageId("answer"), 30.seconds),
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
        assertEquals(listOf(FIRST), f.channel.accepted.map { it.conversation })
        f.dropAndReconnect()
        f.history[SECOND] = listOf(
            f.user(HistoryUser(MessageId("foreign-echo"), Prompt(MessageId("cm-test-1"), "hey"), 10.seconds)),
        )
        f.reload(SECOND)
        assertTrue(f.pending(FIRST).single().toRenderItem().message.isSendFailed)
        assertEquals(emptyList(), f.pending(SECOND))
        val second = f.render(secondScreen)
        assertEquals(listOf("foreign-echo"), second.map { assertIs<ChatRenderItem.Single>(it).message.id })
        f.reload(FIRST)
        assertEquals(listOf("msg-cm-test-1"), f.render(firstScreen).map { it.key })
        assertTrue(firstScreen.retry(MessageId("cm-test-1")))
        runCurrent()
        assertEquals(listOf(FIRST, FIRST), f.channel.accepted.map { it.conversation })
        assertEquals(listOf("cm-test-1", "cm-test-1"), f.channel.accepted.map { it.otid?.value })
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
        f.retireSender()
        f.store.fromAnotherProcess(f.scope(FIRST))
        val restarted = CanonicalTimelineCoordinator(f.store, f.transport)
        val owner = restarted.acquire(f.scope(FIRST))
        f.history[FIRST] = listOf(
            f.user(HistoryUser(MessageId("persisted"), Prompt(MessageId("cm-test-3"), "persisted"), 10.seconds)),
        )
        assertEquals(TimelineEnginePageOutcome.Applied, restarted.reconcileRecent(owner))
        val screen = CanonicalTimelinePresentation.open(restarted, owner, backgroundScope)
        val rows = f.render(screen)
        assertEquals(setOf("cm-test-1", "cm-test-2"), owner.session.pending.value.map { it.otid }.toSet(),
            "FALSE PASS guard: a successful reload must not clear all pending records")
        assertEquals(2, rows.filterIsInstance<ChatRenderItem.Single>().count { it.message.isSendFailed })
        assertEquals(3, rows.size)
        screen.close()
    }

    private class Fixture(private val test: TestScope, private val captureSendFailures: Boolean = false) {
        val store = ScopedStore()
        val history = mutableMapOf<ConversationId, List<LettaMessage>>()
        val channel = Channel()
        var streaming = false
        val sendFailures = mutableListOf<String>()
        var historyFailure: Throwable? = null
        var historyGate: CompletableDeferred<Unit>? = null
        var selected = FIRST
        var minted = 0
        private val baseMillis = timelineCurrentTimeMillis()
        fun at(delay: Duration) = Instant.fromEpochMilliseconds(baseMillis + delay.inWholeMilliseconds).toString()
        fun scope(conversation: ConversationId) = TimelineScope("backend", conversation.value, "agent")
        val transport = object : TimelineTransport by unexpectedTimelineTransport() {
            override suspend fun listConversationMessagePage(
                request: TimelineRemotePageRequest, progress: TimelinePageProgress?,
            ): TimelineRemotePageResult {
                historyGate?.await()
                historyFailure?.let { throw it }
                return TimelineRemotePageAdapter.fromMessages(
                    request, history[ConversationId(request.scope.conversationId)].orEmpty(), hasMore = false,
                )
            }
        }
        private val coordinator = CanonicalTimelineCoordinator(store, transport)
        private val maintenance = IndexedCanonicalTimelineMaintenance(
            coordinator, test.backgroundScope, { _, _, _ -> }, { throw it },
        )
        private val admitted = AdmittedTimelineExternalWriter(
            CanonicalExternalTransportWriter(
                coordinator, { _, conversation -> scope(ConversationId(conversation)) }, maintenance, { at(10.seconds) },
            ),
            TimelineLegacyAdmission(),
        )
        val writer = object : TimelineExternalTransportWriter by admitted {
            private val pendingRetry = TimelinePendingRetryBinding()
            override fun queuedRetries(agent: AgentId) = pendingRetry.queuedRetries(agent)
            override suspend fun acceptPendingSend(
                attempt: TimelinePendingSendAttempt, content: String, attachments: List<MessageContentPart.Image>, accept: () -> Boolean,
            ) = admitted.also(pendingRetry::resolved).acceptPendingSend(attempt, content, attachments, accept)
        }
        private val senderJob = kotlinx.coroutines.SupervisorJob(test.backgroundScope.coroutineContext[kotlinx.coroutines.Job])
        private val senderScope = kotlinx.coroutines.CoroutineScope(test.backgroundScope.coroutineContext + senderJob)
        fun retireSender(stopTransport: Boolean = true) {
            senderJob.cancel()
            if (stopTransport) channel.busy = false
        }
        val sender = ChatSendCoordinator(
            scope = senderScope, agentId = "agent",
            activeConfig = { LettaConfig("test", LettaConfig.Mode.SELF_HOSTED, "http://localhost:8291", "token") },
            wsChatBridge = WsChatBridge(channel.transport), timelineRepository = writer,
            conversationRepository = object : IConversationRepository {
                override fun getConversations(agentId: AgentId) = emptyFlow<List<Conversation>>()
                override fun getCachedConversations(agentId: AgentId) = emptyList<Conversation>()
            },
            ui = object : ChatSendUiSink by sink() {
                override fun isStreaming() = streaming
                override fun onSendFailed(message: String) {
                    if (!captureSendFailures) fail(message)
                    sendFailures += message
                }
            }, clearComposerAfterSend = {}, activeConversationId = { selected.value },
            setActiveConversationId = { selected = ConversationId(it) }, startTimelineObserver = {},
            clientVersion = { "test" }, otidGenerator = { "cm-test-${++minted}" },
        )

        suspend fun screen(conversation: ConversationId) =
            CanonicalTimelinePresentation.open(coordinator, coordinator.acquire(scope(conversation)), test.backgroundScope)

        suspend fun sendQueued(text: String, switchWhileQueued: Boolean = false) {
            channel.busy = true
            sender.send(text)
            test.runCurrent()
            assertEquals(1, sender.sendQueue.state.value[QueueConversationId(FIRST.value)]?.items?.size)
            assertEquals(emptyList(), pending(FIRST), "queued sends acquire a bubble only when dispatched")
            if (switchWhileQueued) selected = SECOND
            channel.busy = false
            sender.sendQueue.resume(QueueConversationId(FIRST.value))
            test.runCurrent()
            assertEquals(listOf("cm-test-1"), pending(FIRST).map { it.otid })
        }

        suspend fun dropAndReconnect() {
            channel.state.value = ChannelTransportState.Idle
            sender.handleEvent(WsTimelineEvent.Disconnected(1006, "transport dropped", willReconnect = true))
            channel.state.value = ChannelTransportState.Connected("server", "session-2", "device")
        }

        suspend fun reload(conversation: ConversationId) {
            assertIs<RecentMessagesReconcileOutcome.Applied>(writer.reconcileRecentMessages(
                "agent", conversation.value, "redial-recovery", forceRefresh = true, connectionGeneration = 2,
            ))
        }

        suspend fun failed(conversation: ConversationId, prompt: Prompt) {
            writer.appendExternalTransportLocal("agent", conversation.value, prompt.content, prompt.otid.value, emptyList())
            writer.markExternalTransportLocalFailed("agent", conversation.value, prompt.otid.value)
        }

        suspend fun pending(conversation: ConversationId) = CanonicalPendingLocalStore(store).load(scope(conversation))
        fun user(row: HistoryUser) = UserMessage(
            row.id.value, contentRaw = JsonPrimitive(row.prompt.content), otid = row.prompt.otid.value, date = at(row.delay),
        )
        fun reply(id: MessageId, delay: Duration) =
            AssistantMessage(id.value, contentRaw = JsonPrimitive(id.value), date = at(delay))

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

    private data class Prompt(val otid: MessageId, val content: String)
    private data class HistoryUser(val id: MessageId, val prompt: Prompt, val delay: Duration)
    private data class AcceptedSend(val conversation: ConversationId, val otid: MessageId?)

    private class Channel {
        val state = MutableStateFlow<ChannelTransportState>(ChannelTransportState.Connected("server", "session", "device"))
        private val events = MutableSharedFlow<ServerFrame>()
        private val frameEvents = MutableSharedFlow<TransportFrameEvent>()
        var busy = false
        val accepted = mutableListOf<AcceptedSend>()
        // Adapt the legacy wire call once; acceptance and assertions share typed send identities.
        val redials = MutableSharedFlow<RedialWhileTurnActive>()
        val transport: IChannelTransport = object : IChannelTransport by proxy<IChannelTransport>(::invoke), RedialAwareChannelTransport {
            override val redialWhileTurnActive = redials
        }

        private fun invoke(method: Method, args: Array<out Any?>): Any? = when (method.name) {
            "send" -> accept(AcceptedSend(ConversationId(args[1] as String), args[3]?.let { MessageId(it as String) }))
            "hasActiveChatTurn", "getHasAnyActiveChatTurn" -> busy
            else -> property(method)
        }

        private fun property(method: Method): Any? = when (method.name) {
            "getState" -> state
            "getEvents" -> events
            "getFrameEvents" -> frameEvents
            "getHostCapabilities" -> emptySet<String>()
            "getHostNodeId" -> null
            else -> fail("unexpected channel call: ${method.name}")
        }

        private fun accept(send: AcceptedSend): Boolean {
            if (busy) return false
            accepted += send
            return true
        }
    }

    private companion object {
        val FIRST = ConversationId("local-conv-95805990")
        val SECOND = ConversationId("local-conv-95805992")

        fun sink(): ChatSendUiSink = proxy { method, args ->
            when (method.name) {
                "currentError" -> null
                "isStreaming", "isAgentTyping" -> false
                "onSendFailed" -> fail(args.single() as String)
                else -> Unit
            }
        }

        inline fun <reified T> proxy(crossinline invoke: (Method, Array<out Any?>) -> Any?): T =
            Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, args ->
                invoke(method, args.orEmpty())
            } as T
    }
}
