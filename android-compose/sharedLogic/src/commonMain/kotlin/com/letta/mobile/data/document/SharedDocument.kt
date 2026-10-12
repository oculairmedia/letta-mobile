package com.letta.mobile.data.document

import com.letta.mobile.util.Telemetry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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

/**
 * P7 SharedDocument: one live, op-logged, revisioned document with bounded history.
 *
 * Every write goes the same way whatever the document is (a canvas board, the Agent Tray's
 * Markdown, a saved Space): the ACL judges each op's actor, the [OpLog] deduplicates and keeps it,
 * the [DocumentReducer] folds the batch into one new revision, and the [DocumentStore] persists it
 * by compare-and-set (folding the batch onto a newer stored revision when another writer won).
 *
 * History policy (the canvas's): keep history, bound it, never silently discard. Ops since the last
 * archive are the live history; past [HistoryBudget] they are handed to the [OpArchive] and the
 * document continues from its current snapshot, dropping them from a [CompactableOpLog]. When there
 * is no archive, or it fails, everything is kept and an OVER_BUDGET fault says so.
 *
 * Storage failures are never fatal and never silent: the document keeps working from memory, logs
 * through [Telemetry], and publishes a [SharedDocumentFault] on [storageFaults].
 */
// One document's write path, recovery, budget and fault reporting; storage failures of any type become faults.
@Suppress("TooManyFunctions", "TooGenericExceptionCaught")
class SharedDocument<Id, Op : DocumentOp, Snapshot : DocumentSnapshot<Id>> private constructor(
    initial: Snapshot,
    private val reducer: DocumentReducer<Op, Snapshot>,
    private val store: DocumentStore<Id, Snapshot>,
    private val opLog: OpLog<Id, Op>,
    private val budget: HistoryBudget,
    private val archive: OpArchive<Id, Op, Snapshot>?,
    private val clock: () -> Long,
    private val tag: String,
) : DocumentStorageHealth<SharedDocumentFault> {
    val id: Id = initial.id
    private val key: String = initial.id.toString()

    private val mutex = Mutex()
    private val _snapshot = MutableStateFlow(initial)

    /** The document as it stands; updated on every applied batch. */
    val snapshot: StateFlow<Snapshot> = _snapshot.asStateFlow()

    private val _faults = MutableStateFlow<List<SharedDocumentFault>>(emptyList())
    override val storageFaults: StateFlow<List<SharedDocumentFault>> = _faults.asStateFlow()

    /** The snapshot the live history starts from: the open state, or the head of the last archive. */
    private var base: Snapshot = initial
    private val history = mutableListOf<Op>()
    private var historyBytes = 0L
    private var pressure = HistoryPressure.WITHIN

    /** The highest Lamport clock this document has seen; a local writer stamps its next op above it. */
    var lamport: Long = reducer.lamportOf(initial)
        private set

    /** Ops applied since the last archive, oldest first. */
    suspend fun history(): List<Op> = mutex.withLock { history.toList() }

    /** Everything archived so far, oldest first. */
    suspend fun archivedHistory(): List<ArchivedHistory<Op, Snapshot>> = archive?.archived(id).orEmpty()

    /** [snapshot] rebuilt from the base and the live history, in [DocumentOpOrder]: what a fresh replica would hold. */
    suspend fun replay(): Snapshot = mutex.withLock {
        if (history.isEmpty()) base else reducer.reduce(base, history.sortedWith(DocumentOpOrder), clock())
    }

    fun <P : PeerPresence> presence(transport: PresenceTransport<Id, P>): DocumentPresence<Id, P> =
        DocumentPresence(id, transport)

    suspend fun apply(op: Op, vouchedActor: String? = null): DocumentApplyResult<Op, Snapshot> =
        apply(listOf(op), vouchedActor)

    /**
     * Applies [ops] as one revision. An op is denied when the ACL refuses its actor, unless the
     * transport vouches for that actor ([vouchedActor], as the Iroh host does for an agent it
     * checked against its own ACL); an op the log already holds is a duplicate.
     */
    suspend fun apply(ops: List<Op>, vouchedActor: String? = null): DocumentApplyResult<Op, Snapshot> = mutex.withLock {
        val current = _snapshot.value
        val acl = reducer.aclOf(current)
        val applied = mutableListOf<Op>()
        val denied = mutableListOf<Op>()
        val duplicates = mutableListOf<Op>()
        val seen = mutableSetOf<String>()
        for (op in ops) {
            when {
                acl != null && op.actorId != vouchedActor && !acl.canWrite(op.actorId) -> denied += op
                !seen.add(op.opId) || logged(op) -> duplicates += op
                else -> applied += op
            }
        }
        if (denied.isNotEmpty()) {
            Telemetry.event(
                tag, "op.denied",
                "documentId" to key, "count" to denied.size, "actorId" to denied.first().actorId,
                level = Telemetry.Level.WARN,
            )
        }
        if (applied.isNotEmpty()) commit(current, applied, appendToLog = true)
        DocumentApplyResult(_snapshot.value, applied, denied, duplicates)
    }

    /** Under the lock: log [ops], fold them into [current], persist, remember them, and keep the budget. */
    private suspend fun commit(current: Snapshot, ops: List<Op>, appendToLog: Boolean) {
        if (appendToLog) ops.forEach { op -> guarded(SharedDocumentFault.Kind.SAVE_FAILED, "log.append") { opLog.append(id, op) } }
        ops.maxOfOrNull { it.lamport }?.let { if (it > lamport) lamport = it }
        _snapshot.value = reducer.reduce(current, ops, clock())
        persist(current.revision, ops)
        history += ops
        historyBytes += ops.sumOf { reducer.sizeOf(it) }
        keepBudget()
    }

    /**
     * Writes the held snapshot by compare-and-set against [expectedRevision]. When another writer
     * moved the store on, [ops] are folded onto its revision instead, so neither write is lost.
     */
    private suspend fun persist(expectedRevision: Long, ops: List<Op>) {
        var expected = expectedRevision
        guarded(SharedDocumentFault.Kind.SAVE_FAILED, "store.save") {
            repeat(MAX_SAVE_ATTEMPTS) {
                if (store.upsertIfRevision(_snapshot.value, expected)) return@guarded
                val stored = store.get(id)
                if (stored == null) {
                    store.upsert(_snapshot.value)
                    return@guarded
                }
                _snapshot.value = reducer.reduce(stored, ops, clock())
                expected = stored.revision
            }
            fault(
                SharedDocumentFault.Kind.SAVE_FAILED,
                "Lost the revision race $MAX_SAVE_ATTEMPTS times; revision ${_snapshot.value.revision} is only in memory",
            )
        }
    }

    private suspend fun logged(op: Op): Boolean =
        guarded(SharedDocumentFault.Kind.LOAD_FAILED, "log.has", fallback = false) { opLog.has(id, op.opId) }

    /** Under the lock: archive and continue past the budget; warn near it; never drop history unarchived. */
    private suspend fun keepBudget() {
        when (budget.assess(historyBytes, history.size)) {
            HistoryPressure.WITHIN -> pressure = HistoryPressure.WITHIN
            HistoryPressure.NEAR -> if (pressure < HistoryPressure.NEAR) {
                pressure = HistoryPressure.NEAR
                fault(SharedDocumentFault.Kind.NEAR_BUDGET, "History is ${history.size} ops / $historyBytes bytes, near its budget")
            }
            HistoryPressure.OVER -> archiveAndContinue()
        }
    }

    private suspend fun archiveAndContinue() {
        val target = archive ?: return overBudget("no archive is configured", error = null)
        val head = _snapshot.value
        val stretch = ArchivedHistory(base, history.toList(), head, clock())
        try {
            target.archive(id, stretch)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            return overBudget("archiving failed: ${error.message}", error)
        }
        @Suppress("UNCHECKED_CAST") // Op is the log's own op type
        val compactable = opLog as? CompactableOpLog<Id, Op>
        compactable?.let { log ->
            // The archive holds them now; a log that cannot drop them only stays larger.
            guarded(SharedDocumentFault.Kind.SAVE_FAILED, "log.compact") { log.dropArchived(id, stretch.ops.mapTo(mutableSetOf()) { it.opId }) }
        }
        base = head
        history.clear()
        historyBytes = 0L
        pressure = HistoryPressure.WITHIN
        fault(
            SharedDocumentFault.Kind.COMPACTED,
            "Archived ${stretch.ops.size} ops of history; continuing from revision ${head.revision}",
        )
    }

    private fun overBudget(reason: String, error: Throwable?) {
        if (pressure == HistoryPressure.OVER) return
        pressure = HistoryPressure.OVER
        val message = "History of ${history.size} ops / $historyBytes bytes is over its budget and $reason; all of it is kept"
        if (error != null) Telemetry.error(tag, "history.archive_failed", error, "documentId" to key)
        fault(SharedDocumentFault.Kind.OVER_BUDGET, message)
    }

    /** Runs [action]; a failure becomes a [kind] fault and [fallback], never a crash. */
    private suspend fun <T> guarded(kind: SharedDocumentFault.Kind, what: String, fallback: T, action: suspend () -> T): T =
        try {
            action()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Telemetry.error(tag, "storage.$what", error, "documentId" to key)
            fault(kind, "$what failed: ${error.message ?: error::class.simpleName}")
            fallback
        }

    private suspend fun guarded(kind: SharedDocumentFault.Kind, what: String, action: suspend () -> Unit) =
        guarded(kind, what, Unit, action)

    private fun fault(kind: SharedDocumentFault.Kind, message: String) {
        val fault = SharedDocumentFault(kind, key, message, clock())
        Telemetry.event(
            tag, "fault.${kind.name.lowercase()}", "documentId" to key, "message" to message,
            level = if (fault.isError) Telemetry.Level.ERROR else Telemetry.Level.INFO,
        )
        _faults.value = (_faults.value + fault).takeLast(MAX_FAULTS)
    }

    /** Folds ops the log holds past the stored snapshot's clock: a crash between log and store. */
    private suspend fun recover() = mutex.withLock {
        val pending = guarded(SharedDocumentFault.Kind.LOAD_FAILED, "log.read", fallback = emptyList()) {
            opLog.getOps(id, reducer.lamportOf(_snapshot.value))
        }
        if (pending.isNotEmpty()) commit(_snapshot.value, pending.sortedWith(DocumentOpOrder), appendToLog = false)
    }

    companion object {
        private const val MAX_SAVE_ATTEMPTS = 3
        private const val MAX_FAULTS = 32

        /**
         * Opens document [id]: the stored snapshot, or [create]'s (stored now), with any ops the log
         * holds beyond it folded in. Never throws for storage: a failed read opens [create]'s
         * snapshot and reports a LOAD_FAILED fault.
         */
        @Suppress("LongParameterList") // the document's parts, each with a default where one exists
        suspend fun <Id, Op : DocumentOp, Snapshot : DocumentSnapshot<Id>> open(
            id: Id,
            create: () -> Snapshot,
            reducer: DocumentReducer<Op, Snapshot>,
            store: DocumentStore<Id, Snapshot>,
            opLog: OpLog<Id, Op>,
            budget: HistoryBudget = HistoryBudget(),
            archive: OpArchive<Id, Op, Snapshot>? = null,
            clock: () -> Long = { kotlin.time.Clock.System.now().toEpochMilliseconds() },
            tag: String = "SharedDocument",
        ): SharedDocument<Id, Op, Snapshot> {
            var readError: Exception? = null
            val stored = try {
                store.get(id)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                readError = error
                null
            }
            val initial = stored ?: create()
            require(initial.id == id) { "create() made document ${initial.id}, not $id" }
            val document = SharedDocument(initial, reducer, store, opLog, budget, archive, clock, tag)
            if (readError != null) {
                Telemetry.error(tag, "storage.store.read", readError, "documentId" to id.toString())
                document.fault(SharedDocumentFault.Kind.LOAD_FAILED, "store.read failed: ${readError.message}")
            } else if (stored == null) {
                document.guarded(SharedDocumentFault.Kind.SAVE_FAILED, "store.create") { store.upsert(initial) }
            }
            document.recover()
            return document
        }
    }
}
