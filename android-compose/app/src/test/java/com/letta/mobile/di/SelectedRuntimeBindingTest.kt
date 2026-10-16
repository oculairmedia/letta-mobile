package com.letta.mobile.di

import com.letta.mobile.feature.chat.coordination.SelectedChatRuntime
import com.letta.mobile.feature.chat.screen.ChatPagingHost
import com.letta.mobile.feature.chat.screen.ChatPagingPresentation
import com.letta.mobile.testutil.FakeTimelineExternalTransportWriter
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
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

    @Test fun expiryDelegatesThroughResolvedWriter() = runTest {
        val delegate = mockk<com.letta.mobile.data.timeline.api.TimelineExternalTransportWriter>(relaxed = true)
        var resolutions = 0
        val writer = SelectedRuntimeWriter("agent") { resolutions++; delegate }
        writer.repairExpiredConversationCursorScoped("agent", "conversation", fallbackSeq = 12L, expectedWatermark = 4L)
        // The name's claim is that the call goes through a freshly resolved writer, not a captured one.
        assertEquals(1, resolutions)
        coVerify(exactly = 1) { delegate.repairExpiredConversationCursorScoped("agent", "conversation", 12L) }
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
