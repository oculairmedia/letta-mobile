package com.letta.mobile.data.timeline

import com.letta.mobile.data.model.AssistantMessage
import com.letta.mobile.data.timeline.snapshot.TimelineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.advanceUntilIdle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class TimelineExactCanonicalWriterTest {
    @Test fun assistantAliasRequiresSharedSegmentProvenanceNotTextOrRun() = runTest {
        val store = Store()
        val writer = TimelineExactCanonicalWriter(scope, 100_000)
        val live = kotlin.test.assertNotNull(message("same response").toTimelineEvent(0.0)).copy(
            serverId = "cm-stream-provider-assistant-0-segment-a", otid = "provider-assistant-0-segment-a",
        )
        store.transaction(scope) { writer.mergeEvent(this, live) }
        // A history producer preserving the exact segment OTID reconciles without text heuristics.
        store.transaction(scope) { writer.mergeEvent(this, live.copy(serverId = "ui-msg-a")) }
        assertEquals(1, store.rows.size)
        // Same content and run with independent provenance must remain a separate response.
        store.transaction(scope) { writer.mergeEvent(this, live.copy(serverId = "ui-msg-b", otid = "ui-msg-b")) }
        assertEquals(2, store.rows.size)
    }

    @Test fun capturedTransportScopeRejectsRetiredBackend() = runTest {
        var current = "first"
        val resolver = CanonicalTransportScopeResolver(current) { it == current }
        assertEquals(TimelineScope("first", "conversation", "agent"), resolver.resolve("agent", "conversation"))
        current = "second"
        kotlin.test.assertFailsWith<IllegalStateException> { resolver.resolve("agent", "conversation") }
        val replacement = CanonicalTransportScopeResolver(current) { it == current }
        assertEquals(TimelineScope("second", "conversation", "agent"), replacement.resolve("agent", "conversation"))
    }

    @Test fun liveReductionNamesSyncSettlementWithoutWritingAndRejectsStaleFence() = runTest {
        val store = Store()
        val engine = CanonicalTimelineEngine(store, TimelineExactCanonicalWriter(scope, 100_000), enabled = true)
        val selection = assertIs<TimelineEngineOpen.Opened>(engine.open(scope)).selection
        val stale = engine.beginLive(selection)
        val fence = engine.beginLive(selection)
        val reply = message("hello")
        assertFalse(engine.ingest(stale, TimelineStreamFrame.Message(message("old"))))
        assertTrue(engine.ingest(fence, TimelineStreamFrame.Message(reply)))
        assertEquals(0L, engine.publication.value.durableRevision)
        assertTrue(engine.ingest(fence, TimelineStreamFrame.Done))
        // The terminal frame only names the first revision the sync writer can reach; it commits nothing.
        assertEquals(0, store.rows.size)
        assertEquals(1L, engine.live.value?.settlementRevision)
        assertEquals(0L, engine.publication.value.durableRevision)
        assertFalse(engine.acknowledgeSettlement(fence, emptyMap()))
        // Other rows being on screen says nothing about this turn; only its own row can settle it.
        assertFalse(engine.acknowledgeSettlement(fence, mapOf(TimelineMessageId("other") to 0L)))
        // Only the sync path writes the row, and its identity becoming resident releases the overlay.
        assertEquals(TimelineEnginePageOutcome.Applied, reconcile(engine, selection, record(reply)))
        assertEquals(1, store.rows.size)
        assertEquals(1L, engine.publication.value.durableRevision)
        assertFalse(engine.acknowledgeSettlement(stale, mapOf(TimelineMessageId("id") to 1L)))
        assertTrue(engine.acknowledgeSettlement(fence, mapOf(TimelineMessageId("id") to 1L)))
        assertEquals(null, engine.live.value)
    }

    @Test fun incrementalSameIdLiveUpdatesDoNotAdvanceSettledPublication() = runTest {
        val store = Store()
        val engine = CanonicalTimelineEngine(store, TimelineExactCanonicalWriter(scope, 100_000), enabled = true)
        val selection = assertIs<TimelineEngineOpen.Opened>(engine.open(scope)).selection
        val published = engine.publication.value
        val fence = engine.beginLive(selection)
        repeat(1_000) { n ->
            assertTrue(engine.ingest(fence, TimelineStreamFrame.Message(message("x".repeat(n + 1)))))
            assertTrue(engine.publication.value === published)
            assertEquals(0, store.rows.size)
            assertEquals(1, engine.live.value?.block?.events?.size)
            assertEquals(null, engine.live.value?.settlementRevision)
        }
        assertEquals(selection, engine.publication.value.selection)
        assertEquals(0L, engine.publication.value.durableRevision)
        assertTrue(engine.ingest(fence, TimelineStreamFrame.Done))
        // The terminal frame leaves the settled publication alone too; live ingest never writes.
        assertTrue(engine.publication.value === published)
        assertEquals(0, store.rows.size)
        assertEquals(1L, engine.live.value?.settlementRevision)
        assertEquals(TimelineEnginePageOutcome.Applied,
            reconcile(engine, selection, record(message("x".repeat(1_000)))))
        assertEquals(1, store.rows.size)
        assertEquals(1L, engine.publication.value.durableRevision)
        assertFalse(engine.publication.value === published)
    }

    @Test fun toolIndexRollsBackWithBodyAndResolvesCanonicalAlias() = runTest {
        val store = Store()
        val writer = TimelineExactCanonicalWriter(scope, 100_000)
        val event = kotlin.test.assertNotNull(message("tool").toTimelineEvent(0.0)).copy(
            messageType = TimelineMessageType.TOOL_CALL,
            toolCalls = listOf(com.letta.mobile.data.model.ToolCall(id = "call", name = "test")).toTimelinePersistentList(),
            otid = "local-tool",
        )
        kotlin.test.assertFailsWith<IllegalStateException> {
            store.transaction(scope) {
                writer.mergeEvent(this, event)
                setToolSweepGeneration(1)
                error("rollback")
            }
        }
        assertEquals(0, store.rows.size)
        store.read(scope) {
            assertEquals(null, toolCall("call"))
            assertEquals(0L, toolSweepGeneration())
        }
        store.transaction(scope) { writer.mergeEvent(this, event); nextRevision() }
        store.transaction(scope) { writer.mergeEvent(this, event.copy(serverId = "alias")) }
        val afterAlias = store.puts
        store.transaction(scope) { writer.mergeEvent(this, event.copy(serverId = "alias")) }
        assertEquals(afterAlias, store.puts, "identical alias replay must not rewrite the canonical body")
        store.read(scope) {
            assertEquals(TimelineMessageId("id"), toolCall("call")?.owner)
            assertEquals(listOf("call"), unresolvedTools(null, 1).map { it.callId })
            assertEquals(emptyList(), unresolvedTools("call", 1))
        }
        store.read(scope.copy(backendId = "other")) {
            assertEquals(null, toolCall("call"))
            assertEquals(emptyList(), unresolvedTools(null, 1))
        }
    }

    @Test fun legacyToolProjectionsWithDifferentServerIdsAndOtidsShareInvocationOwner() = runTest {
        val store = Store()
        val writer = TimelineExactCanonicalWriter(scope, 100_000)
        val first = kotlin.test.assertNotNull(message("tool").toTimelineEvent(0.0)).copy(
            serverId = "ui-message:tool:call:request", otid = "server-ui-tool",
            messageType = TimelineMessageType.TOOL_CALL, runId = null,
            toolCalls = listOf(com.letta.mobile.data.model.ToolCall(id = "call", name = "test")).toTimelinePersistentList(),
        )
        val history = first.copy(serverId = "toolcall-call", otid = "server-toolcall-local-run", runId = "local-run")
        store.transaction(scope) { writer.mergeEvent(this, first); nextRevision() }
        store.transaction(scope) { writer.mergeEvent(this, history); nextRevision() }
        assertEquals(1, store.rows.size)
        store.read(scope) {
            assertEquals(TimelineMessageId(first.serverId), toolCall("call")?.owner)
            assertEquals(TimelineMessageId(first.serverId), writer.canonicalIdentity(this, history.serverId, history.otid))
        }
        store.transaction(scope) { writer.mergeEvent(this, history) }
        assertEquals(1, store.rows.size)
    }

    @Test fun toolGroupCannotMergeTwoExistingInvocationOwners() = runTest {
        val store = Store()
        val writer = TimelineExactCanonicalWriter(scope, 100_000)
        val event = kotlin.test.assertNotNull(message("tool").toTimelineEvent(0.0)).copy(
            messageType = TimelineMessageType.TOOL_CALL, otid = "",
            toolCalls = listOf(com.letta.mobile.data.model.ToolCall(id = "first", name = "test")).toTimelinePersistentList(),
        )
        store.transaction(scope) { writer.mergeEvent(this, event); nextRevision() }
        val other = event.copy(serverId = "other", toolCalls = listOf(
            com.letta.mobile.data.model.ToolCall(id = "second", name = "test"),
        ).toTimelinePersistentList())
        store.transaction(scope) { writer.mergeEvent(this, other); nextRevision() }
        kotlin.test.assertFailsWith<IllegalStateException> {
            store.transaction(scope) {
                writer.mergeEvent(this, event.copy(serverId = "group", toolCalls =
                    (event.toolCalls + other.toolCalls).toTimelinePersistentList()))
            }
        }
        assertEquals(2, store.rows.size)
        store.read(scope) {
            assertEquals(TimelineMessageId(event.serverId), toolCall("first")?.owner)
            assertEquals(TimelineMessageId(other.serverId), toolCall("second")?.owner)
        }
    }

    @Test fun undecodedRawEventFailsWithoutCommittingOrConsumingTypedTurn() = runTest {
        val store = Store()
        val engine = CanonicalTimelineEngine(store, TimelineExactCanonicalWriter(scope, 100_000), enabled = true)
        val selection = assertIs<TimelineEngineOpen.Opened>(engine.open(scope)).selection
        val fence = engine.beginLive(selection)
        kotlin.test.assertFailsWith<IllegalStateException> {
            engine.ingest(fence, TimelineStreamFrame.RawEvent("message", "{}", "cursor"))
        }
        assertEquals(0, store.rows.size)
        assertEquals(0L, engine.publication.value.durableRevision)
        val typed = message("typed")
        assertTrue(engine.ingest(fence, TimelineStreamFrame.Message(typed)))
        assertTrue(engine.ingest(fence, TimelineStreamFrame.Done))
        // The rejected raw frame consumed neither the fence nor the turn's settlement revision.
        assertEquals(0, store.rows.size)
        assertEquals(1L, engine.live.value?.settlementRevision)
        assertEquals(TimelineEnginePageOutcome.Applied, reconcile(engine, selection, record(typed)))
        assertEquals(1, store.rows.size)
    }

    @Test fun pendingSendDoesNotFilterPreviouslyLoadedHistoryAndSelectionStillFencesIt() = runTest {
        val store = Store()
        val engine = CanonicalTimelineEngine(store, TimelineExactCanonicalWriter(scope, 100_000), enabled = true)
        val selection = assertIs<TimelineEngineOpen.Opened>(engine.open(scope)).selection
        val fence = engine.beginLive(selection)
        val prior = message("visible history")
        engine.ingest(fence, TimelineStreamFrame.Message(prior))
        engine.ingest(fence, TimelineStreamFrame.Done)
        // Streamed rows are not history until the sync writer commits them, so drive that first.
        assertEquals(TimelineEnginePageOutcome.Applied, reconcile(engine, selection, record(prior)))
        val revision = engine.publication.value.durableRevision
        assertEquals(1L, revision)
        val event = kotlin.test.assertNotNull(prior.toTimelineEvent(0.0))
        val pending = CanonicalPendingLocalStore(store)
        pending.save(scope, CanonicalPendingLocalStore.Record("send", "new prompt", emptyList(), "2026-09-09T00:00:00Z"))
        assertFalse(engine.isSuppressed(selection, TimelineMessageId(prior.id), revision, event))
        pending.mark(scope, "send", CanonicalPendingLocalStore.Delivery.Sent)
        assertFalse(engine.isSuppressed(selection, TimelineMessageId(prior.id), revision, event))
        engine.open(scope.copy(conversationId = "other"))
        assertTrue(engine.isSuppressed(selection, TimelineMessageId(prior.id), revision, event))
    }

    @Test fun semanticSuppressionRetainsRawBodyAndRejectsExactReplay() = runTest {
        val store = Store()
        val writer = TimelineExactCanonicalWriter(scope, 100_000)
        val engine = CanonicalTimelineEngine(store, writer, enabled = true)
        val selection = assertIs<TimelineEngineOpen.Opened>(engine.open(scope)).selection
        val fence = engine.beginLive(selection)
        val fragment = message("Hi").copy(runId = "run")
        assertTrue(engine.ingest(fence, TimelineStreamFrame.Message(fragment)))
        assertTrue(engine.ingest(fence, TimelineStreamFrame.Done))
        // An abandoned tail is only suppressible once the sync path has made it durable.
        assertEquals(TimelineEnginePageOutcome.Applied, reconcile(engine, selection, record(fragment)))
        val raw = store.rows.values.single().body.copyOf()
        assertEquals(1, engine.suppressAbandonedTail(selection, "run", null, "cancelled", emptySet()))
        assertEquals(1, store.rows.size)
        kotlin.test.assertContentEquals(raw, store.rows.values.single().body)
        val event = kotlin.test.assertNotNull(fragment.toTimelineEvent(0.0))
        assertTrue(engine.isSuppressed(selection, TimelineMessageId(fragment.id), 2L, event))
        // Paging may still transform the previous generation after durable publication.
        assertTrue(engine.isSuppressed(selection, TimelineMessageId(fragment.id), 1L, event))
        store.transaction(scope) {
            assertFalse(writer.merge(this, TimelineRemoteRecord(TimelineMessageId(fragment.id), fragment, 0)))
        }
    }

    @Test fun nextTurnHandsOffSettledOffTailBodyAndRejectsOldAcknowledgment() = runTest {
        val store = Store()
        val engine = CanonicalTimelineEngine(store, TimelineExactCanonicalWriter(scope, 100_000), enabled = true)
        val selection = assertIs<TimelineEngineOpen.Opened>(engine.open(scope)).selection
        val first = engine.beginLive(selection)
        val firstMessage = message("first")
        assertTrue(engine.ingest(first, TimelineStreamFrame.Message(firstMessage)))
        assertTrue(engine.ingest(first, TimelineStreamFrame.Done))
        assertEquals(TimelineEnginePageOutcome.Applied, reconcile(engine, selection, record(firstMessage)))
        val firstBody = store.rows.values.single().body.copyOf()
        val second = engine.beginLive(selection)
        // Starting the next turn drops the finished overlay; its acknowledgment can never land.
        assertFalse(engine.acknowledgeSettlement(first, mapOf(TimelineMessageId("id") to 1L)))
        val secondMessage = message("second").copy(id = "second", otid = "second")
        assertTrue(engine.ingest(second, TimelineStreamFrame.Message(secondMessage)))
        kotlin.test.assertContentEquals(firstBody, store.rows.values.single().body)
        assertTrue(engine.ingest(second, TimelineStreamFrame.Done))
        // The new turn settles one past the revision the previous turn already committed.
        assertEquals(2L, engine.live.value?.settlementRevision)
        assertEquals(TimelineEnginePageOutcome.Applied,
            reconcile(engine, selection, record(firstMessage), record(secondMessage)))
        assertEquals(2, store.rows.size)
        kotlin.test.assertContentEquals(firstBody,
            store.rows.getValue(store.rows.keys.single { it.identity == TimelineMessageId("id") }).body)
        assertFalse(engine.acknowledgeSettlement(first, mapOf(TimelineMessageId("id") to 2L)))
        assertEquals(second, engine.live.value?.fence)
        assertTrue(engine.acknowledgeSettlement(second, mapOf(TimelineMessageId("second") to 2L)))
    }

    /**
     * Pinned once per test: the lost-echo horizon measures against the wall clock, so a fixture
     * dated in the past would be stale by construction and retire itself mid-test.
     */
    private val sentAt = timelineNow().toString()

    /**
     * The agent replying again before the screen has acknowledged the settled turn is ordinary
     * traffic. It used to be fatal: the settled overlay refused the frame and the writer asserted.
     */
    @Test fun externalFrameAfterSettledTurnOpensTheNextTurnInsteadOfFailing() = runTest {
        val store = Store()
        val coordinator = CanonicalTimelineCoordinator(store, PageTransport({ }))
        val external = externalWriter(coordinator, RecordingMaintenance(mutableListOf()), sentAt)
        val owner = coordinator.acquire(scope)
        val screen = kotlin.test.assertNotNull(coordinator.attach(owner))
        external.turnStarted(scope.agentId, scope.conversationId, "run", "turn")
        external.ingestExternalTransportMessage(scope.agentId, scope.conversationId, message("hello"))
        external.turnEnded(scope.agentId, scope.conversationId, clean = true)
        assertEquals(1L, owner.session.live.value?.settlementRevision)
        external.ingestExternalTransportMessage(scope.agentId, scope.conversationId, reply("second", "next turn"))
        // The next turn is live again, carrying only its own frame.
        assertEquals(null, owner.session.live.value?.settlementRevision)
        assertEquals(listOf(TimelineMessageId("second")),
            owner.session.live.value?.block?.events?.map { TimelineMessageId(it.serverId) })
        coordinator.detach(screen)
    }

    /** A turn longer than the overlay budget must truncate the resident view, never fail the turn. */
    @Test fun liveOverlayStopsGrowingAtItsBudgetInsteadOfFailingTheTurn() = runTest {
        val store = Store()
        val engine = CanonicalTimelineEngine(
            store, TimelineExactCanonicalWriter(scope, 100_000), TimelinePageBudget(2, 2L * 1024 * 1024), enabled = true,
        )
        val selection = assertIs<TimelineEngineOpen.Opened>(engine.open(scope)).selection
        val fence = engine.beginLive(selection)
        repeat(4) { n -> assertTrue(engine.ingest(fence, TimelineStreamFrame.Message(reply("m$n", "body $n")))) }
        assertEquals(2, engine.live.value?.block?.events?.size)
        // The turn still settles, so the durable reconcile can carry the rows the overlay dropped.
        assertTrue(engine.ingest(fence, TimelineStreamFrame.Done))
        assertEquals(1L, engine.live.value?.settlementRevision)
    }

    private fun externalWriter(
        coordinator: CanonicalTimelineCoordinator,
        maintenance: CanonicalTimelineMaintenance,
        now: String,
    ) = CanonicalExternalTransportWriter(coordinator, { agentId, conversationId ->
        assertEquals(scope.agentId, agentId)
        assertEquals(scope.conversationId, conversationId)
        scope
    }, maintenance, now = { now })

    @Test fun backgroundCoordinatorCompletionRetainsNonEmptyHistoryAcrossRetirement() = runTest {
        val store = Store()
        var repairStarted = kotlinx.coroutines.CompletableDeferred<Unit>()
        var releaseRepair = kotlinx.coroutines.CompletableDeferred<Unit>()
        var repairSequence: Int? = null
        val transport = RepairOnlyTransport(
            repairStarted = { repairStarted },
            releaseRepair = { releaseRepair },
            repairSequence = { repairSequence },
            reply = message("hello"),
        )
        val coordinator = CanonicalTimelineCoordinator(store, transport)
        val calls = mutableListOf<String>()
        val external = externalWriter(coordinator, RecordingMaintenance(calls), sentAt)
        val local = CanonicalPendingLocalStore.Record("pending-user", "question", emptyList(), sentAt)
        external.appendExternalTransportLocal(scope.agentId, scope.conversationId, local.content, local.otid, local.attachments)
        external.turnStarted(scope.agentId, scope.conversationId, "run", "turn")
        assertEquals(2, external.cleanupAbandonedAssistantFragments(scope.agentId, scope.conversationId, "run", "turn", "cancelled"))
        external.repairExpiredConversationCursorScoped(scope.agentId, scope.conversationId, 10L)
        val owner = coordinator.acquire(scope)
        assertEquals(listOf(local), owner.session.pending.value)
        owner.session.markPending(local.otid, CanonicalPendingLocalStore.Delivery.Sent)
        assertEquals(CanonicalPendingLocalStore.Delivery.Sent, owner.session.pending.value.single().delivery)
        val screen = kotlin.test.assertNotNull(coordinator.attach(owner))
        val fence = coordinator.beginLive(owner)
        external.ingestExternalTransportMessage(scope.agentId, scope.conversationId, message("hello"))
        coordinator.detach(screen)
        assertFalse(coordinator.retire(owner))
        external.turnEnded(scope.agentId, scope.conversationId, clean = false)
        assertEquals(listOf("start:run:turn", "cleanup:cancelled", "repair:10:null", "end:false"), calls)
        assertEquals(null, owner.session.live.value)
        // Live ingest is not a writer: only the two pending-send revisions are durable so far.
        assertEquals(0, store.rows.size)
        assertEquals(2L, store.current.revision)
        assertFalse(coordinator.ingest(owner, fence, TimelineStreamFrame.Done))
        assertTrue(coordinator.retire(owner))
        val reopened = coordinator.acquire(scope)
        assertEquals(2L, reopened.session.publication.value.durableRevision)
        assertEquals(listOf("pending-user"), reopened.session.pending.value.map { it.otid })
        // The reply reaches the ledger only through this path, concurrently with retirement attempts.
        val repair = async { coordinator.reconcileRecentDetailed(reopened) }
        repairStarted.await()
        assertFalse(coordinator.retire(reopened))
        releaseRepair.complete(Unit)
        assertEquals(TimelineEngineReconcileResult(TimelineEnginePageOutcome.Applied, appended = 2), repair.await())
        assertEquals(emptyList(), reopened.session.pending.value)
        val durableBody = store.rows.getValue(
            store.rows.keys.single { it.identity == TimelineMessageId("id") },
        ).body.copyOf()
        assertEquals(TimelineEngineReconcileResult(TimelineEnginePageOutcome.Applied, appended = 0), coordinator.reconcileRecentDetailed(reopened))
        assertEquals(2, store.rows.size)
        assertTrue(store.current.hasMore)
        assertEquals(TimelineContinuation.Initial, store.current.continuation)
        repairStarted = kotlinx.coroutines.CompletableDeferred()
        releaseRepair = kotlinx.coroutines.CompletableDeferred()
        val cancelledRepair = async { coordinator.reconcileRecent(reopened) }
        repairStarted.await()
        assertFalse(coordinator.retire(reopened))
        cancelledRepair.cancel()
        cancelledRepair.join()
        releaseRepair.complete(Unit)
        val failures = mutableListOf<Throwable>()
        val watermarks = mutableListOf<Pair<Long?, Long?>>()
        var backendCurrent = true
        val indexed = IndexedCanonicalTimelineMaintenance(coordinator, this, { _, expected, watermark ->
            check(backendCurrent) { "retired backend" }
            watermarks += expected to watermark
        }, { failures += it })
        kotlin.test.assertFailsWith<IllegalStateException> { indexed.repairCursor(reopened, 12L, expectedWatermark = 4L) }
        assertEquals(emptyList<Pair<Long?, Long?>>(), watermarks)
        repairSequence = 7
        indexed.repairCursor(reopened, 999L, expectedWatermark = 4L)
        assertEquals(listOf<Pair<Long?, Long?>>(4L to 7L), watermarks)
        backendCurrent = false
        val retiredFailure = kotlin.test.assertFailsWith<IllegalStateException> {
            indexed.repairCursor(reopened, 13L, expectedWatermark = 4L)
        }
        assertEquals("retired backend", retiredFailure.message)
        assertEquals(listOf<Pair<Long?, Long?>>(4L to 7L), watermarks)
        assertEquals(0, indexed.cleanup(reopened, TimelineTurnCleanup("missing", null, "test", emptySet())))
        for (clean in listOf(true, false)) {
            indexed.turnEnded(reopened, clean)
            assertFalse(coordinator.retire(reopened))
            indexed.turnStarted(reopened, "replacement", null)
            runCurrent()
            assertEquals(0, reopened.activeRepairs)
            indexed.turnEnded(reopened, clean)
            advanceUntilIdle()
            assertEquals(0, reopened.activeRepairs)
        }
        assertEquals(emptyList(), failures)
        assertTrue(coordinator.retire(reopened))
        // Retiring the owner never discards settled history; the reacquired ledger still serves it.
        val revived = coordinator.acquire(scope)
        val tail = revived.session.engine.load(revived.selection, TimelineReadPosition.Tail, 2)
        assertTrue(tail.bodies.any { it.contentEquals(durableBody) })
        assertTrue(coordinator.retire(revived))
    }

    @Test fun userEchoConfirmsPendingAtomicallyIncludingReplay() = runTest {
        val store = Store()
        val pending = CanonicalPendingLocalStore(store)
        val writer = TimelineExactCanonicalWriter(scope, 100_000)
        val local = CanonicalPendingLocalStore.Record("local", "hello", emptyList(), "2026-01-01T00:00:00Z")
        pending.save(scope, local)
        store.transaction(scope) {
            writer.merge(this, TimelineRemoteRecord(TimelineMessageId("id"), message("reply").copy(otid = "assistant-local"), 0))
        }
        assertEquals(listOf(local), pending.load(scope))
        val echo = com.letta.mobile.data.model.UserMessage(
            id = "user", contentRaw = kotlinx.serialization.json.JsonPrimitive("hello"),
            date = local.sentAt, otid = local.otid,
        )
        val record = TimelineRemoteRecord(TimelineMessageId("user"), echo, 0)
        kotlin.test.assertFailsWith<IllegalStateException> {
            store.transaction(scope) {
                writer.merge(this, record)
                error("simulated commit failure")
            }
        }
        assertEquals(listOf(local), pending.load(scope))
        assertEquals(1, store.rows.size)
        store.transaction(scope) { assertTrue(writer.merge(this, record)) }
        assertEquals(emptyList(), pending.load(scope))
        pending.save(scope, local)
        store.transaction(scope) { assertTrue(writer.merge(this, record)) }
        assertEquals(emptyList(), pending.load(scope))
        assertEquals(2, store.rows.size)
    }

    @Test fun recentRepairPreservesOlderCursorAndRejectsSupersededRequests() = runTest {
        val store = Store()
        val engine = CanonicalTimelineEngine(store, TimelineExactCanonicalWriter(scope, 100_000), enabled = true)
        val selection = assertIs<TimelineEngineOpen.Opened>(engine.open(scope)).selection
        val older = engine.beginPage(selection)
        val stale = engine.beginReconcile(selection)
        val current = engine.beginReconcile(selection)
        fun page(request: TimelineEngineRequest) = TimelineRemotePageResult.Page(
            request.remote.requestId, selection.generation,
            listOf(TimelineRemoteRecord(TimelineMessageId("id"), message("hello"), 0)), null, false, 0,
        )
        assertEquals(TimelineEnginePageOutcome.Stale, engine.reconcilePage(stale, page(stale)))
        assertEquals(0, store.rows.size)
        engine.cancelReconcile(stale)
        assertEquals(TimelineEnginePageOutcome.Applied, engine.reconcilePage(current, page(current)))
        assertEquals(TimelineContinuation.Initial, store.current.continuation)
        assertTrue(store.current.hasMore)
        assertEquals(TimelineEnginePageOutcome.Stale, engine.reconcilePage(current, page(current)))
        assertEquals(TimelineEnginePageOutcome.Applied, engine.applyPage(older, page(older)))
        assertEquals(1, store.rows.size)
        assertFalse(store.current.hasMore)
        val beforeRun = engine.beginReconcile(selection)
        engine.beginLive(selection)
        assertEquals(TimelineEnginePageOutcome.Stale, engine.reconcilePage(beforeRun, page(beforeRun)))
    }

    @Test fun exactWriterPreservesKeyOnDuplicateAndReopen() = runTest {
        val store = Store()
        val writer = TimelineExactCanonicalWriter(scope, 100_000)
        val record = TimelineRemoteRecord(TimelineMessageId("id"), message("hello"), 0)
        store.transaction(scope) { assertTrue(writer.merge(this, record)); nextRevision() }
        val key = store.rows.keys.single()
        store.transaction(scope) { assertFalse(TimelineExactCanonicalWriter(scope, 100_000).merge(this, record)) }
        assertEquals(key, store.rows.keys.single())
        assertEquals(2, store.evidence.size)
    }

    @Test fun previewResolutionChecksRevisionAndReadsOnlySelectedBody() = runTest {
        val store = Store()
        val writer = TimelineExactCanonicalWriter(scope, 200_000)
        store.transaction(scope) {
            writer.merge(this, TimelineRemoteRecord(TimelineMessageId("id"), message("x".repeat(100_000)), 0))
            nextRevision()
        }
        val engine = CanonicalTimelineEngine(store, writer, enabled = true)
        val selection = assertIs<TimelineEngineOpen.Opened>(engine.open(scope)).selection
        val page = engine.load(selection, TimelineReadPosition.Tail, 1)
        val row = page.metadata.rows.single()
        val preview = TimelineSettledRecord(row.key, row.contentType, page.bodies.single(), page.metadata.revision, row.body)
        assertTrue(preview.isPreview)
        kotlin.test.assertFailsWith<IllegalArgumentException> { preview.toRenderItem() }
        val resolved = engine.resolveBody(selection, preview)
        assertFalse(resolved.isPreview)
        val item = assertIs<com.letta.mobile.data.chat.projection.ChatRenderItem.Single>(resolved.toRenderItem())
        assertEquals("x".repeat(100_000), item.message.content)
        assertEquals(row.body.encodedBytes, resolved.body.size.toLong())
        store.transaction(scope) { nextRevision() }
        kotlin.test.assertFailsWith<IllegalStateException> { engine.resolveBody(selection, preview) }
    }

    @Test fun historicalBodyReadsRespectPlatformChunkLimit() = runTest {
        val store = Store()
        val writer = TimelineExactCanonicalWriter(scope, 200_000)
        val record = TimelineRemoteRecord(TimelineMessageId("id"), message("x".repeat(100_000)), 0)
        store.transaction(scope) { writer.merge(this, record); nextRevision() }
        store.transaction(scope) { assertFalse(writer.merge(this, record)) }
        assertEquals(2, store.bodyReads)
    }

    @Test fun concurrentCursorCompletionsOnlyCommitCurrentRequest() = runTest {
        val store = Store()
        val engine = CanonicalTimelineEngine(store, TimelineExactCanonicalWriter(scope, 100_000), enabled = true)
        val selection = assertIs<TimelineEngineOpen.Opened>(engine.open(scope)).selection
        val requests = (1..64).map { engine.beginPage(selection) }
        val results = requests.map { request -> async {
            engine.applyPage(request, TimelineRemotePageResult.Page(request.remote.requestId, selection.generation,
                emptyList(), null, false, 0))
        } }.awaitAll()
        assertEquals(1, results.count { it == TimelineEnginePageOutcome.Applied })
        assertEquals(63, results.count { it == TimelineEnginePageOutcome.Stale })
        assertEquals(1L, store.current.revision)
    }

    @Test fun historicalCorrectionReadsOneBodyAmong28kRows() = runTest {
        val store = Store()
        val writer = TimelineExactCanonicalWriter(scope, 100_000)
        store.transaction(scope) { writer.merge(this, TimelineRemoteRecord(TimelineMessageId("id"), message("hello"), 0)) }
        val seed = store.rows.values.single()
        repeat(28_000) { index ->
            val key = TimelinePageKey(index.toLong(), TimelineMessageId("history-$index"))
            store.rows[key] = seed.copy(key = key)
        }
        store.bodyReads = 0
        store.transaction(scope) { writer.merge(this, TimelineRemoteRecord(TimelineMessageId("id"), message("hello world"), 0)) }
        assertEquals(1, store.bodyReads)
        assertEquals(28_001, store.rows.size)
    }

    @Test fun publishedBlockSettlesOnlyWhenItsCommittedRevisionIsResident() = runTest {
        val store = Store()
        val engine = CanonicalTimelineEngine(store, TimelineExactCanonicalWriter(scope, 100_000), enabled = true)
        val selection = assertIs<TimelineEngineOpen.Opened>(engine.open(scope)).selection
        val fence = engine.beginLive(selection)
        // publishLive is a sync-side path: it commits, and names the revision it committed.
        assertTrue(engine.publishLive(fence, TimelineLiveBlock(listOf(record(message("hello"))), true)))
        val revision = engine.publication.value.durableRevision
        assertEquals(1L, revision)
        assertEquals(1, store.rows.size)
        // The block carries rows, so it drains once they are resident rather than settling immediately.
        assertFalse(engine.acknowledgeSettlement(fence, emptyMap()))
        assertFalse(engine.acknowledgeSettlement(fence, mapOf(TimelineMessageId("other") to revision)))
        assertTrue(engine.acknowledgeSettlement(fence, mapOf(TimelineMessageId("id") to revision)))
        assertEquals(null, engine.live.value)
    }

    @Test fun everyStreamedNameForOneToolCallAdoptsItsCommittedIdentity() = runTest {
        // A tool call reaches the overlay under names the ledger never uses, and a stream emits a
        // synthetic return before the real one, so ONE call arrives under several names while the
        // sync page commits a single row. All of them must drain, or the card renders twice and
        // keeps running its own lifecycle after the settled row has completed.
        val callId = "call_3c3732f1a4a54887b0b79b3c"
        val engine = CanonicalTimelineEngine(Store(), TimelineExactCanonicalWriter(scope, 100_000), enabled = true)
        val selection = assertIs<TimelineEngineOpen.Opened>(engine.open(scope)).selection
        val fence = engine.beginLive(selection)
        val streamedCall = com.letta.mobile.data.model.ToolCallMessage(
            id = "toolcall-$callId", date = "2026-01-01T00:00:00Z",
            toolCalls = listOf(com.letta.mobile.data.model.ToolCall(toolCallId = callId, name = "Bash", arguments = "{}")),
        )
        assertTrue(engine.ingest(fence, TimelineStreamFrame.Message(streamedCall)))
        assertTrue(engine.ingest(fence, TimelineStreamFrame.Done))
        val streamedNames = kotlin.test.assertNotNull(engine.live.value).block.events
            .filter { it.messageType == TimelineMessageType.TOOL_CALL }
            .map { it.serverId }
        assertEquals(listOf("toolcall-$callId"), streamedNames)

        // The sync page commits the call under the name the ledger uses for it.
        val committedCall = com.letta.mobile.data.model.ToolCallMessage(
            id = "ui-msg-9154162", date = "2026-01-01T00:00:00Z",
            toolCalls = listOf(com.letta.mobile.data.model.ToolCall(toolCallId = callId, name = "Bash", arguments = "{}")),
        )
        assertEquals(TimelineEnginePageOutcome.Applied, reconcile(engine, selection, record(committedCall)))

        // The streamed name now resolves to the committed identity, whatever shape that identity has.
        val aliases = kotlin.test.assertNotNull(engine.live.value).aliases
        assertTrue("toolcall-$callId" in aliases, "streamed tool call must adopt a committed identity")
        assertTrue(
            aliases.getValue("toolcall-$callId").value.contains(callId) ||
                aliases.getValue("toolcall-$callId").value == "ui-msg-9154162",
            "adopted identity must be the row the writer stored, not the streamed name",
        )
    }

    @Test fun reconcileAdoptsTheCommittedIdentityForAStreamedReplyThatSharesNoId() = runTest {
        // The shape observed on device: the stream names the reply cm-stream-* and derives its otid
        // from that id, the server names it ui-msg-* and derives its otid from ITS id. Nothing links
        // them, so without adoption the overlay and the settled row both stay on screen and the
        // reply is visible twice. This page is the one moment both names are in hand.
        val streamedId = "cm-stream-provider-assistant-0-75ab1210"
        val committedId = "ui-msg-9154136"
        val engine = CanonicalTimelineEngine(Store(), TimelineExactCanonicalWriter(scope, 100_000), enabled = true)
        val selection = assertIs<TimelineEngineOpen.Opened>(engine.open(scope)).selection
        val fence = engine.beginLive(selection)
        val reply = AssistantMessage(
            id = streamedId, contentRaw = kotlinx.serialization.json.JsonPrimitive("Hey. What's up?"),
            date = "2026-01-01T00:00:00Z",
        )
        assertTrue(engine.ingest(fence, TimelineStreamFrame.Message(reply)))
        assertTrue(engine.ingest(fence, TimelineStreamFrame.Done))
        val settlement = kotlin.test.assertNotNull(engine.live.value?.settlementRevision)
        val presented = mapOf(TimelineMessageId(committedId) to settlement)
        // Nothing links the two names yet, so the settled row cannot settle the turn.
        assertFalse(kotlin.test.assertNotNull(engine.live.value).isSettled(presented))

        val committed = AssistantMessage(
            id = committedId, contentRaw = kotlinx.serialization.json.JsonPrimitive("Hey. What's up?"),
            date = "2026-01-01T00:00:00Z",
        )
        assertEquals(TimelineEnginePageOutcome.Applied, reconcile(engine, selection, record(committed)))

        // Reconcile saw both names at once and adopted the committed one, so the overlay drains.
        val live = kotlin.test.assertNotNull(engine.live.value)
        assertEquals(mapOf(streamedId to TimelineMessageId(committedId)), live.aliases)
        assertTrue(live.isSettled(presented))
        assertEquals(emptyList(), live.overlayEvents(presented))
        assertTrue(engine.acknowledgeSettlement(fence, presented))
        assertEquals(null, engine.live.value)
    }

    @Test fun aTruncatedStreamStillAdoptsWhenBothSidesCarryTheSegmentOtid() = runTest {
        // The duplicate seen on device 2026-09-11: the stream stopped one character short of the
        // stored reply, so a content join found nothing and BOTH rows rendered. The App Server
        // mints a stable otid per streamed segment and now persists it, so the two sides share an
        // identifier and the missing tail stops mattering.
        val segmentOtid = "provider-assistant-0-23a84950"
        val streamedId = "cm-stream-$segmentOtid"
        val committedId = "ui-msg-9155077"
        val engine = CanonicalTimelineEngine(Store(), TimelineExactCanonicalWriter(scope, 100_000), enabled = true)
        val selection = assertIs<TimelineEngineOpen.Opened>(engine.open(scope)).selection
        val fence = engine.beginLive(selection)
        assertTrue(engine.ingest(fence, TimelineStreamFrame.Message(AssistantMessage(
            id = streamedId, otid = segmentOtid,
            contentRaw = kotlinx.serialization.json.JsonPrimitive("What color did you want it to be"),
            date = "2026-01-01T00:00:00Z",
        ))))
        assertTrue(engine.ingest(fence, TimelineStreamFrame.Done))
        val settlement = kotlin.test.assertNotNull(engine.live.value?.settlementRevision)
        val presented = mapOf(TimelineMessageId(committedId) to settlement)

        // One character longer than the stream ever showed — the final "?" never arrived.
        val committed = AssistantMessage(
            id = committedId, otid = segmentOtid,
            contentRaw = kotlinx.serialization.json.JsonPrimitive("What color did you want it to be?"),
            date = "2026-01-01T00:00:00Z",
        )
        assertEquals(TimelineEnginePageOutcome.Applied, reconcile(engine, selection, record(committed)))

        val live = kotlin.test.assertNotNull(engine.live.value)
        assertEquals(mapOf(streamedId to TimelineMessageId(committedId)), live.aliases)
        assertTrue(live.isSettled(presented), "the otid pairs them even though the texts differ")
        assertEquals(emptyList(), live.overlayEvents(presented))
    }

    @Test fun twoRepliesReadingTheSameAdoptTheirOwnRows() = runTest {
        // Identical text is not identity. With a segment otid each, the rows stay distinct rather
        // than collapsing onto one — the failure a pure content join is always one coincidence away
        // from.
        val engine = CanonicalTimelineEngine(Store(), TimelineExactCanonicalWriter(scope, 100_000), enabled = true)
        val selection = assertIs<TimelineEngineOpen.Opened>(engine.open(scope)).selection
        val fence = engine.beginLive(selection)
        val text = kotlinx.serialization.json.JsonPrimitive("Sure.")
        assertTrue(engine.ingest(fence, TimelineStreamFrame.Message(AssistantMessage(
            id = "cm-stream-provider-assistant-0-aaa", otid = "provider-assistant-0-aaa",
            contentRaw = text, date = "2026-01-01T00:00:00Z",
        ))))
        assertTrue(engine.ingest(fence, TimelineStreamFrame.Message(AssistantMessage(
            id = "cm-stream-provider-assistant-2-bbb", otid = "provider-assistant-2-bbb",
            contentRaw = text, date = "2026-01-01T00:00:01Z",
        ))))
        assertTrue(engine.ingest(fence, TimelineStreamFrame.Done))

        assertEquals(
            TimelineEnginePageOutcome.Applied,
            reconcile(
                engine, selection,
                record(AssistantMessage(
                    id = "ui-msg-1", otid = "provider-assistant-0-aaa",
                    contentRaw = text, date = "2026-01-01T00:00:00Z",
                )),
                record(AssistantMessage(
                    id = "ui-msg-2", otid = "provider-assistant-2-bbb",
                    contentRaw = text, date = "2026-01-01T00:00:01Z",
                )),
            ),
        )

        val live = kotlin.test.assertNotNull(engine.live.value)
        assertEquals(
            mapOf(
                "cm-stream-provider-assistant-0-aaa" to TimelineMessageId("ui-msg-1"),
                "cm-stream-provider-assistant-2-bbb" to TimelineMessageId("ui-msg-2"),
            ),
            live.aliases,
            "each reply adopts the row that carries its own otid",
        )
    }

    @Test fun aTruncatedStreamAdoptsByPositionWhenNothingElsePairsThem() = runTest {
        // Stock letta-code shares no identifier for an assistant reply, so the join falls to text —
        // and text is only as good as the stream being complete. On device 2026-09-11 a dropped
        // tail delta left the streamed row at 368 characters against a stored 369 and both
        // rendered. Position pairs them anyway: one reply streamed, one reply committed.
        val streamedId = "cm-stream-provider-assistant-0-23a84950"
        val committedId = "ui-msg-9155077"
        val engine = CanonicalTimelineEngine(Store(), TimelineExactCanonicalWriter(scope, 100_000), enabled = true)
        val selection = assertIs<TimelineEngineOpen.Opened>(engine.open(scope)).selection
        val fence = engine.beginLive(selection)
        assertTrue(engine.ingest(fence, TimelineStreamFrame.Message(AssistantMessage(
            id = streamedId,
            contentRaw = kotlinx.serialization.json.JsonPrimitive("What color did you want it to be"),
            date = "2026-01-01T00:00:00Z",
        ))))
        assertTrue(engine.ingest(fence, TimelineStreamFrame.Done))
        val settlement = kotlin.test.assertNotNull(engine.live.value?.settlementRevision)
        val presented = mapOf(TimelineMessageId(committedId) to settlement)

        assertEquals(TimelineEnginePageOutcome.Applied, reconcile(engine, selection, record(AssistantMessage(
            id = committedId,
            contentRaw = kotlinx.serialization.json.JsonPrimitive("What color did you want it to be?"),
            date = "2026-01-01T00:00:00Z",
        ))))

        val live = kotlin.test.assertNotNull(engine.live.value)
        assertEquals(mapOf(streamedId to TimelineMessageId(committedId)), live.aliases)
        assertTrue(live.isSettled(presented), "position pairs them even though the texts differ")
        assertEquals(emptyList(), live.overlayEvents(presented))
    }

    @Test fun positionPairsSeveralRepliesInTheOrderTheyHappened() = runTest {
        val engine = CanonicalTimelineEngine(Store(), TimelineExactCanonicalWriter(scope, 100_000), enabled = true)
        val selection = assertIs<TimelineEngineOpen.Opened>(engine.open(scope)).selection
        val fence = engine.beginLive(selection)
        // Both truncated, so nothing matches by text.
        assertTrue(engine.ingest(fence, TimelineStreamFrame.Message(AssistantMessage(
            id = "cm-stream-a", contentRaw = kotlinx.serialization.json.JsonPrimitive("first repl"),
            date = "2026-01-01T00:00:00Z",
        ))))
        assertTrue(engine.ingest(fence, TimelineStreamFrame.Message(AssistantMessage(
            id = "cm-stream-b", contentRaw = kotlinx.serialization.json.JsonPrimitive("second rep"),
            date = "2026-01-01T00:00:01Z",
        ))))
        assertTrue(engine.ingest(fence, TimelineStreamFrame.Done))

        assertEquals(TimelineEnginePageOutcome.Applied, reconcile(
            engine, selection,
            record(AssistantMessage(id = "ui-msg-1",
                contentRaw = kotlinx.serialization.json.JsonPrimitive("first reply"),
                date = "2026-01-01T00:00:00Z")),
            record(AssistantMessage(id = "ui-msg-2",
                contentRaw = kotlinx.serialization.json.JsonPrimitive("second reply"),
                date = "2026-01-01T00:00:01Z")),
        ))

        assertEquals(
            mapOf("cm-stream-a" to TimelineMessageId("ui-msg-1"), "cm-stream-b" to TimelineMessageId("ui-msg-2")),
            kotlin.test.assertNotNull(engine.live.value).aliases,
            "the k-th streamed reply is the k-th reply the turn committed",
        )
    }

    @Test fun positionNeverPairsAgainstRowsThePageDidNotAppend() = runTest {
        // A reconcile page is a window of recent history, not a turn. An older reply already in
        // the ledger must not be handed to a streamed row just because it is in the page: that is
        // how a positional join would rewrite one reply into another.
        val store = Store()
        val engine = CanonicalTimelineEngine(store, TimelineExactCanonicalWriter(scope, 100_000), enabled = true)
        val selection = assertIs<TimelineEngineOpen.Opened>(engine.open(scope)).selection
        val older = AssistantMessage(id = "ui-msg-older",
            contentRaw = kotlinx.serialization.json.JsonPrimitive("an older reply"),
            date = "2026-01-01T00:00:00Z")
        assertEquals(TimelineEnginePageOutcome.Applied, reconcile(engine, selection, record(older)))

        val fence = engine.beginLive(selection)
        assertTrue(engine.ingest(fence, TimelineStreamFrame.Message(AssistantMessage(
            id = "cm-stream-new", contentRaw = kotlinx.serialization.json.JsonPrimitive("a new repl"),
            date = "2026-01-01T00:00:02Z",
        ))))
        assertTrue(engine.ingest(fence, TimelineStreamFrame.Done))

        // The page carries the older row again and nothing new: there is nothing to pair with.
        assertEquals(TimelineEnginePageOutcome.Applied, reconcile(engine, selection, record(older)))
        assertEquals(
            emptyMap(),
            kotlin.test.assertNotNull(engine.live.value).aliases,
            "a resident row is not a candidate for position",
        )
    }

    @Test fun streamedAssistantIdIsAliasedToItsCanonicalIdentityWhateverItsShape() = runTest {
        val store = Store()
        // The turn already has a canonical identity, recorded against the otid the stream carries.
        store.evidence["identity/otid/server-reply-assistant"] = "msg-canonical".encodeToByteArray()
        // The shape the live transport actually streams. A guard that matched a prefix instead of
        // comparing the two names missed every real reply, and the overlay could never drain.
        val streamedId = "cm-stream-provider-assistant-0-1732a7f3"
        val streamed = AssistantMessage(
            id = streamedId, contentRaw = kotlinx.serialization.json.JsonPrimitive("hello"),
            date = "2026-01-01T00:00:00Z", otid = "server-reply-assistant",
        )
        store.transaction(scope) {
            TimelineExactCanonicalWriter(scope, 100_000)
                .merge(this, TimelineRemoteRecord(TimelineMessageId(streamedId), streamed, 0))
        }
        assertEquals("msg-canonical", store.evidence["identity/serverId/$streamedId"]?.decodeToString())
    }

    @Test fun anAlreadyCanonicalRowRecordsNoAlias() = runTest {
        val store = Store()
        // Re-merging a row whose name is already canonical, as a migration replay does, must not
        // accumulate evidence: the two names do not differ, so there is nothing to record.
        store.transaction(scope) {
            TimelineExactCanonicalWriter(scope, 100_000)
                .merge(this, TimelineRemoteRecord(TimelineMessageId("id"), message("hello"), 0))
        }
        assertEquals(emptyList(), store.evidence.keys.filter { it.startsWith("identity/serverId/") })
    }

    @Test fun oversizedHistoricalMergeRollsBackWithoutLosingRetry() = runTest {
        val store = Store()
        store.transaction(scope) { TimelineExactCanonicalWriter(scope, 100_000).merge(this,
            TimelineRemoteRecord(TimelineMessageId("id"), message("hello"), 0)); nextRevision() }
        val before = store.rows.values.single().body.copyOf()
        val engine = CanonicalTimelineEngine(store, TimelineExactCanonicalWriter(scope, 1), enabled = true)
        val selection = assertIs<TimelineEngineOpen.Opened>(engine.open(scope)).selection
        val request = engine.beginPage(selection)
        val page = TimelineRemotePageResult.Page(request.remote.requestId, selection.generation,
            listOf(TimelineRemoteRecord(TimelineMessageId("id"), message("hello world"), 0)), null, false, 0)
        repeat(2) {
            val failure = kotlin.test.assertFailsWith<TimelineMergeUnavailable> { engine.applyPage(request, page) }
            assertEquals("historical_body_budget", failure.reason)
            kotlin.test.assertContentEquals(before, store.rows.values.single().body)
            assertTrue(store.current.hasMore)
            assertEquals(1L, store.current.revision)
        }
    }

    @Test fun settlementBudgetFailureRollsBackEarlierOwnerBodyAndIndex() = runTest {
        val store = Store()
        val writer = TimelineExactCanonicalWriter(scope, 100_000)
        fun tool(id: String, content: String) = kotlin.test.assertNotNull(message(content).toTimelineEvent(0.0)).copy(
            serverId = id, otid = id, messageType = TimelineMessageType.TOOL_CALL,
            toolCalls = listOf(com.letta.mobile.data.model.ToolCall(id = id, name = "test")).toTimelinePersistentList(),
        )
        store.transaction(scope) {
            writer.mergeEvent(this, tool("a", "small"))
            writer.mergeEvent(this, tool("z", "x".repeat(20_000)))
            nextRevision()
        }
        val before = store.rows.mapValues { it.value.body.copyOf() }
        val engine = CanonicalTimelineEngine(store, writer, TimelinePageBudget(64, 10_000), enabled = true)
        val selection = assertIs<TimelineEngineOpen.Opened>(engine.open(scope)).selection
        val generation = engine.advanceToolSweep(selection)
        kotlin.test.assertFailsWith<IllegalArgumentException> { engine.settleToolSweep(selection, generation) }
        assertTrue(store.bodyReads > 0)
        before.forEach { (key, bytes) -> kotlin.test.assertContentEquals(bytes, store.rows.getValue(key).body) }
        assertEquals(2L, store.current.revision)
        assertEquals(2L, engine.publication.value.durableRevision)
        store.read(scope) {
            assertEquals(generation, toolSweepGeneration())
            assertEquals(listOf("a", "z"), unresolvedTools(null, 64).map { it.callId })
            assertFalse(kotlin.test.assertNotNull(toolCall("a")).returned)
        }
    }

    @Test fun blockedSweepCancellationReleasesLeaseAndCannotFailNextTurn() = runTest {
        val store = Store()
        val entered = kotlinx.coroutines.CompletableDeferred<Unit>()
        val release = kotlinx.coroutines.CompletableDeferred<Unit>()
        val coordinator = CanonicalTimelineCoordinator(store, PageTransport(barrier = {
            entered.complete(Unit)
            release.await()
        }))
        val owner = coordinator.acquire(scope)
        val failures = mutableListOf<Throwable>()
        val maintenance = IndexedCanonicalTimelineMaintenance(coordinator, this, { _, _, _ -> error("unexpected cursor") }, { failures += it })
        maintenance.turnEnded(owner, false)
        entered.await()
        assertFalse(coordinator.retire(owner))
        maintenance.turnStarted(owner, "next", null)
        val next = coordinator.beginLive(owner)
        assertTrue(coordinator.ingest(owner, next, TimelineStreamFrame.Message(message("next"))))
        release.complete(Unit)
        runCurrent()
        assertEquals(0, owner.activeRepairs)
        assertEquals(next, owner.session.live.value?.fence)
        assertEquals(emptyList(), failures)
        assertTrue(coordinator.ingest(owner, next, TimelineStreamFrame.Done))
        assertTrue(coordinator.retire(owner))
        assertFalse(coordinator.retire(owner))
    }

    @Test fun attachedSettledOverlayDrainsOnSyncWriteBeforeRepairAndNextTurn() = runTest {
        val store = Store()
        val remote = mutableListOf<TimelineRemoteRecord>()
        val coordinator = CanonicalTimelineCoordinator(store, PageTransport({}, { remote.toList() }))
        val owner = coordinator.acquire(scope)
        val presentation = kotlin.test.assertNotNull(coordinator.attach(owner))
        val first = coordinator.beginLive(owner)
        val firstMessage = message("durable off-tail")
        coordinator.ingest(owner, first, TimelineStreamFrame.Message(firstMessage))
        coordinator.ingest(owner, first, TimelineStreamFrame.Done)
        // While a viewport is attached the overlay is the turn's only copy, and nothing is durable.
        assertEquals(0, store.rows.size)
        assertEquals(first, owner.session.live.value?.fence)
        remote += TimelineRemoteRecord(TimelineMessageId(firstMessage.id), firstMessage, 0)
        assertEquals(TimelineEnginePageOutcome.Applied, coordinator.reconcileRecent(owner))
        assertEquals(1, store.rows.size)
        assertEquals(1L, owner.session.publication.value.durableRevision)
        val bytes = store.rows.values.single().body.copyOf()
        // Repair no longer force-releases; only this turn's own resident row drains the overlay.
        assertEquals(first, owner.session.live.value?.fence)
        assertFalse(coordinator.acknowledgeSettlement(owner, first, mapOf(TimelineMessageId("other") to 1L)))
        assertTrue(coordinator.acknowledgeSettlement(owner, first, mapOf(TimelineMessageId("id") to 1L)))
        assertEquals(null, owner.session.live.value)
        kotlin.test.assertContentEquals(bytes, store.rows.values.single().body)
        val next = coordinator.beginLive(owner)
        val nextMessage = message("next").copy(id = "next", otid = "next")
        coordinator.ingest(owner, next, TimelineStreamFrame.Message(nextMessage))
        assertFalse(coordinator.acknowledgeSettlement(owner, first, mapOf(TimelineMessageId("id") to 1L)))
        assertEquals(next, owner.session.live.value?.fence)
        coordinator.ingest(owner, next, TimelineStreamFrame.Done)
        remote += TimelineRemoteRecord(TimelineMessageId(nextMessage.id), nextMessage, 0)
        assertEquals(TimelineEnginePageOutcome.Applied, coordinator.reconcileRecent(owner))
        assertTrue(coordinator.acknowledgeSettlement(owner, next, mapOf(TimelineMessageId("next") to 2L)))
        coordinator.detach(presentation)
        val maintenance = IndexedCanonicalTimelineMaintenance(coordinator, this, { _, _, _ -> error("unexpected cursor") }, { throw it })
        repeat(2) {
            maintenance.turnEnded(owner, true)
            assertFalse(coordinator.retire(owner))
            advanceUntilIdle()
            assertEquals(0, owner.activeRepairs)
        }
        assertTrue(coordinator.retire(owner))
        assertEquals(2, store.rows.size)
    }

    /** Live ingest writes nothing, so a settled row exists only once this path has run. */
    @Test fun reconcileRefusesMidStreamThenServesTheSettledTurn() = runTest {
        val store = Store()
        val engine = CanonicalTimelineEngine(store, TimelineExactCanonicalWriter(scope, 100_000), enabled = true)
        val selection = assertIs<TimelineEngineOpen.Opened>(engine.open(scope)).selection
        val reply = message("hello")
        val fence = engine.beginLive(selection)
        assertTrue(engine.ingest(fence, TimelineStreamFrame.Message(reply)))
        // Mid-stream the turn is still being reduced, so the sync writer must not commit under it.
        assertEquals(TimelineEnginePageOutcome.NoProgress, reconcile(engine, selection, record(reply)))
        assertEquals(0, store.rows.size)
        assertEquals(0L, engine.publication.value.durableRevision)
        // Once the turn is settled the fence is still held, but refusing here would strand the reply:
        // this path is now its only route to durability.
        assertTrue(engine.ingest(fence, TimelineStreamFrame.Done))
        assertEquals(TimelineEnginePageOutcome.Applied, reconcile(engine, selection, record(reply)))
        assertEquals(1, store.rows.size)
        assertTrue(engine.acknowledgeSettlement(fence, mapOf(TimelineMessageId("id") to 1L)))
    }

    /** Fresh pending store plus a record factory: every pending test needs exactly this. */
    private fun pendingFixture(): Pair<CanonicalPendingLocalStore, (Int, String) -> CanonicalPendingLocalStore.Record> {
        val store = CanonicalPendingLocalStore(Store())
        return store to { n, at -> CanonicalPendingLocalStore.Record("otid-$n", "attempt $n", emptyList(), at) }
    }

    @Test fun theNewestFailureSurvivesWhateverOrderOutcomesArriveIn() = runTest {
        val (pending, record) = pendingFixture()
        pending.save(scope, record(1, "2026-01-01T00:00:00Z"))
        pending.save(scope, record(2, "2026-01-01T00:00:01Z"))
        // Transport outcomes can land out of order: the later send fails first.
        pending.mark(scope, "otid-2", CanonicalPendingLocalStore.Delivery.Failed)
        pending.mark(scope, "otid-1", CanonicalPendingLocalStore.Delivery.Failed)
        assertEquals(listOf("otid-2"), pending.load(scope).map { it.otid })

        // Retirement must not resurrect an older send over a newer failure either.
        val (pending2, record2) = pendingFixture()
        pending2.save(scope, record2(3, "2026-01-01T00:00:00Z"))
        pending2.save(scope, record2(4, "2026-01-01T00:00:01Z"))
        pending2.mark(scope, "otid-3", CanonicalPendingLocalStore.Delivery.Sent)
        pending2.mark(scope, "otid-4", CanonicalPendingLocalStore.Delivery.Failed)
        val now = parseTimelineInstant("2026-01-01T01:00:00Z")
        assertEquals(1, pending2.retireLostEchoes(scope, pending2.load(scope), now))
        assertEquals(listOf("otid-4"), pending2.load(scope).map { it.otid })
    }

    @Test fun aSendWhoseEchoNeverCameIsRetiredButALiveOneIsNot() = runTest {
        val (pending, record) = pendingFixture()
        val now = parseTimelineInstant("2026-01-01T01:00:00Z")
        pending.save(scope, record(1, "2026-01-01T00:00:00Z"))
        pending.save(scope, record(2, "2026-01-01T00:30:00Z"))
        pending.save(scope, record(3, "2026-01-01T00:59:59Z"))
        listOf(1, 2, 3).forEach { pending.mark(scope, "otid-$it", CanonicalPendingLocalStore.Delivery.Sent) }

        // Two are long past any plausible echo; the third was accepted a second ago.
        assertEquals(2, pending.retireLostEchoes(scope, pending.load(scope), now))
        val remaining = pending.load(scope)
        assertEquals(listOf("otid-2", "otid-3"), remaining.map { it.otid })
        assertEquals(CanonicalPendingLocalStore.Delivery.Failed, remaining.first { it.otid == "otid-2" }.delivery)
        assertEquals(CanonicalPendingLocalStore.Delivery.Sent, remaining.first { it.otid == "otid-3" }.delivery)
        // Idempotent: a second pass finds nothing new to retire.
        assertEquals(0, pending.retireLostEchoes(scope, pending.load(scope), now))
    }

    @Test fun atMostOneFailedSendSurvivesAndTheNewestWins() = runTest {
        val (pending, make) = pendingFixture()
        fun record(n: Int) = make(n, "2026-01-01T00:00:0${n}Z")
        pending.save(scope, record(1))
        pending.mark(scope, "otid-1", CanonicalPendingLocalStore.Delivery.Failed)
        // Sending again is how a user abandons the last failure: it supersedes rather than stacks.
        pending.save(scope, record(2))
        assertEquals(listOf("otid-2"), pending.load(scope).map { it.otid })
        pending.mark(scope, "otid-2", CanonicalPendingLocalStore.Delivery.Failed)

        // A send still in flight is never superseded: only its own failure can retire it.
        pending.save(scope, record(3))
        assertEquals(listOf("otid-3"), pending.load(scope).map { it.otid })
        pending.save(scope, record(4))
        assertEquals(listOf("otid-3", "otid-4"), pending.load(scope).map { it.otid })
        pending.mark(scope, "otid-3", CanonicalPendingLocalStore.Delivery.Failed)
        pending.mark(scope, "otid-4", CanonicalPendingLocalStore.Delivery.Failed)
        assertEquals(listOf("otid-4"), pending.load(scope).map { it.otid })
    }

    @Test fun onlyAFailedSendCanBeDiscardedAndTheRestSurvive() = runTest {
        val store = Store()
        val pending = CanonicalPendingLocalStore(store)
        val sending = CanonicalPendingLocalStore.Record("otid-sending", "in flight", emptyList(), "2026-01-01T00:00:00Z")
        val failed = CanonicalPendingLocalStore.Record("otid-failed", "gave up", emptyList(), "2026-01-01T00:00:01Z")
        pending.save(scope, sending)
        pending.save(scope, failed)
        pending.mark(scope, failed.otid, CanonicalPendingLocalStore.Delivery.Failed)
        // An absent echo is not confirmation, so a send still in flight must not be droppable.
        assertFalse(pending.discardFailed(scope, sending.otid))
        assertFalse(pending.discardFailed(scope, "otid-unknown"))
        assertEquals(2, pending.load(scope).size)
        assertTrue(pending.discardFailed(scope, failed.otid))
        assertEquals(listOf(sending.otid), pending.load(scope).map { it.otid })
        // Discarding is idempotent: the bubble cannot come back on a second tap.
        assertFalse(pending.discardFailed(scope, failed.otid))
    }

    private suspend fun reconcile(
        engine: CanonicalTimelineEngine,
        selection: TimelineEngineSelection,
        vararg records: TimelineRemoteRecord,
    ): TimelineEnginePageOutcome {
        val request = engine.beginReconcile(selection)
        return engine.reconcilePage(request, TimelineRemotePageResult.Page(
            request.remote.requestId, selection.generation, records.toList(), null, false, 0,
        ))
    }

    private fun record(message: com.letta.mobile.data.model.LettaMessage) =
        TimelineRemoteRecord(TimelineMessageId(message.id), message, 0)

    private class PageTransport(
        private val barrier: suspend () -> Unit,
        // The sync page is the only durable writer, so tests supply the rows it is expected to commit.
        private val records: () -> List<TimelineRemoteRecord> = { emptyList() },
    ) : TimelineTransport {
        override suspend fun listConversationMessagePage(request: TimelineRemotePageRequest, progress: TimelinePageProgress?): TimelineRemotePageResult {
            barrier()
            return TimelineRemotePageResult.Page(request.requestId, request.selectionGeneration, records(), null, false, 0)
        }
        override suspend fun sendConversationMessage(conversationId: String, request: com.letta.mobile.data.model.MessageCreateRequest): kotlinx.coroutines.flow.Flow<com.letta.mobile.data.model.LettaMessage> = error("unexpected send")
        override suspend fun streamConversation(conversationId: String): kotlinx.coroutines.flow.Flow<TimelineStreamFrame> = error("unexpected stream")
        override suspend fun listConversationMessages(conversationId: String, limit: Int?, after: String?, order: String?): List<com.letta.mobile.data.model.LettaMessage> = error("legacy hydration")
        override suspend fun listAgentMessages(agentId: String, limit: Int?, order: String?, conversationId: String?): List<com.letta.mobile.data.model.LettaMessage> = error("legacy hydration")
    }

    private class Store : TimelineBoundedStore {
        var bodyReads = 0
        var puts = 0
        var current = TimelineDurableCheckpoint(0, TimelineContinuation.Initial, true)
        val rows = mutableMapOf<TimelinePageKey, TimelineStoredRecord>()
        val evidence = mutableMapOf<String, ByteArray>()
        private val tools = mutableMapOf<TimelineScope, TestToolIndexState>()
        override suspend fun <T> read(scope: TimelineScope, block: suspend TimelineStoreReader.() -> T): T =
            block(Tx(tools[scope]?.snapshot() ?: TestToolIndexState()))
        override suspend fun <T> transaction(scope: TimelineScope, block: suspend TimelineStoreTransaction.() -> T): T {
            val before = current
            val oldRows = rows.toMap()
            val oldEvidence = evidence.toMap()
            val toolCopy = tools[scope]?.snapshot() ?: TestToolIndexState()
            return try { block(Tx(toolCopy)).also { tools[scope] = toolCopy } } catch (failure: Throwable) {
                current = before
                rows.clear(); rows.putAll(oldRows)
                evidence.clear(); evidence.putAll(oldEvidence)
                throw failure
            }
        }
        private inner class Tx(private val tools: TestToolIndexState) : TimelineStoreTransaction {
            override suspend fun toolCall(callId: String) = tools.entries[callId]
            override suspend fun unresolvedTools(afterCallId: String?, maxRows: Int) = tools.unresolved(afterCallId, maxRows)
            override suspend fun toolSweepGeneration() = tools.generation
            override suspend fun putToolCall(entry: TimelineToolIndexEntry) = tools.put(entry)
            override suspend fun setToolSweepGeneration(next: Long) = tools.advance(next)
            override suspend fun checkpoint() = current
            override suspend fun locate(identity: TimelineMessageId) = rows.keys.singleOrNull { it.identity == identity }
            override suspend fun metadata(position: TimelineReadPosition, maxRows: Int): TimelineMetadataPage {
                val selected = rows.values.sortedBy { it.key }.filter { position !is TimelineReadPosition.Around || it.key == position.key }.take(maxRows)
                return TimelineMetadataPage(selected.map { TimelineLedgerMetadata(it.key, TimelineBodyPointer(it.key.identity.value, it.body.size.toLong()), it.contentType, current.revision) }, null, null, current.revision)
            }
            override suspend fun body(pointer: TimelineBodyPointer, offset: Long, maxBytes: Int): ByteArray {
                require(maxBytes in 0..65_536)
                bodyReads++
                val bytes = rows.values.single { it.key.identity.value == pointer.value }.body
                return bytes.copyOfRange(offset.toInt(), minOf(bytes.size, offset.toInt() + maxBytes))
            }
            override suspend fun evidence(key: String, maxBytes: Int): ByteArray? = evidence[key]?.also { check(it.size <= maxBytes) }?.copyOf()
            override suspend fun put(record: TimelineStoredRecord) {
                puts++
                rows[record.key] = record.copy(body = record.body.copyOf())
            }
            override suspend fun putEvidence(key: String, value: ByteArray) { evidence[key] = value.copyOf() }
            override suspend fun deleteEvidence(key: String) { evidence.remove(key) }
            override suspend fun cursor(continuation: TimelineContinuation?, hasMore: Boolean) { current = current.copy(continuation = continuation, hasMore = hasMore) }
            override suspend fun nextRevision(): Long { current = current.copy(revision = current.revision + 1); return current.revision }
            override suspend fun delete(identity: TimelineMessageId, reason: TimelineDurableDeleteReason) { rows.keys.removeAll { it.identity == identity } }
        }
    }

    companion object {
        private val scope = TimelineScope("backend", "conversation")
        private fun message(content: String) = AssistantMessage(id = "id", contentRaw = kotlinx.serialization.json.JsonPrimitive(content), date = "2026-01-01T00:00:00Z")
        private fun reply(id: String, content: String) = AssistantMessage(id = id, contentRaw = kotlinx.serialization.json.JsonPrimitive(content), date = "2026-01-01T00:00:00Z")
    }
}

