package com.letta.mobile.data.home

import com.letta.mobile.data.home.SessionHomePageSourceTest.FakeRuns
import com.letta.mobile.data.home.SessionHomePageSourceTest.FakeTools
import com.letta.mobile.data.home.SessionHomePageSourceTest.FixedGraphProvider
import com.letta.mobile.data.home.SessionHomePageSourceTest.HomeGraph
import com.letta.mobile.data.model.Run
import com.letta.mobile.data.model.Tool
import com.letta.mobile.data.model.ToolId
import com.letta.mobile.data.transport.ChannelTransportState
import com.letta.mobile.data.transport.api.NoOpChannelTransport
import com.letta.mobile.data.transport.api.FrameCollectorOverflowAwareChannelTransport
import com.letta.mobile.data.transport.api.FrameCollectorOverflowIncident
import kotlinx.coroutines.flow.MutableSharedFlow
import com.letta.mobile.data.transport.appserver.AppServerInboundFrame
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/** Completed-result bounds and connection-scoped absence, with distinct fixture wire counters. */
@OptIn(ExperimentalCoroutinesApi::class)
class HomeAggregateCachingTest {
    @Test
    fun hiddenStartKeepsCatalogLiveWithoutAggregateAttempts() = runTest {
        val tools = FakeTools()
        val source = SessionHomePageSource(FixedGraphProvider(HomeGraph(tools = tools)), backgroundScope)
        source.start()
        runCurrent()
        tools.catalog.value = listOf(Tool(id = ToolId("tool-one"), name = "Example"))
        runCurrent()
        assertEquals("Example", source.catalog.value.tools.single().name)
        assertEquals(0, tools.countCalls, "hidden Home keeps catalogs but defers expensive aggregates")
        source.loadIfStale()
        runCurrent()
        assertEquals(1, tools.countCalls)
        source.close()
    }

    @Test
    fun repeatedGraphEmissionsAndPageEntriesReuseCompletedAggregatesInsideTtl() = runTest {
        val clock = TestClock()
        val tools = FakeTools(Result.success(4))
        val transport = CountingTransport()
        val provider = FixedGraphProvider(HomeGraph(tools = tools, channelTransport = transport))
        val source = SessionHomePageSource(provider, backgroundScope, clock = clock)
        source.start()
        source.loadIfStale()
        runCurrent()
        repeat(20) {
            provider.currentGraph.value = HomeGraph(tools = tools, channelTransport = transport)
            runCurrent()
            source.loadIfStale()
            runCurrent()
        }
        assertEquals(1, tools.countCalls, "20 graph emissions and entries must cause zero reloads inside TTL")
        assertEquals(4, source.stats.value.toolCount)
        clock.instant += 60.seconds
        source.loadIfStale()
        runCurrent()
        assertEquals(2, tools.countCalls, "TTL boundary reloads on demand")
        source.close()
    }

    @Test
    fun replacingAnInFlightGraphUsesTheNewRepositories() = runTest {
        val pending = CompletableDeferred<Unit>()
        val oldTools = FakeTools(Result.success(4), beforeCount = { pending.await() })
        val newTools = FakeTools(Result.success(9))
        val transport = CountingTransport()
        val provider = FixedGraphProvider(HomeGraph(tools = oldTools, channelTransport = transport))
        val source = SessionHomePageSource(provider, backgroundScope)
        source.start()
        source.loadIfStale()
        runCurrent()
        provider.currentGraph.value = HomeGraph(tools = newTools, channelTransport = transport)
        runCurrent()
        assertEquals(1, oldTools.countCalls)
        assertEquals(1, newTools.countCalls, "an unfinished result cannot keep using a replaced graph's repositories")
        assertEquals(9, source.stats.value.toolCount)
        pending.complete(Unit)
        runCurrent()
        assertEquals(9, source.stats.value.toolCount)
        source.close()
    }

    @Test
    fun ttlStartsWhenTheAggregateCompletes() = runTest {
        val clock = TestClock()
        val pending = CompletableDeferred<Unit>()
        val tools = FakeTools(beforeCount = { pending.await() })
        val source = SessionHomePageSource(FixedGraphProvider(HomeGraph(tools = tools)), backgroundScope, clock = clock)
        source.start()
        source.loadIfStale()
        runCurrent()
        clock.instant += 120.seconds
        pending.complete(Unit)
        runCurrent()
        source.loadIfStale()
        runCurrent()
        assertEquals(1, tools.countCalls, "a slow load receives a full TTL after completion")
        source.close()
    }

    @Test
    fun explicitRefreshBypassesTtlAndCoalescesOverlaps() = runTest {
        var pending: CompletableDeferred<Unit>? = null
        val tools = FakeTools(Result.success(4), beforeCount = { pending?.await() })
        val source = SessionHomePageSource(FixedGraphProvider(HomeGraph(tools = tools)), backgroundScope)
        source.start()
        source.loadIfStale()
        runCurrent()
        pending = CompletableDeferred()
        source.refresh()
        runCurrent()
        repeat(10) { source.refresh() }
        runCurrent()
        assertEquals(2, tools.countCalls, "explicit refresh starts exactly one reload even inside TTL")
        pending.complete(Unit)
        runCurrent()
        source.close()
    }

