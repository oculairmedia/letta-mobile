package com.letta.mobile.data.timeline

import com.letta.mobile.data.model.UserMessage
import com.letta.mobile.data.timeline.snapshot.TimelineScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.launch
import kotlinx.coroutines.CompletableDeferred
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.MessageId
import com.letta.mobile.data.timeline.api.PendingDispatchOwners
import com.letta.mobile.data.timeline.api.PendingDispatchTimelineStore
import java.time.Instant
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class PendingRecoveryPagingTest {
    @Test fun coveredRecentSendingAndSentCandidatesFailBeforeHistoryIsExhausted() = runTest {
        val sentAt = Instant.now().minusSeconds(120)
        for (delivery in listOf(CanonicalPendingLocalStore.Delivery.Sending, CanonicalPendingLocalStore.Delivery.Sent)) {
            // Include equality at the oldest row, and a send strictly inside the loaded window.
            val f = Fixture(sentAt = sentAt, firstPageAt = sentAt.minusSeconds(delivery.ordinal.toLong()), delivery = delivery)
            val selection = f.open()
            assertEquals(delivery, f.session.pending.value.single().delivery)
            assertEquals(TimelineEnginePageOutcome.Applied, f.session.loadOlder(selection))
            assertEquals(CanonicalPendingLocalStore.Delivery.Failed, f.session.pending.value.single().delivery)
            assertTrue(f.session.pending.value.single().toRenderItem().message.isSendFailed)
        }
    }

    @Test fun recentReconcileAlsoDecidesCoveredRecoveryCandidates() = runTest {
        val sentAt = Instant.now().minusSeconds(120)
        val f = Fixture(sentAt = sentAt, firstPageAt = sentAt)
        val selection = f.open()
        assertEquals(TimelineEnginePageOutcome.Applied, f.session.reconcileRecent(selection))
        assertEquals(CanonicalPendingLocalStore.Delivery.Failed, f.session.pending.value.single().delivery)
    }

    @Test fun uncoveredRecoveryCandidateIsRetiredAfterHorizonWithoutLoadingItsPage() = runTest {
        val sentAt = Instant.now().minusMillis(CanonicalPendingLocalStore.LOST_ECHO_HORIZON_MILLIS + 60_000)
        val f = Fixture(sentAt = sentAt)
        val selection = f.open()
        assertEquals(CanonicalPendingLocalStore.Delivery.Sending, f.session.pending.value.single().delivery)
        assertEquals(TimelineEnginePageOutcome.Applied, f.session.loadOlder(selection))
        assertEquals(CanonicalPendingLocalStore.Delivery.Failed, f.session.pending.value.single().delivery)
        assertTrue(f.session.pending.value.single().toRenderItem().message.isSendFailed)
    }

    @Test fun activeRecoveryDispatchSurvivesCoveredHistoryAndHorizon() = runTest {
        for (ageSeconds in listOf(120L, 600L)) {
            val sentAt = Instant.now().minusSeconds(ageSeconds)
            val f = Fixture(sentAt = sentAt, firstPageAt = sentAt)
            f.active = true
            val selection = f.open()
            assertEquals(TimelineEnginePageOutcome.Applied, f.session.loadOlder(selection))
            assertEquals(CanonicalPendingLocalStore.Delivery.Sending, f.session.pending.value.single().delivery)
            f.active = false
            // A live completion reuses the coverage already established by the loaded page.
            val fence = f.session.beginLive(selection)
            assertTrue(f.session.ingest(fence, TimelineStreamFrame.Done))
            assertEquals(CanonicalPendingLocalStore.Delivery.Failed, f.session.pending.value.single().delivery)
        }
    }

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

    private class Fixture(
        private val echo: Boolean = true,
        private val sentAt: Instant = Instant.now().minusSeconds(120),
        private val firstPageAt: Instant = sentAt.plusSeconds(60),
        private val delivery: CanonicalPendingLocalStore.Delivery = CanonicalPendingLocalStore.Delivery.Sending,
    ) {
        private val scope = TimelineScope("paging-recovery", "conversation", "agent")
        private val store = InMemoryTimelineStore()
        private val dispatchOwners = PendingDispatchOwners()
        var active = false
        var offline = false
        private val transport = object : TimelineTransport by unexpectedTimelineTransport() {
            override suspend fun listConversationMessagePage(request: TimelineRemotePageRequest, progress: TimelinePageProgress?): TimelineRemotePageResult {
                check(!offline) { "offline" }
                val first = request.continuation == TimelineContinuation.Initial
                val message = if (first) user("newer", "another-otid", firstPageAt.toString()) else
                    user("older", if (echo) "pending-otid" else "other-otid", sentAt.toString())
                return TimelineRemotePageAdapter.fromMessages(request, listOf(message), hasMore = first)
            }
        }
        val session = CanonicalTimelineSession(PendingDispatchTimelineStore(store, dispatchOwners), transport, scope, enabled = true)

        suspend fun open(): TimelineEngineSelection {
            CanonicalPendingLocalStore(store).save(scope, CanonicalPendingLocalStore.Record(
                "pending-otid", "prompt", emptyList(), sentAt.toString(), delivery,
            ))
            dispatchOwners.retain(scope.backendId, scope.conversationId, "pending-otid") { active }
            return assertIs<TimelineEngineOpen.Opened>(session.open()).selection
        }

        private fun user(id: String, otid: String, date: String) =
            UserMessage(id, contentRaw = JsonPrimitive("prompt"), otid = otid, date = date)
    }
}
