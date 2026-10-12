package com.letta.mobile.data.document

import com.letta.mobile.data.document.markdown.MarkdownDocId
import com.letta.mobile.data.document.markdown.MarkdownDocument
import com.letta.mobile.data.document.markdown.MarkdownOp
import com.letta.mobile.data.document.markdown.openMarkdownDocument
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SharedDocumentTest {
    private val id = MarkdownDocId.agentTray("agent-1")
    private var now = 1_000L
    private val clock = { now++ }

    private fun text(opId: String, lamport: Long, markdown: String, actor: String = "user:alice") =
        MarkdownOp.SetMarkdown(opId, actor, lamport, markdown)

    private fun title(opId: String, lamport: Long, title: String, actor: String = "user:alice") =
        MarkdownOp.SetTitle(opId, actor, lamport, title)

    private suspend fun open(
        store: DocumentStore<MarkdownDocId, MarkdownDocument> = InMemoryDocumentStore(),
        log: OpLog<MarkdownDocId, MarkdownOp> = InMemoryOpLog(),
        budget: HistoryBudget = HistoryBudget(),
        archive: OpArchive<MarkdownDocId, MarkdownOp, MarkdownDocument>? = null,
        acl: PrincipalAcl? = null,
    ) = openMarkdownDocument(id, store, log, title = "Tray", agentId = "agent-1", acl = acl, budget = budget, archive = archive, clock = clock)

    @Test
    fun appliedOpsAreLoggedFoldedAndPersistedAsOneRevision() = runTest {
        val store = InMemoryDocumentStore<MarkdownDocId, MarkdownDocument>()
        val log = InMemoryOpLog<MarkdownDocId, MarkdownOp>()
        val doc = open(store, log)
        val result = doc.apply(listOf(title("t1", 1, "Notes"), text("m1", 2, "# hello")))

        assertEquals(2, result.applied.size)
        assertEquals("Notes", doc.snapshot.value.title)
        assertEquals("# hello", doc.snapshot.value.markdown)
        assertEquals(1, doc.snapshot.value.revision)
        assertEquals(doc.snapshot.value, store.get(id))
        assertEquals(listOf("t1", "m1"), log.getOps(id).map { it.opId })
        assertEquals(2, doc.lamport)
    }

    @Test
    fun replayFromTheBaseReachesTheSameContentInAnyArrivalOrder() = runTest {
        val ops = listOf(text("a", 3, "third"), text("b", 1, "first"), title("c", 2, "T"), text("d", 2, "second"))
        val forward = open().also { doc -> ops.forEach { doc.apply(it) } }
        val backward = open().also { doc -> ops.reversed().forEach { doc.apply(it) } }

        assertEquals("third", forward.snapshot.value.markdown)
        assertEquals(forward.snapshot.value.markdown, backward.snapshot.value.markdown)
        assertEquals(forward.snapshot.value.title, backward.snapshot.value.title)
        val replayed = forward.replay()
        assertEquals(forward.snapshot.value.markdown, replayed.markdown)
        assertEquals(forward.snapshot.value.title, replayed.title)
    }

    @Test
    fun redeliveredOpsAreDuplicatesAndChangeNothing() = runTest {
        val doc = open()
        doc.apply(text("m1", 1, "once"))
        val again = doc.apply(listOf(text("m1", 1, "once"), text("m2", 2, "x"), text("m2", 2, "x")))

        assertEquals(listOf("m2"), again.applied.map { it.opId })
        assertEquals(listOf("m1", "m2"), again.duplicates.map { it.opId })
        assertEquals(2, doc.snapshot.value.revision)
    }

    @Test
    fun theAclDeniesAnUnlistedActorAndKeepsNothingOfIt() = runTest {
        val log = InMemoryOpLog<MarkdownDocId, MarkdownOp>()
        val doc = open(log = log, acl = PrincipalAcl(ownerUserId = "alice", writerAgentIds = setOf("agent-1")))

        val denied = doc.apply(text("m1", 1, "intruder", actor = "agent:mallory"))
        assertEquals(1, denied.denied.size)
        assertTrue(denied.applied.isEmpty())
        assertEquals("", doc.snapshot.value.markdown)
        assertFalse(log.has(id, "m1"))

        assertEquals(1, doc.apply(text("m2", 2, "mine", actor = "agent:agent-1")).applied.size)
        assertEquals(1, doc.apply(text("m3", 3, "vouched", actor = "agent:host-checked"), vouchedActor = "agent:host-checked").applied.size)
        assertEquals("vouched", doc.snapshot.value.markdown)
    }

    @Test
    fun pastTheBudgetHistoryIsArchivedAndTheDocumentContinues() = runTest {
        val log = InMemoryOpLog<MarkdownDocId, MarkdownOp>()
        val archive = InMemoryOpArchive<MarkdownDocId, MarkdownOp, MarkdownDocument>()
        val doc = open(log = log, budget = HistoryBudget(maxOps = 3), archive = archive)
        (1L..4L).forEach { doc.apply(text("m$it", it, "v$it")) }

        val archived = archive.archived(id).single()
        assertEquals(listOf("m1", "m2", "m3", "m4"), archived.ops.map { it.opId })
        assertEquals("v4", archived.head.markdown)
        assertEquals("", archived.base.markdown)
        assertTrue(doc.history().isEmpty())
        assertEquals("v4", doc.snapshot.value.markdown)
        assertEquals("v4", doc.replay().markdown, "replay continues from the archived head")
        assertTrue(log.getOps(id).isEmpty(), "the log let go of what the archive holds")
        assertTrue(log.has(id, "m1"), "an archived op is still known, so its redelivery stays a duplicate")
        assertEquals(1, doc.apply(text("m1", 1, "v1")).duplicates.size)
        val fault = doc.storageFaults.value.last()
        assertEquals(SharedDocumentFault.Kind.COMPACTED, fault.kind)
        assertFalse(fault.isError)

        doc.apply(text("m5", 5, "v5"))
        assertEquals(listOf("m5"), doc.history().map { it.opId })
    }

    @Test
    fun historyIsNeverDiscardedWhenItCannotBeArchived() = runTest {
        val failing = object : OpArchive<MarkdownDocId, MarkdownOp, MarkdownDocument> {
            override suspend fun archive(documentId: MarkdownDocId, history: ArchivedHistory<MarkdownOp, MarkdownDocument>) {
                throw IllegalStateException("disk full")
            }

            override suspend fun archived(documentId: MarkdownDocId) = emptyList<ArchivedHistory<MarkdownOp, MarkdownDocument>>()
        }
        for (archive in listOf(failing, null)) {
            val log = InMemoryOpLog<MarkdownDocId, MarkdownOp>()
            val doc = open(log = log, budget = HistoryBudget(maxOps = 2), archive = archive)
            (1L..4L).forEach { doc.apply(text("m$it", it, "v$it")) }

            assertEquals(4, doc.history().size)
            assertEquals(4, log.getOps(id).size)
            val overBudget = doc.storageFaults.value.filter { it.kind == SharedDocumentFault.Kind.OVER_BUDGET }
            assertEquals(1, overBudget.size, "reported once, not on every op")
            assertTrue(overBudget.single().isError)
        }
    }

    @Test
    fun nearTheBudgetTheDocumentWarnsOnce() = runTest {
        val doc = open(budget = HistoryBudget(maxOps = 10, warnFraction = 0.5))
        (1L..7L).forEach { doc.apply(text("m$it", it, "v$it")) }
        val near = doc.storageFaults.value.filter { it.kind == SharedDocumentFault.Kind.NEAR_BUDGET }
        assertEquals(1, near.size)
        assertFalse(near.single().isError)
    }

    @Test
    fun aFailingStoreIsLoudButNotFatal() = runTest {
        val broken = object : InMemoryDocumentStore<MarkdownDocId, MarkdownDocument>() {
            var failing = false

            override suspend fun upsertIfRevision(doc: MarkdownDocument, expectedRevision: Long): Boolean =
                if (failing) throw IllegalStateException("read-only volume") else super.upsertIfRevision(doc, expectedRevision)
        }
        val doc = open(store = broken)
        broken.failing = true

        val result = doc.apply(text("m1", 1, "kept in memory"))
        assertEquals(1, result.applied.size)
        assertEquals("kept in memory", doc.snapshot.value.markdown)
        val fault = doc.storageFaults.value.single()
        assertEquals(SharedDocumentFault.Kind.SAVE_FAILED, fault.kind)
        assertTrue(fault.isError)
        assertTrue("read-only volume" in fault.message)
    }

    @Test
    fun aWriterThatLostTheRevisionRaceFoldsItsOpsOntoTheNewerRevision() = runTest {
        val store = InMemoryDocumentStore<MarkdownDocId, MarkdownDocument>()
        val doc = open(store = store)
        val stored = assertNotNull(store.get(id))
        store.upsert(stored.copy(revision = stored.revision + 5, title = "renamed elsewhere"))

        doc.apply(text("m1", 1, "body"))
        val persisted = assertNotNull(store.get(id))
        assertEquals("renamed elsewhere", persisted.title)
        assertEquals("body", persisted.markdown)
        assertEquals(stored.revision + 6, persisted.revision)
        assertEquals(persisted, doc.snapshot.value)
    }

    @Test
    fun openingFoldsOpsTheLogHoldsBeyondTheStoredSnapshot() = runTest {
        val store = InMemoryDocumentStore<MarkdownDocId, MarkdownDocument>()
        val log = InMemoryOpLog<MarkdownDocId, MarkdownOp>()
        open(store, log).apply(text("m1", 1, "saved"))
        log.append(id, text("m2", 2, "logged before a crash"))

        val reopened = open(store, log)
        assertEquals("logged before a crash", reopened.snapshot.value.markdown)
        assertEquals("logged before a crash", store.get(id)?.markdown)
        assertEquals(listOf("m2"), reopened.history().map { it.opId })
    }

    @Test
    fun presenceKeepsActivePeersAndDropsSilentOrLeavingOnes() = runTest {
        data class Peer(override val peerId: String, override val lastActiveEpochMs: Long = 0, override val isActive: Boolean = true) : PeerPresence

        var at = 0L
        val transport = InMemoryPresenceTransport<MarkdownDocId, Peer>(ttlMs = 100, clock = { at }) { peer, time -> peer.copy(lastActiveEpochMs = time) }
        val presence = open().presence(transport)

        presence.announce(Peer("a"))
        at = 50
        presence.announce(Peer("b"))
        assertEquals(setOf("a", "b"), presence.peers.first().map { it.peerId }.toSet())
        at = 120
        presence.announce(Peer("b"))
        assertEquals(listOf("b"), presence.peers.first().map { it.peerId }, "a went silent past the TTL")
        presence.announce(Peer("b", isActive = false))
        assertTrue(presence.peers.first().isEmpty())
    }
}