    @Test
    fun unsupportedLocalGuardCostsOneAttemptAndNoWireTrafficPerGeneration() = runTest {
        verifyUnsupportedCounts(rejectBeforeSend = true, expectedWireCount = 0)
    }

    @Test
    fun unsupportedReplyCostsOneAttemptSendReplyAndBackendDispatchPerGeneration() = runTest {
        verifyUnsupportedCounts(rejectBeforeSend = false, expectedWireCount = 1)
    }

    @Test
    fun unknownAdminMethodReplyIsAlsoRemembered() = runTest {
        verifyUnsupportedCounts(rejectBeforeSend = false, expectedWireCount = 1, replyError = "Unknown method: stat.count")
    }

    private fun TestScope.verifyUnsupportedCounts(
        rejectBeforeSend: Boolean,
        expectedWireCount: Int,
        replyError: String = "unsupported admin_rpc method",
    ) {
        val clock = TestClock()
        val transport = CountingTransport(rejectBeforeSend, replyError)
        val tools = FakeTools(beforeCount = {
            val response = transport.adminRpc("stat.count", "/statistics", null)
            if (!response.success) error(response.error.orEmpty())
        })
        val provider = FixedGraphProvider(HomeGraph(tools = tools, channelTransport = transport))
        val source = SessionHomePageSource(provider, backgroundScope, clock = clock)
        source.start()
        source.loadIfStale()
        runCurrent()
        repeat(10) {
            clock.instant += 61.seconds
            source.refresh()
            runCurrent()
            provider.currentGraph.value = HomeGraph(tools = tools, channelTransport = transport)
            runCurrent()
        }
        transport.connection.value = ChannelTransportState.Connected("server", "first", "device", a2uiEnabled = true)
        runCurrent()
        assertEquals(1, tools.countCalls, "unsupported statistic is attempted once, including forced refreshes")
        assertEquals(expectedWireCount, transport.sends, "send count is separate from source attempts")
        assertEquals(expectedWireCount, transport.replies, "reply count is observed separately")
        assertEquals(expectedWireCount, transport.backendDispatches, "fake backend dispatch count is separate")

        transport.connection.value = ChannelTransportState.Connected("server", "second", "device")
        runCurrent()
        assertEquals(2, tools.countCalls, "a new connection session retries unsupported statistics")
        assertEquals(expectedWireCount * 2, transport.sends)
        assertEquals(expectedWireCount * 2, transport.replies)
        assertEquals(expectedWireCount * 2, transport.backendDispatches)

        transport.features = setOf("statistics-v2")
        provider.currentGraph.value = HomeGraph(tools = tools, channelTransport = transport)
        runCurrent()
        assertEquals(3, tools.countCalls, "changed host capabilities retry in the same connection")
        source.refresh()
        runCurrent()
        assertEquals(3, tools.countCalls, "that capability epoch also remembers unsupported")
        source.close()
    }

    @Test
    fun disconnectedHandshakeResetDoesNotRetryUnsupportedInTheOldGeneration() = runTest {
        val transport = CountingTransport(rejectBeforeSend = true)
        transport.features = setOf("statistics-v2")
        val tools = FakeTools(beforeCount = { transport.adminRpc("stat.count", "/statistics", null) })
        val source = SessionHomePageSource(
            FixedGraphProvider(HomeGraph(tools = tools, channelTransport = transport)), backgroundScope,
        )
        source.start()
        source.loadIfStale()
        runCurrent()
        transport.features = emptySet()
        transport.connection.value = ChannelTransportState.Connecting(reconnecting = true)
        runCurrent()
        assertEquals(1, tools.countCalls, "a cleared handshake must not make a disconnected aggregate attempt")
        transport.connection.value = ChannelTransportState.Connected("server", "second", "device")
        runCurrent()
        assertEquals(2, tools.countCalls, "the new connection retries")
        source.close()
    }

    @Test
    fun numericConnectionGenerationRetriesEvenWithTheSameSessionMetadata() = runTest {
        val transport = CountingTransport(rejectBeforeSend = true)
        val tools = FakeTools(beforeCount = { transport.adminRpc("stat.count", "/statistics", null) })
        val source = SessionHomePageSource(
            FixedGraphProvider(HomeGraph(tools = tools, channelTransport = transport)), backgroundScope,
        )
        source.start()
        source.loadIfStale()
        runCurrent()
        transport.frameCollectorConnectionGeneration += 1
        transport.connection.value = ChannelTransportState.Connected("server", "first", "device", a2uiEnabled = true)
        runCurrent()
        assertEquals(2, tools.countCalls, "the transport epoch supersedes identical server/session metadata")
        source.close()
    }

