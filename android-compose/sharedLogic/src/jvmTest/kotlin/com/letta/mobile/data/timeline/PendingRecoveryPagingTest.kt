package com.letta.mobile.data.timeline

import com.letta.mobile.data.model.UserMessage
import com.letta.mobile.data.timeline.snapshot.TimelineScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.launch
import kotlinx.coroutines.CompletableDeferred
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.MessageId
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class PendingRecoveryPagingTest {
    @Test fun laterPageEchoConfirmsWithoutPrematureFailure() = runTest {
        val f = Fixture()
        val selection = f.open()
        assertEquals(TimelineEnginePageOutcome.Applied, f.session.loadOlder(selection))
        assertEquals(CanonicalPendingLocalStore.Delivery.Sending, f.session.pending.value.single().delivery)
        assertEquals(TimelineEnginePageOutcome.Applied, f.session.loadOlder(selection))
        assertEquals(emptyList(), f.session.pending.value)
    }

    @Test fun missingEchoFailsOnlyAfterLastPageAndSurvivesHistoryFailure() = runTest {
        val f = Fixture(echo = false)
        val selection = f.open()
        f.offline = true
        assertFailsWith<IllegalStateException> { f.session.loadOlder(selection) }
        assertEquals(CanonicalPendingLocalStore.Delivery.Sending, f.session.pending.value.single().delivery)
        f.offline = false
        assertEquals(TimelineEnginePageOutcome.Applied, f.session.loadOlder(selection))
        assertEquals(CanonicalPendingLocalStore.Delivery.Sending, f.session.pending.value.single().delivery)
        assertEquals(TimelineEnginePageOutcome.Applied, f.session.loadOlder(selection))
        assertEquals(CanonicalPendingLocalStore.Delivery.Failed, f.session.pending.value.single().delivery)
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test fun suspendedRetryAdmissionCannotBeRetiredAsAnOldLostEcho() = runTest {
        val scope = TimelineScope("admission-recovery", "conversation", "agent")
        val store = InMemoryTimelineStore()
        val transport = object : TimelineTransport by unexpectedTimelineTransport() {
            override suspend fun listConversationMessagePage(request: TimelineRemotePageRequest, progress: TimelinePageProgress?) =
                TimelineRemotePageAdapter.fromMessages(request, emptyList(), hasMore = false)
        }
        val record = CanonicalPendingLocalStore.Record("retry", "prompt", emptyList(), "2026-01-01T00:00:00Z",
            CanonicalPendingLocalStore.Delivery.Failed)
        CanonicalPendingLocalStore(store).save(scope, record)
        val coordinator = CanonicalTimelineCoordinator(store, transport)
        val owner = coordinator.acquire(scope)
        val entered = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        coordinator.bindPendingRetry(AgentId("agent")) { _, _ -> entered.complete(Unit); finish.await() }
        val retry = backgroundScope.launch { assertTrue(coordinator.retryPending(owner, MessageId(record.otid))) }
        runCurrent()
        assertTrue(entered.isCompleted)
        assertEquals(TimelineEnginePageOutcome.Applied, coordinator.reconcileRecent(owner))
        assertEquals(CanonicalPendingLocalStore.Delivery.Sending, owner.session.pending.value.single().delivery)
        finish.complete(Unit)
        retry.join()
        assertEquals(TimelineEnginePageOutcome.Applied, coordinator.reconcileRecent(owner))
        assertEquals(CanonicalPendingLocalStore.Delivery.Failed, owner.session.pending.value.single().delivery)
    }

    private class Fixture(private val echo: Boolean = true) {
        private val scope = TimelineScope("paging-recovery", "conversation", "agent")
        private val store = InMemoryTimelineStore()
        var offline = false
        private val transport = object : TimelineTransport by unexpectedTimelineTransport() {
            override suspend fun listConversationMessagePage(request: TimelineRemotePageRequest, progress: TimelinePageProgress?): TimelineRemotePageResult {
                check(!offline) { "offline" }
                val first = request.continuation == TimelineContinuation.Initial
                val message = if (first) user("newer", "another-otid", "2026-01-01T00:01:00Z") else
                    user("older", if (echo) "pending-otid" else "other-otid", "2026-01-01T00:00:00Z")
                return TimelineRemotePageAdapter.fromMessages(request, listOf(message), hasMore = first)
            }
        }
        val session = CanonicalTimelineSession(store, transport, scope, enabled = true)

        suspend fun open(): TimelineEngineSelection {
            CanonicalPendingLocalStore(store).save(scope, CanonicalPendingLocalStore.Record(
                "pending-otid", "prompt", emptyList(), "2026-01-01T00:00:00Z",
            ))
            return assertIs<TimelineEngineOpen.Opened>(session.open()).selection
        }

        private fun user(id: String, otid: String, date: String) =
            UserMessage(id, contentRaw = JsonPrimitive("prompt"), otid = otid, date = date)
    }
}
