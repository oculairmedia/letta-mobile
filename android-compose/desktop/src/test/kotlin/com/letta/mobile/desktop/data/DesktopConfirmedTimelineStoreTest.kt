package com.letta.mobile.desktop.data

import com.letta.mobile.data.timeline.snapshot.StoredTimelineEnvelope
import com.letta.mobile.data.timeline.snapshot.StoredTimelineEvent
import com.letta.mobile.data.timeline.snapshot.NormalizedTimelineCommitFailure
import com.letta.mobile.data.timeline.snapshot.NormalizedTimelineCommitPlan
import com.letta.mobile.data.timeline.snapshot.NormalizedTimelineCommitPlanner
import com.letta.mobile.data.timeline.snapshot.NormalizedTimelineWriteResult
import com.letta.mobile.data.timeline.snapshot.TimelineScope
import com.letta.mobile.data.timeline.snapshot.TimelineRevision
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DesktopConfirmedTimelineStoreTest {
    private lateinit var tempDir: Path
    private lateinit var store: DesktopConfirmedTimelineStore

    @BeforeTest
    fun setUp() {
        tempDir = Files.createTempDirectory("desktop-timeline-test")
        store = DesktopConfirmedTimelineStore(tempDir)
    }

    @AfterTest
    fun tearDown() {
        if (Files.exists(tempDir)) {
            Files.walk(tempDir)
                .sorted(Comparator.reverseOrder())
                .forEach { Files.deleteIfExists(it) }
        }
    }

    @Test
    fun writeAndReadSnapshotPreservesData() = runTest {
        val scope = TimelineScope(backendId = "backend-local", conversationId = "conv-1", agentId = "agent-1")
        val envelope = StoredTimelineEnvelope(
            schemaVersion = 1,
            scope = scope,
            revision = 10L,
            liveCursor = "srv-10",
            events = listOf(
                StoredTimelineEvent(
                    position = 1.0,
                    otid = "otid-1",
                    content = "Hello from Desktop persistence",
                    serverId = "srv-1",
                    messageType = "USER",
                    dateIso = "2026-08-24T00:00:00Z",
                )
            ),
            writtenAtMillis = 1000L,
        )

        val written = store.writeSnapshot(envelope)
        assertTrue(written)

        val read = store.readSnapshot(scope)
        assertNotNull(read)
        assertEquals(10L, read?.revision)
        assertEquals("srv-10", read?.liveCursor)
        assertEquals(1, read?.events?.size)
        assertEquals("Hello from Desktop persistence", read?.events?.first()?.content)
    }

    @Test
    fun metadataOnlyIncrementalApplyFailsClosedWithoutTruncatingEvents() = runTest {
        val scope = TimelineScope(backendId = "backend-local", conversationId = "conv-guard")
        val original = envelope(scope, revision = 1L, cursor = "cursor-1", content = "durable content")
        val updated = envelope(scope, revision = 2L, cursor = "cursor-2", content = "updated content")
        assertTrue(store.writeSnapshot(original))

        val result = store.commitNormalized(
            plan = NormalizedTimelineCommitPlanner.plan(original, updated),
            fullEnvelope = updated.copy(events = emptyList()),
        )

        assertEquals(
            NormalizedTimelineWriteResult.Invalid(NormalizedTimelineCommitFailure.UNSUPPORTED_PLAN),
            result,
        )
        assertEquals(original, store.readSnapshot(scope))
    }

    @Test
    fun unsupportedStoreDoesNotAdvertiseIncrementalCommits() {
        assertFalse(store.supportsIncrementalCommit)
    }

    @Test
    fun metadataOnlyNoOpCannotEraseAnEventBearingSnapshot() = runTest {
        val scope = TimelineScope(backendId = "backend-local", conversationId = "conv-no-op")
        val original = envelope(scope, revision = 1L, cursor = "cursor-1", content = "durable content")
        assertTrue(store.writeSnapshot(original))

        val result = store.commitNormalized(
            plan = NormalizedTimelineCommitPlan.NoOp(
                scope = scope,
                baseRevision = TimelineRevision(1L),
                targetRevision = TimelineRevision(2L),
                writtenAtMillis = 2L,
            ),
            fullEnvelope = original.copy(revision = 2L, events = emptyList()),
        )

        assertEquals(
            NormalizedTimelineWriteResult.Invalid(NormalizedTimelineCommitFailure.UNSUPPORTED_PLAN),
            result,
        )
        assertEquals(original, store.readSnapshot(scope))
    }

    @Test
    fun staleRevisionWritesAreRejected() = runTest {
        val scope = TimelineScope(backendId = "b1", conversationId = "c1")

        assertTrue(store.writeSnapshot(StoredTimelineEnvelope(scope = scope, revision = 5L)))
        assertFalse(store.writeSnapshot(StoredTimelineEnvelope(scope = scope, revision = 5L)))
        assertFalse(store.writeSnapshot(StoredTimelineEnvelope(scope = scope, revision = 4L)))
        assertTrue(store.writeSnapshot(StoredTimelineEnvelope(scope = scope, revision = 6L)))

        assertEquals(6L, store.readSnapshot(scope)?.revision)
    }

    @Test
    fun backendIsolationAndPrune() = runTest {
        val scopeA = TimelineScope(backendId = "backend-A", conversationId = "c1")
        val scopeB = TimelineScope(backendId = "backend-B", conversationId = "c1")

        store.writeSnapshot(StoredTimelineEnvelope(scope = scopeA, revision = 1L, writtenAtMillis = 100L))
        store.writeSnapshot(StoredTimelineEnvelope(scope = scopeB, revision = 1L, writtenAtMillis = 100L))

        assertNotNull(store.readSnapshot(scopeA))
        assertNotNull(store.readSnapshot(scopeB))

        store.clearForBackend("backend-A")
        assertNull(store.readSnapshot(scopeA))
        assertNotNull(store.readSnapshot(scopeB))
    }

    @Test
    fun normalizedPathCollisionsRemainIsolated() = runTest {
        val slashScope = TimelineScope(
            backendId = "https://host/a",
            agentId = "agent/a",
            conversationId = "conversation/a",
        )
        val underscoreScope = TimelineScope(
            backendId = "https://host_a",
            agentId = "agent_a",
            conversationId = "conversation_a",
        )

        assertTrue(store.writeSnapshot(StoredTimelineEnvelope(scope = slashScope, revision = 1L)))
        assertTrue(store.writeSnapshot(StoredTimelineEnvelope(scope = underscoreScope, revision = 2L)))

        assertEquals(1L, store.readSnapshot(slashScope)?.revision)
        assertEquals(2L, store.readSnapshot(underscoreScope)?.revision)
    }

    @Test
    fun corruptPayloadRecoversGracefully() = runTest {
        val scope = TimelineScope(backendId = "b1", conversationId = "c1")
        assertTrue(store.writeSnapshot(StoredTimelineEnvelope(scope = scope, revision = 1L)))
        val file = Files.walk(tempDir).use { paths ->
            paths.filter { Files.isRegularFile(it) }.findFirst().orElseThrow()
        }
        Files.writeString(file, "corrupt json payload")

        val read = store.readSnapshot(scope)
        assertNull(read)
    }

    private fun envelope(
        scope: TimelineScope,
        revision: Long,
        cursor: String,
        content: String,
    ) = StoredTimelineEnvelope(
        scope = scope,
        revision = revision,
        liveCursor = cursor,
        events = listOf(
            StoredTimelineEvent(
                position = 1.0,
                otid = "otid-guard",
                content = content,
                serverId = "server-guard",
                messageType = "ASSISTANT",
                dateIso = "2026-09-02T00:00:00Z",
            ),
        ),
        writtenAtMillis = revision,
    )
}