    @Test
    fun lateFailureFromAnOldConnectionCannotPoisonTheNewGeneration() = runTest {
        val pending = CompletableDeferred<Unit>()
        var attempts = 0
        val tools = FakeTools(Result.success(9), beforeCount = {
            attempts += 1
            if (attempts == 1) {
                withContext(NonCancellable) { pending.await() }
                throw UnsupportedOperationException("unsupported admin_rpc method")
            }
        })
        val transport = CountingTransport()
        val source = SessionHomePageSource(
            FixedGraphProvider(HomeGraph(tools = tools, channelTransport = transport)), backgroundScope,
        )
        source.start()
        source.loadIfStale()
        runCurrent()
        transport.connection.value = ChannelTransportState.Connected("server", "second", "device")
        runCurrent()
        assertEquals(9, source.stats.value.toolCount)
        pending.complete(Unit)
        runCurrent()
        source.refresh()
        runCurrent()
        assertEquals(3, tools.countCalls, "late unsupported failure must not suppress the new generation")
        assertEquals(9, source.stats.value.toolCount)
        source.close()
    }

    @Test
    fun transientFailureCanBeRetriedInTheSameGeneration() = runTest {
        val tools = FakeTools(Result.failure(IllegalStateException("timed out")))
        val source = SessionHomePageSource(FixedGraphProvider(HomeGraph(tools = tools)), backgroundScope)
        source.start()
        source.loadIfStale()
        runCurrent()
        tools.count = Result.success(9)
        source.refresh()
        runCurrent()
        assertEquals(2, tools.countCalls)
        assertEquals(9, source.stats.value.toolCount)
        source.close()
    }

    @Test
    fun completedUsageAvoidsAnotherHundredStepReadsUntilExplicitRefresh() = runTest {
        val clock = TestClock()
        val runs = FakeRuns()
        runs.recent = List(100) { Run(id = "run-$it", agentId = "agent", createdAt = clock.now().toString()) }
        val transport = CountingTransport()
        val provider = FixedGraphProvider(HomeGraph(runs = runs, channelTransport = transport))
        val source = SessionHomePageSource(provider, backgroundScope, clock = clock)
        source.start()
        source.loadIfStale()
        runCurrent()
        repeat(20) {
            provider.currentGraph.value = HomeGraph(runs = runs, channelTransport = transport)
            runCurrent()
        }
        assertEquals(1, runs.recentCalls)
        assertEquals(100, runs.stepCalls, "20 graph emissions add zero run or step reads inside TTL")
        source.refresh()
        runCurrent()
        assertEquals(2, runs.recentCalls)
        assertEquals(200, runs.stepCalls, "explicit refresh performs exactly one complete usage reload")
        source.close()
    }

    @Test
    fun unsupportedStepsAreProbedOnceBeforeTheHundredRunFanout() = runTest {
        val clock = TestClock()
        val runs = FakeRuns(stepFailure = UnsupportedOperationException("unsupported admin_rpc step.list"))
        runs.recent = List(100) { Run(id = "run-$it", agentId = "agent", createdAt = clock.now().toString()) }
        val source = SessionHomePageSource(FixedGraphProvider(HomeGraph(runs = runs)), backgroundScope, clock = clock)
        source.start()
        source.loadIfStale()
        runCurrent()
        repeat(5) {
            source.refresh()
            runCurrent()
        }
        assertEquals(1, runs.recentCalls)
        assertEquals(1, runs.stepCalls, "probe unsupported steps once, before launching the other 99 reads")
        assertNull(source.stats.value.usage, "unsupported steps must not report fake zero token usage")
        source.close()
    }

    private class TestClock : Clock {
        var instant = Instant.parse("2026-10-28T12:00:00Z")
        override fun now(): Instant = instant
    }

    /** In-process transport/backend fixture: no network or live host is contacted. */
    private class CountingTransport(
        private val rejectBeforeSend: Boolean = false,
        private val replyError: String = "unsupported admin_rpc method",
    ) : NoOpChannelTransport(), FrameCollectorOverflowAwareChannelTransport {
        override val collectorOverflows = MutableSharedFlow<FrameCollectorOverflowIncident>()
        override var frameCollectorConnectionGeneration = 1L
        override fun isFrameCollectorOverflowCancellation(
            subscriptionIdentity: String,
            cancellation: CancellationException,
        ): Boolean = false
        val connection = MutableStateFlow<ChannelTransportState>(ChannelTransportState.Connected("server", "first", "device"))
        override val state: StateFlow<ChannelTransportState> = connection
        var features: Set<String> = emptySet()
        override val hostCapabilities: Set<String> get() = features
        var sends = 0
        var replies = 0
        var backendDispatches = 0

        override suspend fun adminRpc(method: String, path: String, body: String?): AppServerInboundFrame.AdminRpcResponse {
            if (rejectBeforeSend) throw UnsupportedOperationException("admin_rpc is not supported by this transport")
            sends += 1
            backendDispatches += 1
            val reply = AppServerInboundFrame.AdminRpcResponse(
                requestId = "test-request", success = false, error = replyError,
            )
            replies += 1
            return reply
        }
    }

}
