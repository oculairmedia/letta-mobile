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
    @Test fun capturedTransportScopeRejectsRetiredBackend() = runTest {
        var current = "first"
        val resolver = CanonicalTransportScopeResolver(current) { it == current }
        assertEquals(TimelineScope("first", "conversation", "agent"), resolver.resolve("agent", "conversation"))
        current = "second"
        kotlin.test.assertFailsWith<IllegalStateException> { resolver.resolve("agent", "conversation") }
        val replacement = CanonicalTransportScopeResolver(current) { it == current }
        assertEquals(TimelineScope("second", "conversation", "agent"), replacement.resolve("agent", "conversation"))
    }

    @Test fun liveReductionCommitsOnceAndRejectsStaleFence() = runTest {
        val store = Store()
        val engine = CanonicalTimelineEngine(store, TimelineExactCanonicalWriter(scope, 100_000), enabled = true)
        val selection = assertIs<TimelineEngineOpen.Opened>(engine.open(scope)).selection
        val stale = engine.beginLive(selection)
        val fence = engine.beginLive(selection)
        assertFalse(engine.ingest(stale, TimelineStreamFrame.Message(message("old"))))
        assertTrue(engine.ingest(fence, TimelineStreamFrame.Message(message("hello"))))
        assertEquals(0L, engine.publication.value.durableRevision)
        assertTrue(engine.ingest(fence, TimelineStreamFrame.Done))
        assertEquals(1, store.rows.size)
        assertEquals(1L, engine.publication.value.durableRevision)
        assertFalse(engine.acknowledgeSettlement(fence, emptyMap()))
        assertTrue(engine.acknowledgeSettlement(fence, mapOf(TimelineMessageId("id") to 1L)))
        assertEquals(null, engine.live.value)
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
        assertTrue(engine.ingest(fence, TimelineStreamFrame.Message(message("typed"))))
        assertTrue(engine.ingest(fence, TimelineStreamFrame.Done))
        assertEquals(1, store.rows.size)
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
        val raw = store.rows.values.single().body.copyOf()
        assertEquals(1, engine.suppressAbandonedTail(selection, "run", null, "cancelled", emptySet()))
        assertEquals(1, store.rows.size)
        kotlin.test.assertContentEquals(raw, store.rows.values.single().body)
        val event = kotlin.test.assertNotNull(fragment.toTimelineEvent(0.0))
        assertTrue(engine.isSuppressed(selection, TimelineMessageId(fragment.id), 2L, event))
        kotlin.test.assertFailsWith<IllegalStateException> {
            engine.isSuppressed(selection, TimelineMessageId(fragment.id), 1L, event)
        }
        store.transaction(scope) {
            assertFalse(writer.merge(this, TimelineRemoteRecord(TimelineMessageId(fragment.id), fragment, 0)))
        }
    }

    @Test fun nextTurnHandsOffCommittedOffTailBodyAndRejectsOldAcknowledgment() = runTest {
        val store = Store()
        val engine = CanonicalTimelineEngine(store, TimelineExactCanonicalWriter(scope, 100_000), enabled = true)
        val selection = assertIs<TimelineEngineOpen.Opened>(engine.open(scope)).selection
        val first = engine.beginLive(selection)
        assertTrue(engine.ingest(first, TimelineStreamFrame.Message(message("first"))))
        assertTrue(engine.ingest(first, TimelineStreamFrame.Done))
        val firstBody = store.rows.values.single().body.copyOf()
        val second = engine.beginLive(selection)
        assertFalse(engine.acknowledgeSettlement(first, mapOf(TimelineMessageId("id") to 1L)))
        assertTrue(engine.ingest(second, TimelineStreamFrame.Message(message("second").copy(id = "second", otid = "second"))))
        kotlin.test.assertContentEquals(firstBody, store.rows.values.single().body)
        assertTrue(engine.ingest(second, TimelineStreamFrame.Done))
        assertEquals(2, store.rows.size)
        assertFalse(engine.acknowledgeSettlement(first, mapOf(TimelineMessageId("id") to 2L)))
        assertEquals(second, engine.live.value?.fence)
        assertTrue(engine.acknowledgeSettlement(second, mapOf(TimelineMessageId("second") to 2L)))
    }

    @Test fun backgroundCoordinatorCompletionRetainsNonEmptyHistoryAcrossRetirement() = runTest {
        val store = Store()
        var repairStarted = kotlinx.coroutines.CompletableDeferred<Unit>()
        var releaseRepair = kotlinx.coroutines.CompletableDeferred<Unit>()
        var repairSequence: Int? = null
        val transport = object : TimelineTransport {
            override suspend fun listConversationMessagePage(request: TimelineRemotePageRequest, progress: TimelinePageProgress?): TimelineRemotePageResult {
                repairStarted.complete(Unit)
                releaseRepair.await()
                assertEquals(TimelineContinuation.Initial, request.continuation)
                return TimelineRemotePageResult.Page(request.requestId, request.selectionGeneration,
                    listOf(TimelineRemoteRecord(TimelineMessageId("echo"), com.letta.mobile.data.model.UserMessage(
                        id = "echo", contentRaw = kotlinx.serialization.json.JsonPrimitive("question"),
                        date = "2026-01-01T00:00:00Z", otid = "pending-user", seqId = repairSequence,
                    ), 0)), null, false, 0)
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
        val coordinator = CanonicalTimelineCoordinator(store, transport)
        val calls = mutableListOf<String>()
        val maintenance = object : CanonicalTimelineMaintenance {
            override suspend fun turnStarted(owner: CanonicalTimelineCoordinator.Owner, runId: String?, turnId: String?) { calls += "start:$runId:$turnId" }
            override suspend fun turnEnded(owner: CanonicalTimelineCoordinator.Owner, clean: Boolean) { calls += "end:$clean" }
            override suspend fun cleanup(owner: CanonicalTimelineCoordinator.Owner, runId: String?, turnId: String?, reason: String, candidateRunIds: Set<String>): Int { calls += "cleanup:$reason"; return 2 }
            override suspend fun repairCursor(
                owner: CanonicalTimelineCoordinator.Owner,
                fallbackSeq: Long?,
                expectedWatermark: Long?,
            ) { calls += "repair:$fallbackSeq:$expectedWatermark" }
        }
        val external = CanonicalExternalTransportWriter(coordinator, { agentId, conversationId ->
            assertEquals(scope.agentId, agentId)
            assertEquals(scope.conversationId, conversationId)
            scope
        }, maintenance, now = { "2026-01-01T00:00:00Z" })
        val local = CanonicalPendingLocalStore.Record("pending-user", "question", emptyList(), "2026-01-01T00:00:00Z")
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
        assertEquals(1, store.rows.size)
        val durableBody = store.rows.values.single().body.copyOf()
        assertEquals(1L, store.current.revision)
        assertFalse(coordinator.ingest(owner, fence, TimelineStreamFrame.Done))
        assertTrue(coordinator.retire(owner))
        val reopened = coordinator.acquire(scope)
        val page = reopened.session.engine.load(reopened.selection, TimelineReadPosition.Tail, 1)
        kotlin.test.assertContentEquals(durableBody, page.bodies.single())
        assertEquals(1L, reopened.session.publication.value.durableRevision)
        assertEquals(1, store.rows.size)
        val repair = async { coordinator.reconcileRecentDetailed(reopened) }
        repairStarted.await()
        assertFalse(coordinator.retire(reopened))
        releaseRepair.complete(Unit)
        assertEquals(TimelineEngineReconcileResult(TimelineEnginePageOutcome.Applied, appended = 1), repair.await())
        assertEquals(TimelineEngineReconcileResult(TimelineEnginePageOutcome.Applied, appended = 0), coordinator.reconcileRecentDetailed(reopened))
        assertEquals(emptyList(), reopened.session.pending.value)
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
        assertEquals(0, indexed.cleanup(reopened, "missing", null, "test", emptySet()))
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
        store.transaction(scope) { assertFalse(writer.merge(this, record)) }
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

    @Test fun aliasedSettlementAcknowledgesCanonicalIdentity() = runTest {
        val store = Store()
        val writer = TimelineExactCanonicalWriter(scope, 100_000)
        val canonical = message("hello").toTimelineEvent(0.0)!!.copy(serverId = "canonical")
        store.transaction(scope) { writer.mergeEvent(this, canonical); nextRevision() }
        val engine = CanonicalTimelineEngine(store, writer, enabled = true)
        val selection = assertIs<TimelineEngineOpen.Opened>(engine.open(scope)).selection
        val fence = engine.beginLive(selection)
        engine.publishLive(fence, TimelineLiveBlock(listOf(TimelineRemoteRecord(TimelineMessageId("id"), message("hello"), 0)), true))
        val revision = engine.publication.value.durableRevision
        assertFalse(engine.acknowledgeSettlement(fence, mapOf(TimelineMessageId("id") to revision)))
        assertTrue(engine.acknowledgeSettlement(fence, mapOf(TimelineMessageId("canonical") to revision)))
        assertEquals(1, store.rows.size)
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
        assertEquals(1L, store.current.revision)
        assertEquals(1L, engine.publication.value.durableRevision)
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
        val coordinator = CanonicalTimelineCoordinator(store, PageTransport {
            entered.complete(Unit)
            release.await()
        })
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

    @Test fun attachedCommittedOverlayHandsOffBeforeRepairAndNextTurn() = runTest {
        val store = Store()
        val coordinator = CanonicalTimelineCoordinator(store, PageTransport {})
        val owner = coordinator.acquire(scope)
        val presentation = kotlin.test.assertNotNull(coordinator.attach(owner))
        val first = coordinator.beginLive(owner)
        coordinator.ingest(owner, first, TimelineStreamFrame.Message(message("durable off-tail")))
        coordinator.ingest(owner, first, TimelineStreamFrame.Done)
        val bytes = store.rows.values.single().body.copyOf()
        assertEquals(first, owner.session.live.value?.fence)
        assertEquals(TimelineEnginePageOutcome.Applied, coordinator.reconcileRecent(owner))
        assertEquals(null, owner.session.live.value)
        assertEquals(1L, owner.session.publication.value.durableRevision)
        kotlin.test.assertContentEquals(bytes, store.rows.values.single().body)
        val next = coordinator.beginLive(owner)
        coordinator.ingest(owner, next, TimelineStreamFrame.Message(message("next").copy(id = "next", otid = "next")))
        assertFalse(coordinator.acknowledgeSettlement(owner, first, mapOf(TimelineMessageId("id") to 1L)))
        assertEquals(next, owner.session.live.value?.fence)
        coordinator.ingest(owner, next, TimelineStreamFrame.Done)
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

    private class PageTransport(private val barrier: suspend () -> Unit) : TimelineTransport {
        override suspend fun listConversationMessagePage(request: TimelineRemotePageRequest, progress: TimelinePageProgress?): TimelineRemotePageResult {
            barrier()
            return TimelineRemotePageResult.Page(request.requestId, request.selectionGeneration, emptyList(), null, false, 0)
        }
        override suspend fun sendConversationMessage(conversationId: String, request: com.letta.mobile.data.model.MessageCreateRequest): kotlinx.coroutines.flow.Flow<com.letta.mobile.data.model.LettaMessage> = error("unexpected send")
        override suspend fun streamConversation(conversationId: String): kotlinx.coroutines.flow.Flow<TimelineStreamFrame> = error("unexpected stream")
        override suspend fun listConversationMessages(conversationId: String, limit: Int?, after: String?, order: String?): List<com.letta.mobile.data.model.LettaMessage> = error("legacy hydration")
        override suspend fun listAgentMessages(agentId: String, limit: Int?, order: String?, conversationId: String?): List<com.letta.mobile.data.model.LettaMessage> = error("legacy hydration")
    }

    private class Store : TimelineBoundedStore {
        var bodyReads = 0
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
            override suspend fun put(record: TimelineStoredRecord) { rows[record.key] = record.copy(body = record.body.copyOf()) }
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
    }
}
