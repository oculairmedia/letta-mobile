package com.letta.mobile.di

import com.letta.mobile.feature.chat.coordination.SelectedChatRuntime
import com.letta.mobile.feature.chat.screen.ChatPagingHost
import com.letta.mobile.feature.chat.screen.ChatPagingPresentation
import com.letta.mobile.testutil.FakeTimelineExternalTransportWriter
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.ConversationId
import com.letta.mobile.data.model.MessageId
import com.letta.mobile.data.model.MessageContentPart
import com.letta.mobile.data.timeline.api.TimelineExternalTransportWriter
import com.letta.mobile.data.timeline.api.TimelinePendingSendAttempt
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class SelectedRuntimeBindingTest {
    @Test fun subscriptionsCaptureSeparateHandlesAndConsumerOwnsRetirement() = runTest {
        val firstGraph = Any()
        val graphs = MutableStateFlow(firstGraph)
        val created = mutableListOf<SelectedChatRuntime>()
        fun flow() = SubscriberRuntimeFlow(graphs) {
            mockk<SelectedChatRuntime>(relaxed = true).also { created += it }
        }
        val first = flow()
        val second = flow()
        assertNotSame(first.value, second.value)
        val old = checkNotNull(first.value)
        var observed: SelectedChatRuntime? = null
        val collector = launch(start = CoroutineStart.UNDISPATCHED) { first.collect { observed = it } }
        assertSame(old, observed)
        graphs.value = Any()
        runCurrent()
        assertNotSame(old, observed)
        coVerify(exactly = 0) { old.retire() }
        collector.cancelAndJoin()
        coVerify(exactly = 0) { old.retire() }
    }

    @Test fun capturedWriterRejectsForeignAgentBeforeResolution() = runTest {
        var resolutions = 0
        val writer = SelectedRuntimeWriter("agent-a") { resolutions++; error("not expected") }
        try {
            writer.markExternalTransportLocalSent("agent-b", "conversation", "otid")
            fail("Foreign agent accepted")
        } catch (_: IllegalArgumentException) { }
        assertEquals(0, resolutions)
    }

    @Test fun pendingBudgetRefusalReachesCapturedWriterBeforeTransportAcceptance() = runTest {
        val delegate = object : TimelineExternalTransportWriter by FakeTimelineExternalTransportWriter() {
            override suspend fun acceptPendingSend(
                attempt: TimelinePendingSendAttempt, content: String, attachments: List<MessageContentPart.Image>, accept: () -> Boolean,
            ): Boolean = throw IllegalArgumentException("Pending row budget exceeded")
        }
        val writer = SelectedRuntimeWriter("agent") { delegate }
        var accepted = false
        try {
            writer.acceptPendingSend(
                TimelinePendingSendAttempt(AgentId("agent"), ConversationId("conversation"), MessageId("otid"), 0),
                "overflow", emptyList(),
            ) { accepted = true; true }
            fail("Budget refusal must propagate")
        } catch (failure: IllegalArgumentException) {
            assertEquals("Pending row budget exceeded", failure.message)
        }
        assertFalse("The selected writer must preserve canonical admission", accepted)
    }

    @Test fun expiryDelegatesThroughResolvedWriter() = runTest {
        val delegate = mockk<com.letta.mobile.data.timeline.api.TimelineExternalTransportWriter>(relaxed = true)
        var resolutions = 0
        val writer = SelectedRuntimeWriter("agent") { resolutions++; delegate }
        writer.repairExpiredConversationCursorScoped("agent", "conversation", fallbackSeq = 12L, expectedWatermark = 4L)
        // The name's claim is that the call goes through a freshly resolved writer, not a captured one.
        assertEquals(1, resolutions)
        coVerify(exactly = 1) { delegate.repairExpiredConversationCursorScoped("agent", "conversation", 12L) }
    }

    @Test fun readyResolvedConversationJoinsQueuedRetries() = retryAfterRecreation(open = false)
    @Test fun openResolvedConversationJoinsQueuedRetries() = retryAfterRecreation(open = true)

    private fun retryAfterRecreation(open: Boolean) = runTest {
        val delegate = mockk<com.letta.mobile.data.timeline.api.TimelineExternalTransportWriter>(relaxed = true)
        val presentation = ChatPagingPresentation(
            settled = flowOf(androidx.paging.PagingData.empty()), live = MutableStateFlow(emptyList()), close = {},
        )
        val binding = mockk<AndroidCanonicalTimelineRuntime.Binding>()
        every { binding.writer } returns delegate
        every { binding.bindPresentation(any()) } answers {
            (invocation.args[0] as ChatPagingHost).openCanonical = { _, _, _, _ -> presentation }
        }
        val captured = CapturedSelectedChatRuntime(
            generation = 9L, config = mockk(relaxed = true), descriptor = mockk(relaxed = true),
            parent = this, agent = "agent", hooks = ConversationBindHooks(bind = { binding }, retire = {}),
        )
        val agent = com.letta.mobile.data.model.AgentId("agent")
        val retry = com.letta.mobile.data.model.ConversationId("persisted-conversation") to
            com.letta.mobile.data.timeline.CanonicalPendingLocalStore.Record(
                "retry-otid", "prompt", emptyList(), "2026-01-01T00:00:00Z", attempt = 1,
            )
        every { delegate.queuedRetries(agent) } returns MutableStateFlow(listOf(retry))
        try {
            // The sender subscribes before any conversation resolves; later ones still reach it.
            val retries = captured.writer.queuedRetries(agent)
            assertTrue(retries.first().isEmpty())
            if (open) captured.open("persisted-conversation", null, this) else captured.ready("persisted-conversation")
            assertEquals(listOf(retry), retries.first())
        } finally {
            captured.retire()
        }
    }

    @Test fun capturedOpenAndSendStayOnCanonicalTogether() = runTest {
        val canonicalWriter = FakeTimelineExternalTransportWriter()
        val presentation = ChatPagingPresentation(
            settled = flowOf(androidx.paging.PagingData.empty()),
            live = MutableStateFlow(emptyList()),
            close = {},
        )
        val binding = mockk<AndroidCanonicalTimelineRuntime.Binding>()
        every { binding.writer } returns canonicalWriter
        every { binding.bindPresentation(any()) } answers {
            (invocation.args[0] as ChatPagingHost).openCanonical = { _, _, _, _ -> presentation }
        }
        val captured = CapturedSelectedChatRuntime(
            generation = 8L,
            config = mockk(relaxed = true),
            descriptor = mockk(relaxed = true),
            parent = this,
            agent = "agent",
            hooks = ConversationBindHooks(
                bind = { binding },
                retire = {},
            ),
        )
        captured.ready("normalized")
        assertSame(presentation, captured.open("normalized", "target", this))
        captured.writer.appendExternalTransportLocal("agent", "normalized", "hello", "otid")
        captured.writer.markExternalTransportLocalSent("agent", "normalized", "otid")
        captured.writer.reconcileExternalTransportSend("normalized", "agent", "normalized", "otid")
        captured.writer.reconcileRecentMessages("agent", "normalized", "post-send", forceRefresh = true)
        assertEquals("otid", canonicalWriter.externalLocals.single().otid)
        assertEquals("otid", canonicalWriter.sentLocals.single().otid)
        assertEquals("otid", canonicalWriter.reconciledSends.single().otid)
        assertEquals("normalized", canonicalWriter.recentReconciles.single().conversationId)
        captured.retire()
        try {
            captured.writer.markExternalTransportLocalSent("normalized", "stale")
            fail("Retired captured runtime accepted a send")
        } catch (failure: IllegalStateException) {
            assertTrue(failure.message.orEmpty().contains("retired"))
        }
    }
}
