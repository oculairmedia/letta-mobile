package com.letta.mobile.data.timeline

import androidx.paging.PagingSource
import com.letta.mobile.data.model.AssistantMessage
import com.letta.mobile.data.model.ConversationId
import com.letta.mobile.data.timeline.snapshot.TimelineScope
import com.letta.mobile.util.Telemetry
import com.letta.mobile.util.TelemetryRecordingTest
import com.letta.mobile.util.TimelineOpenTrace
import com.letta.mobile.util.TimelineOpenTrace.Stage
import com.letta.mobile.util.numberOrNull
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The real session, engine, paging source and store, with only the host transport faked: the stage
 * events must come out of the production path, once each, in open order.
 */
class TimelineOpenTraceStagesTest : TelemetryRecordingTest() {
    private val scope = TimelineScope("backend", "conv-stages", "agent")
    private val conversation = ConversationId(scope.conversationId)

    @BeforeTest
    fun enableTrace() {
        TimelineOpenTrace.clearForTest()
        Telemetry.timelineOpenTraceEnabled.set(true)
    }

    @AfterTest
    fun disableTrace() {
        Telemetry.timelineOpenTraceEnabled.set(false)
        TimelineOpenTrace.clearForTest()
    }

    private class HostTransport(private val records: Int) : TimelineTransport by unexpectedTimelineTransport() {
        var calls = 0

        override suspend fun listConversationMessagePage(
            request: TimelineRemotePageRequest,
            progress: TimelinePageProgress?,
        ): TimelineRemotePageResult {
            calls++
            val page = (records - 1 downTo 0).map { index ->
                TimelineRemoteRecord(
                    TimelineMessageId("m-$index"),
                    AssistantMessage(
                        id = "m-$index", contentRaw = JsonPrimitive("reply $index"),
                        date = "2026-01-01T00:00:0${index}Z", logicalMessageId = "otid-m-$index",
                    ),
                    encodedBodyBytes = 100L,
                )
            }
            return TimelineRemotePageResult.Page(request.requestId, request.selectionGeneration, page, null, false, 100L * records)
        }
    }

    private fun stages(): List<Telemetry.Event> =
        Telemetry.snapshot().asReversed().filter { it.tag == TimelineOpenTrace.TAG }

    @Test
    fun cacheReadNetworkPageAndReconcileFireOnceInOrderFromTheRealPath() = runTest {
        val transport = HostTransport(records = 3)
        val session = CanonicalTimelineSession(InMemoryTimelineStore(), transport, scope, enabled = true)
        val selection = assertIs<TimelineEngineOpen.Opened>(session.open()).selection
        TimelineOpenTrace.requested(conversation)

        // 1. The list's first read hits an empty ledger.
        val source = TimelineLedgerPagingSource(session.engine, selection)
        val cold = assertIs<PagingSource.LoadResult.Page<TimelinePageKey, TimelineSettledRecord>>(
            source.load(PagingSource.LoadParams.Refresh(null, 64, false)),
        )
        assertEquals(0, cold.data.size)
        // 2. The host page is fetched and merged.
        assertEquals(TimelineEnginePageOutcome.Applied, session.reconcileRecent(selection))
        // 3. A later read, and a second reconcile, add nothing: every stage already fired.
        val warm = TimelineLedgerPagingSource(session.engine, selection)
        assertIs<PagingSource.LoadResult.Page<TimelinePageKey, TimelineSettledRecord>>(
            warm.load(PagingSource.LoadParams.Refresh(null, 64, false)),
        )
        session.reconcileRecent(selection)

        assertEquals(2, transport.calls)
        val trace = stages()
        assertEquals(
            listOf(
                Stage.OpenRequested, Stage.CacheReadStart, Stage.CacheReadEnd,
                Stage.NetworkPageStart, Stage.NetworkPageEnd, Stage.ReconcileEnd,
            ).map { it.eventName },
            trace.map { it.name },
        )
        assertEquals(1, trace.map { it.attrs["openId"]?.numberOrNull?.toLong() }.toSet().size)
        assertEquals(0, trace.single { it.name == Stage.CacheReadEnd.eventName }.attrs["count"]?.numberOrNull)
        val network = trace.single { it.name == Stage.NetworkPageEnd.eventName }
        assertEquals(3, network.attrs["count"]?.numberOrNull)
        assertEquals(300L, network.attrs["bytes"]?.numberOrNull?.toLong())
        assertEquals(3, trace.single { it.name == Stage.ReconcileEnd.eventName }.attrs["count"]?.numberOrNull)
        assertTrue(trace.map { it.timestampMs }.zipWithNext().all { (a, b) -> a <= b })
    }

    @Test
    fun theSamePathEmitsNothingWhileTheTraceIsOff() = runTest {
        Telemetry.timelineOpenTraceEnabled.set(false)
        val transport = HostTransport(records = 2)
        val session = CanonicalTimelineSession(InMemoryTimelineStore(), transport, scope, enabled = true)
        val selection = assertIs<TimelineEngineOpen.Opened>(session.open()).selection
        TimelineOpenTrace.requested(conversation)

        assertIs<PagingSource.LoadResult.Page<TimelinePageKey, TimelineSettledRecord>>(
            TimelineLedgerPagingSource(session.engine, selection).load(PagingSource.LoadParams.Refresh(null, 64, false)),
        )
        assertEquals(TimelineEnginePageOutcome.Applied, session.reconcileRecent(selection))

        assertEquals(1, transport.calls)
        assertTrue(stages().isEmpty())
    }
}
