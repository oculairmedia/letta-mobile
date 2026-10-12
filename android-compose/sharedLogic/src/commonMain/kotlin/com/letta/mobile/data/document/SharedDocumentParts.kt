package com.letta.mobile.data.document

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * What one kind of shared document means: how ops fold into a snapshot, who may write it, how
 * heavy an op is for the history budget, and how far a snapshot's clock has got.
 */
interface DocumentReducer<Op : DocumentOp, Snapshot> {
    /**
     * [snapshot] with [ops] applied in the order given, as ONE new revision stamped [nowEpochMs].
     * Conflicts are settled by [DocumentOpOrder] inside the reducer, so arrival order never
     * changes the result.
     */
    fun reduce(snapshot: Snapshot, ops: List<Op>, nowEpochMs: Long): Snapshot

    /** Roughly what [op] costs to keep, in bytes, for [HistoryBudget.maxBytes]. */
    fun sizeOf(op: Op): Long

    /** Who may write [snapshot]; null is anyone. */
    fun aclOf(snapshot: Snapshot): DocumentAcl?

    /** The highest Lamport clock already folded into [snapshot]: recovery replays the log after it. */
    fun lamportOf(snapshot: Snapshot): Long
}

/** Where one kind of document lives: its meaning ([reducer]), its snapshots ([store]) and its ops ([opLog]). */
data class DocumentBackend<Id, Op : DocumentOp, Snapshot : DocumentSnapshot<Id>>(
    val reducer: DocumentReducer<Op, Snapshot>,
    val store: DocumentStore<Id, Snapshot>,
    val opLog: OpLog<Id, Op>,
)

/**
 * How a [SharedDocument] bounds and reports its history: the [budget], where history goes past it
 * ([archive]; none keeps everything and says so), the [clock], and the telemetry [tag].
 */
data class SharedDocumentOptions<Id, Op, Snapshot>(
    val budget: HistoryBudget = HistoryBudget(),
    val archive: OpArchive<Id, Op, Snapshot>? = null,
    val clock: () -> Long = { kotlin.time.Clock.System.now().toEpochMilliseconds() },
    val tag: String = "SharedDocument",
)

/** A stretch of history set aside by archive-and-continue: [ops] took [base] to [head]. */
data class ArchivedHistory<Op, Snapshot>(
    val base: Snapshot,
    val ops: List<Op>,
    val head: Snapshot,
    val archivedAtEpochMs: Long,
)

/**
 * Where archived history goes. [archive] must be durable when it returns and throw when it cannot
 * be: only then does the [SharedDocument] let go of the live history.
 */
interface OpArchive<Id, Op, Snapshot> {
    suspend fun archive(documentId: Id, history: ArchivedHistory<Op, Snapshot>)

    /** Everything archived for [documentId], oldest first: the restore path. */
    suspend fun archived(documentId: Id): List<ArchivedHistory<Op, Snapshot>>
}

/** An [OpArchive] that lasts as long as the process: tests, and hosts without storage. */
class InMemoryOpArchive<Id, Op, Snapshot> : OpArchive<Id, Op, Snapshot> {
    private val mutex = Mutex()
    private val byDocument = mutableMapOf<Id, MutableList<ArchivedHistory<Op, Snapshot>>>()

    override suspend fun archive(documentId: Id, history: ArchivedHistory<Op, Snapshot>) {
        mutex.withLock { byDocument.getOrPut(documentId) { mutableListOf() } += history }
    }

    override suspend fun archived(documentId: Id): List<ArchivedHistory<Op, Snapshot>> =
        mutex.withLock { byDocument[documentId]?.toList().orEmpty() }
}

/** What [SharedDocument.apply] did with each op it was handed. */
data class DocumentApplyResult<Op, Snapshot>(
    /** The document after the call. */
    val snapshot: Snapshot,
    /** Ops that landed, in the order given. */
    val applied: List<Op>,
    /** Ops whose actor the document's ACL refused; nothing of them was kept. */
    val denied: List<Op>,
    /** Ops already in the log (or repeated in the call): redelivery, ignored. */
    val duplicates: List<Op>,
)

/** One document's peers, over a [PresenceTransport] keyed like the document. */
class DocumentPresence<Id, P : PeerPresence>(
    val documentId: Id,
    private val transport: PresenceTransport<Id, P>,
) {
    suspend fun announce(presence: P) = transport.updatePresence(documentId, presence)

    val peers: Flow<List<P>> get() = transport.observePresence(documentId)
}
