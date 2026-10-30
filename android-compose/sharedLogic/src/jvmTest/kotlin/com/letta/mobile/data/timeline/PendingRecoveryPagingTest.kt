package com.letta.mobile.data.timeline

import com.letta.mobile.data.model.AssistantMessage
import com.letta.mobile.data.model.UserMessage
import com.letta.mobile.data.timeline.snapshot.TimelineScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.launch
import kotlinx.coroutines.CompletableDeferred
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.MessageId
import java.time.Instant
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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

    @Test fun aYoungSendOfThisProcessSurvivesCoveredHistory() = runTest {
        val sentAt = Instant.now().minusSeconds(120)
        val f = Fixture(sentAt = sentAt, firstPageAt = sentAt, fromThisProcess = true)
        val selection = f.open()
        assertEquals(TimelineEnginePageOutcome.Applied, f.session.loadOlder(selection))
        assertEquals(CanonicalPendingLocalStore.Delivery.Sending, f.session.pending.value.single().delivery)
    }

    @Test fun aSendOfThisProcessPastTheHorizonFailsWhenNothingStreams() = runTest {
        val sentAt = Instant.now().minusSeconds(600)
        val f = Fixture(sentAt = sentAt, firstPageAt = sentAt, fromThisProcess = true)
        val selection = f.open()
        assertEquals(TimelineEnginePageOutcome.Applied, f.session.loadOlder(selection))
        assertEquals(CanonicalPendingLocalStore.Delivery.Failed, f.session.pending.value.single().delivery)
    }

    @Test fun aSendOfThisProcessPastTheHorizonSurvivesWhileItsTurnStreams() = runTest {
        val sentAt = Instant.now().minusSeconds(600)
        val f = Fixture(sentAt = sentAt, firstPageAt = sentAt, fromThisProcess = true)
        val selection = f.open()
        val fence = f.session.beginLive(selection)
        val frame = AssistantMessage(id = "streamed", contentRaw = JsonPrimitive("still going"), runId = "long-run")
        assertTrue(f.session.ingest(fence, TimelineStreamFrame.Message(frame)))
        assertEquals(TimelineEnginePageOutcome.Applied, f.session.loadOlder(selection))
        assertEquals(CanonicalPendingLocalStore.Delivery.Sending, f.session.pending.value.single().delivery)
        // Once the turn completes nothing streams, and the coverage already loaded decides it.
        assertTrue(f.session.ingest(fence, TimelineStreamFrame.Done))
        assertEquals(CanonicalPendingLocalStore.Delivery.Failed, f.session.pending.value.single().delivery)
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
    // softs (Q2): a prompt that reached the host before contact was lost is not posted twice.
    @Test fun retryConfirmsAnEchoAlreadyInHistoryInsteadOfResending() = runTest {
        val scope = TimelineScope("retry-probe", "conversation", "agent")
        val store = InMemoryTimelineStore()
        val record = CanonicalPendingLocalStore.Record("received", "prompt", emptyList(), "2026-01-01T00:00:00Z",
            CanonicalPendingLocalStore.Delivery.Failed)
        CanonicalPendingLocalStore(store).save(scope, record)
        val echo = UserMessage("server-echo", contentRaw = JsonPrimitive("prompt"), otid = record.otid, date = record.sentAt)
        val transport = object : TimelineTransport by unexpectedTimelineTransport() {
            override suspend fun listConversationMessagePage(request: TimelineRemotePageRequest, progress: TimelinePageProgress?) =
                TimelineRemotePageAdapter.fromMessages(request, listOf(echo), hasMore = false)
        }
        val coordinator = CanonicalTimelineCoordinator(store, transport)
        val owner = coordinator.acquire(scope)
        var resent = false
        coordinator.bindPendingRetry(AgentId("agent")) { _, _ -> resent = true }

        assertTrue(coordinator.retryPending(owner, MessageId(record.otid)))

        assertFalse(resent, "a prompt whose echo is already in history must not be sent again")
        assertTrue(owner.session.pending.value.isEmpty())
        assertTrue(CanonicalPendingLocalStore(store).load(scope).isEmpty())
    }

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
        // The retry is a new attempt: its echo horizon starts now, not at the prompt's first send.
        assertEquals(CanonicalPendingLocalStore.Delivery.Sending, owner.session.pending.value.single().delivery)
        assertEquals(1L, owner.session.pending.value.single().attempt)
    }

    private class Fixture(
        private val echo: Boolean = true,
        private val sentAt: Instant = Instant.now().minusSeconds(120),
        private val firstPageAt: Instant = sentAt.plusSeconds(60),
        private val delivery: CanonicalPendingLocalStore.Delivery = CanonicalPendingLocalStore.Delivery.Sending,
        // Recovery candidates are, by default, sends of a process that has since died.
        private val fromThisProcess: Boolean = false,
    ) {
        private val scope = TimelineScope("paging-recovery", "conversation", "agent")
        private val store = InMemoryTimelineStore()
        var offline = false
        private val transport = object : TimelineTransport by unexpectedTimelineTransport() {
            override suspend fun listConversationMessagePage(
                request: TimelineRemotePageRequest, progress: TimelinePageProgress?,
            ): TimelineRemotePageResult {
                check(!offline) { "offline" }
                val first = request.continuation == TimelineContinuation.Initial
                val message = if (first) user("newer", "another-otid", firstPageAt.toString()) else
                    user("older", if (echo) "pending-otid" else "other-otid", sentAt.toString())
                return TimelineRemotePageAdapter.fromMessages(request, listOf(message), hasMore = first)
            }
        }
        val session = CanonicalTimelineSession(store, transport, scope, enabled = true)

        suspend fun open(): TimelineEngineSelection {
            CanonicalPendingLocalStore(store).save(scope, CanonicalPendingLocalStore.Record(
                "pending-otid", "prompt", emptyList(), sentAt.toString(), delivery,
            ))
            if (!fromThisProcess) store.fromAnotherProcess(scope)
            return assertIs<TimelineEngineOpen.Opened>(session.open()).selection
        }

        private fun user(id: String, otid: String, date: String) =
            UserMessage(id, contentRaw = JsonPrimitive("prompt"), otid = otid, date = date)
    }
}
