package com.letta.mobile.data.timeline

import com.letta.mobile.data.timeline.snapshot.StoredTimelineEvent
import com.letta.mobile.data.timeline.snapshot.StoredToolCall
import com.letta.mobile.data.timeline.snapshot.TimelineScope
import com.letta.mobile.data.timeline.snapshot.TimelineSnapshotCodec
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class TimelinePageProjectionTest {
    private val scope = TimelineScope("backend", "canonical-conversation")

    @Test fun projectionContextUsesCanonicalTimelineScope() = runTest {
        val store = fixture()
        val adapter = DefaultTimelineSettledProjectionAdapter
        var decoded = 0
        val page = prepare(store, adapter = adapter.copy(
            decode = { decoded++; adapter.decode(it) },
            project = { record, event, own ->
                assertEquals(3, decoded, "resident and both edges decode before row construction")
                assertEquals("own-agent", own)
                adapter.project(record, event, own)
            },
        ))
        assertEquals(TimelineProjectionContext(scope, "own-agent"), page.projectionInput.context)
        val record = page.records.single()
        assertEquals(record.presentation("own-agent"), record.preparedPresentation)
        assertEquals(2, store.reads, "open and prepare each use one snapshot")
        assertEquals(0, store.puts)
    }

    @Test fun hydratedFourToolRunProjectsOneAggregateWithoutConstituentRows() = runTest {
        val store = InMemoryTimelineStore()
        repeat(4) { index -> seedToolCall(store, index + 1) }
        val engine = engine(store, TimelinePageBudget(8, 64 * 1024))
        val selection = assertIs<TimelineEngineOpen.Opened>(engine.open(scope)).selection

        val page = engine.preparePage(
            selection,
            TimelineReadPosition.Tail,
            8,
            "own-agent",
            DefaultTimelineSettledProjectionAdapter,
        )

        val rendered = page.records.mapNotNull {
            (it.preparedPresentation as? TimelineSettledPresentation.Render)?.item
        }
        val block = assertIs<com.letta.mobile.data.chat.projection.ChatRenderItem.RunBlock>(rendered.single())
        assertEquals("run-four-tools", block.runId)
        assertEquals(4, block.messages.sumOf { it.first.toolCalls.orEmpty().size })
        assertEquals(3, page.records.count { it.preparedPresentation is TimelineSettledPresentation.Drop })
    }

    @Test fun liveAndHydratedToolRunsUseTheSameAggregateKeyAndContent() = runTest {
        val store = InMemoryTimelineStore()
        repeat(4) { index -> seedToolCall(store, index + 1) }
        val engine = engine(store, TimelinePageBudget(8, 64 * 1024))
        val selection = assertIs<TimelineEngineOpen.Opened>(engine.open(scope)).selection
        val page = engine.preparePage(
            selection,
            TimelineReadPosition.Tail,
            8,
            "own-agent",
            DefaultTimelineSettledProjectionAdapter,
        )
        val hydrated = page.records.mapNotNull {
            (it.preparedPresentation as? TimelineSettledPresentation.Render)?.item
        }.single()
        val liveMessages = page.projectionInput.records.mapNotNull { input ->
            input.event?.let { com.letta.mobile.data.chat.projection.timelineEventToUiMessage(it, "own-agent") }
        }
        val live = com.letta.mobile.data.chat.projection.buildChatRenderModel(
            liveMessages,
            com.letta.mobile.data.chat.projection.ChatDisplayMode.Interactive,
            "own-agent",
        ).renderItems.single()

        assertEquals(live.key, hydrated.key)
        assertEquals(live, hydrated)
    }

    @Test fun syntheticSkillEnvelopeDoesNotSplitNullRunColdProjection() = runTest {
        val store = InMemoryTimelineStore()
        repeat(7) { index ->
            val id = index + 1
            val callId = "call-$id"
            val event = StoredTimelineEvent(
                position = id.toDouble(), otid = "otid-$id", serverId = "id-$id",
                messageType = if (index == 1) "user_message" else "tool_call_message",
                dateIso = "2026-09-22T03:49:00Z",
                content = if (index == 1) "<skill_content name=\"review-gate\">${"instructions ".repeat(20)}</skill_content>" else "",
                toolCalls = if (index == 1) emptyList() else listOf(StoredToolCall(callId,
                    when (index) { 0 -> "Skill"; 2 -> "Agent"; 5 -> "TaskOutput"; else -> "exec_command" }, "{}")),
                toolReturnContentByCallId = if (index == 1) emptyMap() else mapOf(callId to "completed"),
            )
            store.rows[key(id)] = TimelineStoredRecord(key(id), TIMELINE_EVENT_CONTENT_TYPE,
                TimelineSnapshotCodec.json.encodeToString(StoredTimelineEvent.serializer(), event).encodeToByteArray())
        }
        val engine = engine(store, TimelinePageBudget(32, 64 * 1024))
        val selection = assertIs<TimelineEngineOpen.Opened>(engine.open(scope)).selection
        val page = engine.preparePage(selection, TimelineReadPosition.Tail, 32, "own-agent",
            DefaultTimelineSettledProjectionAdapter)
        val live = com.letta.mobile.data.chat.projection.buildChatRenderModel(
            page.projectionInput.records.mapNotNull { input -> input.event?.let {
                com.letta.mobile.data.chat.projection.timelineEventToUiMessage(it, "own-agent")
            } }, com.letta.mobile.data.chat.projection.ChatDisplayMode.Interactive,
        ).renderItems.single()
        val hydrated = page.records.mapNotNull {
            (it.preparedPresentation as? TimelineSettledPresentation.Render)?.item
        }
        assertEquals(listOf(live), hydrated)
        val block = assertIs<com.letta.mobile.data.chat.projection.ChatRenderItem.RunBlock>(hydrated.single())
        assertEquals(6, block.messages.sumOf { it.first.toolCalls.orEmpty().size })

        // A real user turn or an unrelated return must still break physical ownership.
        for (type in listOf("user_message", "tool_return_message")) {
            val barrier = StoredTimelineEvent(
                position = 2.0, otid = "barrier", serverId = "id-2", messageType = type,
                dateIso = "2026-09-22T03:49:00Z", content = "next user turn",
            )
            store.rows[key(2)] = TimelineStoredRecord(key(2), TIMELINE_EVENT_CONTENT_TYPE,
                TimelineSnapshotCodec.json.encodeToString(StoredTimelineEvent.serializer(), barrier).encodeToByteArray())
            val reopened = engine(store, TimelinePageBudget(32, 64 * 1024))
            val reopenedSelection = assertIs<TimelineEngineOpen.Opened>(reopened.open(scope)).selection
            val isolated = reopened.preparePage(reopenedSelection, TimelineReadPosition.Tail, 32, "own-agent",
                DefaultTimelineSettledProjectionAdapter)
            assertTrue(isolated.records.mapNotNull {
                (it.preparedPresentation as? TimelineSettledPresentation.Render)?.item
            }.none { it is com.letta.mobile.data.chat.projection.ChatRenderItem.RunBlock && it.messages.size == 6 })
        }
    }

    @Test fun toolRunCrossingPageBoundaryRemainsLosslessInsteadOfPartiallyAggregating() = runTest {
        val store = InMemoryTimelineStore()
        repeat(4) { index -> seedToolCall(store, index + 1) }
        val engine = engine(store, TimelinePageBudget(2, 64 * 1024))
        val selection = assertIs<TimelineEngineOpen.Opened>(engine.open(scope)).selection

        val page = engine.preparePage(
            selection,
            TimelineReadPosition.Tail,
            2,
            "own-agent",
            DefaultTimelineSettledProjectionAdapter,
        )

        assertEquals(TimelineRunBoundary.Continues("run-four-tools"), page.projectionInput.envelope.older)
        assertEquals(2, page.records.count { it.preparedPresentation is TimelineSettledPresentation.Render })
        assertEquals(0, page.records.count { it.preparedPresentation is TimelineSettledPresentation.Drop })
    }

    @Test fun excludedInteriorRecordPreventsRunAggregationAcrossTheGap() = runTest {
        val store = InMemoryTimelineStore()
        repeat(3) { index -> seedToolCall(store, index + 1) }
        val engine = engine(store, TimelinePageBudget(3, 64 * 1024))
        val selection = assertIs<TimelineEngineOpen.Opened>(engine.open(scope)).selection
        val droppingAdapter = TimelineSettledProjectionAdapter(
            decode = DefaultTimelineSettledProjectionAdapter.decode,
            project = { record, event, ownAgentId ->
                if (event.serverId == "id-2") null
                else DefaultTimelineSettledProjectionAdapter.project(record, event, ownAgentId)
            },
        )

        val page = engine.preparePage(
            selection,
            TimelineReadPosition.Tail,
            3,
            "own-agent",
            droppingAdapter,
        )

        assertEquals(2, page.records.count { it.preparedPresentation is TimelineSettledPresentation.Render })
        assertEquals(1, page.records.count { it.preparedPresentation is TimelineSettledPresentation.Drop })
        assertTrue(page.records.mapNotNull {
            (it.preparedPresentation as? TimelineSettledPresentation.Render)?.item
        }.none { item ->
            item is com.letta.mobile.data.chat.projection.ChatRenderItem.RunBlock && item.messages.size == 2
        })
    }

    @Test fun pageEnvelopeMarksRunContinuationAcrossOlderBoundary() = runTest {
        assertEquals(TimelineRunBoundary.Continues("run-a"), prepare(fixture()).projectionInput.envelope.older)
    }

    @Test fun pageEnvelopeMarksRunContinuationAcrossNewerBoundary() = runTest {
        assertEquals(TimelineRunBoundary.Continues("run-a"), prepare(fixture()).projectionInput.envelope.newer)
    }

    @Test fun pageEnvelopeMarksKnownDifferentRunAsEnds() = runTest {
        for (run in listOf("run-b", null, " ")) {
            val store = fixture()
            seed(store, 1, run)
            seed(store, 3, run)
            assertEquals(TimelineRunEnvelope(TimelineRunBoundary.Ends, TimelineRunBoundary.Ends),
                prepare(store).projectionInput.envelope)
        }
        val single = InMemoryTimelineStore().also { seed(it, 2) }
        assertEquals(TimelineRunEnvelope(TimelineRunBoundary.Ends, TimelineRunBoundary.Ends),
            prepare(single).projectionInput.envelope)
    }

    @Test fun deferredBoundaryIsUnknownAndDoesNotMerge() = runTest {
        val store = fixture()
        seed(store, 1, content = "x".repeat(20_000))
        seed(store, 3, content = "x".repeat(20_000))
        assertUnknown(prepare(store))
        assertEquals(1, store.bodyReads)
        val exhausted = fixture()
        val residentBytes = exhausted.rows.getValue(key(2)).body.size.toLong()
        assertUnknown(prepare(exhausted, budget = TimelinePageBudget(1, residentBytes)))
        assertEquals(1, exhausted.bodyReads)
    }

    @Test fun opaqueBoundaryRecordIsUnknownAndDoesNotInventRunIdentity() = runTest {
        val store = fixture()
        for (id in listOf(1, 3)) store.rows[key(id)] = TimelineStoredRecord(key(id), "opaque", byteArrayOf(1))
        assertUnknown(prepare(store))
        assertEquals(1, store.bodyReads)
    }

    @Test fun malformedBoundaryRecordFailsInsteadOfBecomingUnknown() = runTest {
        for (id in listOf(1, 3)) {
            val store = fixture()
            store.rows[key(id)] = TimelineStoredRecord(key(id), TIMELINE_EVENT_CONTENT_TYPE, "{".encodeToByteArray())
            assertFailsWith<kotlinx.serialization.SerializationException> { prepare(store) }
        }
    }

    @Test fun boundedEnvelopeReadsDoNotGrowWithHistorySize() = runTest {
        for (size in listOf(3, 1_000)) {
            val store = InMemoryTimelineStore()
            repeat(size) { seed(store, it + 1) }
            val queries = mutableListOf<Int>()
            var bytes = 0
            val measured = object : TimelineBoundedStore by store {
                override suspend fun <T> read(scope: TimelineScope, block: suspend TimelineStoreReader.() -> T): T =
                    store.read(scope) {
                        val reader = this
                        block(object : TimelineStoreReader by reader {
                            override suspend fun metadata(position: TimelineReadPosition, maxRows: Int): TimelineMetadataPage {
                                queries += maxRows
                                return reader.metadata(position, maxRows)
                            }
                            override suspend fun body(pointer: TimelineBodyPointer, offset: Long, maxBytes: Int): ByteArray {
                                bytes += maxBytes
                                return reader.body(pointer, offset, maxBytes)
                            }
                        })
                    }
            }
            val page = prepare(measured)
            assertEquals(TimelineRunBoundary.Continues("run-a"), page.projectionInput.envelope.older)
            assertEquals(listOf(1, 1, 1), queries)
            assertEquals(3, store.bodyReads)
            assertTrue(bytes <= 3 * 16 * 1024)
        }
    }

    @Test fun unavailableResidentEvidenceIsUnknownAndReleaseDoesNotWrite() = runTest {
        val store = fixture()
        seed(store, 2, content = "x".repeat(20_000))
        val engine = engine(store)
        val selection = assertIs<TimelineEngineOpen.Opened>(engine.open(scope)).selection
        val page = engine.preparePage(selection, TimelineReadPosition.Around(key(2)), 1, null,
            DefaultTimelineSettledProjectionAdapter)
        assertUnknown(page)
        assertEquals(TimelineSettledPresentation.Defer, page.records.single().preparedPresentation)
        val before = store.rows.toMap()
        engine.release(selection)
        assertEquals(before, store.rows)
        assertEquals(0, store.puts)
    }

    private fun assertUnknown(page: TimelinePreparedPage) {
        assertEquals(TimelineRunEnvelope(TimelineRunBoundary.Unknown, TimelineRunBoundary.Unknown),
            page.projectionInput.envelope)
    }

    private fun fixture() = InMemoryTimelineStore().also { store -> (1..3).forEach { seed(store, it) } }
    private fun key(id: Int) = TimelinePageKey(id.toLong(), TimelineMessageId("id-$id"))
    private fun seed(store: InMemoryTimelineStore, id: Int, run: String? = "run-a", content: String = "hello") {
        val event = StoredTimelineEvent(id.toDouble(), "", content, "id-$id", "assistant_message",
            "2026-01-01T00:00:00Z", runId = run)
        store.rows[key(id)] = TimelineStoredRecord(key(id), TIMELINE_EVENT_CONTENT_TYPE,
            TimelineSnapshotCodec.json.encodeToString(StoredTimelineEvent.serializer(), event).encodeToByteArray())
    }
    private fun seedToolCall(store: InMemoryTimelineStore, id: Int) {
        val event = StoredTimelineEvent(
            position = id.toDouble(),
            otid = "otid-$id",
            serverId = "id-$id",
            messageType = "tool_call_message",
            dateIso = "2026-01-01T00:00:0${id}Z",
            runId = "run-four-tools",
            toolCalls = listOf(StoredToolCall("call-$id", "Bash", "command-$id")),
            toolReturnContentByCallId = mapOf("call-$id" to "output-$id"),
        )
        val key = key(id)
        store.rows[key] = TimelineStoredRecord(
            key,
            TIMELINE_EVENT_CONTENT_TYPE,
            TimelineSnapshotCodec.json.encodeToString(StoredTimelineEvent.serializer(), event).encodeToByteArray(),
        )
    }
    private fun engine(store: TimelineBoundedStore, budget: TimelinePageBudget = TimelinePageBudget(1, 64 * 1024)) =
        CanonicalTimelineEngine(store, TimelineExactCanonicalWriter(scope, 64 * 1024), budget, enabled = true)

    private suspend fun prepare(
        store: TimelineBoundedStore,
        budget: TimelinePageBudget = TimelinePageBudget(1, 64 * 1024),
        adapter: TimelineSettledProjectionAdapter = DefaultTimelineSettledProjectionAdapter,
    ): TimelinePreparedPage {
        val engine = engine(store, budget)
        val selection = assertIs<TimelineEngineOpen.Opened>(engine.open(scope)).selection
        return engine.preparePage(selection, TimelineReadPosition.Around(key(2)), 1, "own-agent", adapter)
    }
}