/**
 * Serves exactly one repair page and refuses every other route. The user echo and the streamed
 * reply are durable only through the sync path now, so a test that wants either must go through a
 * repair rather than a live turn.
 */
private class RepairOnlyTransport(
    private val repairStarted: () -> kotlinx.coroutines.CompletableDeferred<Unit>,
    private val releaseRepair: () -> kotlinx.coroutines.CompletableDeferred<Unit>,
    private val repairSequence: () -> Int?,
    private val reply: com.letta.mobile.data.model.LettaMessage,
) : TimelineTransport {
    override suspend fun listConversationMessagePage(
        request: TimelineRemotePageRequest,
        progress: TimelinePageProgress?,
    ): TimelineRemotePageResult {
        repairStarted().complete(Unit)
        releaseRepair().await()
        assertEquals(TimelineContinuation.Initial, request.continuation)
        return TimelineRemotePageResult.Page(
            request.requestId, request.selectionGeneration,
            listOf(
                TimelineRemoteRecord(
                    TimelineMessageId("echo"),
                    com.letta.mobile.data.model.UserMessage(
                        id = "echo", contentRaw = kotlinx.serialization.json.JsonPrimitive("question"),
                        date = "2026-01-01T00:00:00Z", otid = "pending-user", seqId = repairSequence(),
                    ),
                    0,
                ),
                TimelineRemoteRecord(TimelineMessageId("id"), reply, 0),
            ),
            null, false, 0,
        )
    }

    override suspend fun sendConversationMessage(
        conversationId: String, request: com.letta.mobile.data.model.MessageCreateRequest,
    ): kotlinx.coroutines.flow.Flow<com.letta.mobile.data.model.LettaMessage> = error("unexpected send")

    override suspend fun streamConversation(conversationId: String): kotlinx.coroutines.flow.Flow<TimelineStreamFrame> =
        error("ownership must not open transport")

    override suspend fun listConversationMessages(
        conversationId: String, limit: Int?, after: String?, order: String?,
    ): List<com.letta.mobile.data.model.LettaMessage> = error("unexpected legacy hydration")

    override suspend fun listAgentMessages(
        agentId: String, limit: Int?, order: String?, conversationId: String?,
    ): List<com.letta.mobile.data.model.LettaMessage> = error("unexpected legacy hydration")
}

/** Records the maintenance callbacks in order so a test can assert the sequence it drove. */
private class RecordingMaintenance(private val calls: MutableList<String>) : CanonicalTimelineMaintenance {
    override suspend fun turnStarted(owner: CanonicalTimelineCoordinator.Owner, runId: String?, turnId: String?) {
        calls += "start:$runId:$turnId"
    }

    override suspend fun turnEnded(owner: CanonicalTimelineCoordinator.Owner, clean: Boolean) {
        calls += "end:$clean"
    }

    override suspend fun cleanup(owner: CanonicalTimelineCoordinator.Owner, request: TimelineTurnCleanup): Int {
        calls += "cleanup:${request.reason}"
        return 2
    }

    override suspend fun repairCursor(
        owner: CanonicalTimelineCoordinator.Owner, fallbackSeq: Long?, expectedWatermark: Long?,
    ) {
        calls += "repair:$fallbackSeq:$expectedWatermark"
    }
}
