package com.letta.mobile.data.document

import com.letta.mobile.util.Telemetry
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * P7 SharedDocument: one live, op-logged, revisioned document with bounded history.
 *
 * Every write goes the same way whatever the document is (a canvas board, the Agent Tray's
 * Markdown, a saved Space): the ACL judges each op's actor, the [OpLog] deduplicates and keeps it,
 * the [DocumentReducer] folds the batch into one new revision, and the [DocumentStore] persists it
 * by compare-and-set (folding the batch onto a newer stored revision when another writer won).
 *
 * History policy (the canvas's): keep history, bound it, never silently discard. Ops since the last
 * archive are the live [BoundedHistory]; past [HistoryBudget] they are handed to the [OpArchive]
 * and the document continues from its current snapshot, dropping them from a [CompactableOpLog].
 * When there is no archive, or it fails, everything is kept and an OVER_BUDGET fault says so.
 *
 * Storage failures are never fatal and never silent: the document keeps working from memory, logs
 * through [Telemetry], and publishes a [SharedDocumentFault] on [storageFaults].
 */
class SharedDocument<Id, Op : DocumentOp, Snapshot : DocumentSnapshot<Id>> private constructor(
    initial: Snapshot,
    private val backend: DocumentBackend<Id, Op, Snapshot>,
    private val options: SharedDocumentOptions<Id, Op, Snapshot>,
) : DocumentStorageHealth<SharedDocumentFault> {
    val id: Id = initial.id

    private val reducer get() = backend.reducer
    private val mutex = Mutex()
    private val _snapshot = MutableStateFlow(initial)
    private val faults = DocumentFaults(options.tag, initial.id.toString(), options.clock)
    private val live = BoundedHistory<Op, Snapshot>(initial, options.budget) { backend.reducer.sizeOf(it) }

    /** The document as it stands; updated on every applied batch. */
    val snapshot: StateFlow<Snapshot> = _snapshot.asStateFlow()

    override val storageFaults: StateFlow<List<SharedDocumentFault>> get() = faults.faults

    /** The highest Lamport clock this document has seen; a local writer stamps its next op above it. */
    var lamport: Long = backend.reducer.lamportOf(initial)
        private set

    /** Ops applied since the last archive, oldest first. */
    suspend fun history(): List<Op> = mutex.withLock { live.ops }

    /** Everything archived so far, oldest first. */
    suspend fun archivedHistory(): List<ArchivedHistory<Op, Snapshot>> = options.archive?.archived(id).orEmpty()

    /** [snapshot] rebuilt from the base and the live history, in [DocumentOpOrder]: what a fresh replica would hold. */
    suspend fun replay(): Snapshot = mutex.withLock {
        val ops = live.ops
        if (ops.isEmpty()) live.base else reducer.reduce(live.base, ops.sortedWith(DocumentOpOrder), options.clock())
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
        val mayWrite = writePermission(reducer.aclOf(current), vouchedActor)
        val (permitted, denied) = ops.partition(mayWrite)
        val (applied, duplicates) = splitDuplicates(permitted)
        if (denied.isNotEmpty()) reportDenied(denied)
        if (applied.isNotEmpty()) commit(current, applied, appendToLog = true)
        DocumentApplyResult(_snapshot.value, applied, denied, duplicates)
    }

    private fun writePermission(acl: DocumentAcl?, vouchedActor: String?): (Op) -> Boolean =
        { op -> acl == null || op.actorId == vouchedActor || acl.canWrite(op.actorId) }

    /** [ops] split into the new ones and those already logged or repeated in the batch. */
    private suspend fun splitDuplicates(ops: List<Op>): Pair<List<Op>, List<Op>> {
        val seen = mutableSetOf<String>()
        val fresh = mutableListOf<Op>()
        val duplicates = mutableListOf<Op>()
        for (op in ops) {
            if (seen.add(op.opId) && !logged(op)) fresh += op else duplicates += op
        }
        return fresh to duplicates
    }

    private suspend fun logged(op: Op): Boolean =
        faults.guarded(SharedDocumentFault.Kind.LOAD_FAILED, "log.has", fallback = false) { backend.opLog.has(id, op.opId) }

    private fun reportDenied(denied: List<Op>) {
        Telemetry.event(
            options.tag, "op.denied",
            "documentId" to id.toString(), "count" to denied.size, "actorId" to denied.first().actorId,
            level = Telemetry.Level.WARN,
        )
    }

    /** Under the lock: log [ops], fold them into [current], persist, remember them, and keep the budget. */
    private suspend fun commit(current: Snapshot, ops: List<Op>, appendToLog: Boolean) {
        if (appendToLog) ops.forEach { op -> faults.guarded(SharedDocumentFault.Kind.SAVE_FAILED, "log.append") { backend.opLog.append(id, op) } }
        lamport = maxOf(lamport, ops.maxOf { it.lamport })
        _snapshot.value = reducer.reduce(current, ops, options.clock())
        faults.guarded(SharedDocumentFault.Kind.SAVE_FAILED, "store.save") { persist(current.revision, ops) }
        live.record(ops)
        keepBudget()
    }

    /**
     * Writes the held snapshot by compare-and-set against [expectedRevision]. When another writer
     * moved the store on, [ops] are folded onto its revision instead, so neither write is lost.
     */
    private suspend fun persist(expectedRevision: Long, ops: List<Op>) {
        var expected = expectedRevision
        repeat(MAX_SAVE_ATTEMPTS) {
            val stored = casOrReread(expected) ?: return
            _snapshot.value = reducer.reduce(stored, ops, options.clock())
            expected = stored.revision
        }
        faults.report(
            SharedDocumentFault.Kind.SAVE_FAILED,
            "Lost the revision race $MAX_SAVE_ATTEMPTS times; revision ${_snapshot.value.revision} is only in memory",
        )
    }

    /** Null once the held snapshot is stored; otherwise the newer stored snapshot that beat it. */
    private suspend fun casOrReread(expected: Long): Snapshot? {
        val store = backend.store
        if (store.upsertIfRevision(_snapshot.value, expected)) return null
        val stored = store.get(id)
        if (stored == null) store.upsert(_snapshot.value)
        return stored
    }

    /** Under the lock: archive and continue past the budget; warn near it; never drop history unarchived. */
    private suspend fun keepBudget() {
        val pressure = live.pressure
        when {
            pressure == HistoryPressure.OVER -> archiveAndContinue()
            live.escalate(pressure) && pressure == HistoryPressure.NEAR ->
                faults.report(SharedDocumentFault.Kind.NEAR_BUDGET, "History is ${live.describe()}, near its budget")
        }
    }

    private suspend fun archiveAndContinue() {
        val archive = options.archive ?: return overBudget("no archive is configured", error = null)
        val head = _snapshot.value
        val stretch = live.stretchTo(head, options.clock())
        attempt { archive.archive(id, stretch) }.exceptionOrNull()?.let { error ->
            return overBudget("archiving failed: ${error.message}", error)
        }
        compactLog(stretch.ops)
        live.continueFrom(head)
        faults.report(
            SharedDocumentFault.Kind.COMPACTED,
            "Archived ${stretch.ops.size} ops of history; continuing from revision ${head.revision}",
        )
    }

    /** The archive holds [archived] now; a log that cannot drop them only stays larger. */
    private suspend fun compactLog(archived: List<Op>) {
        val log = backend.opLog as? CompactableOpLog<Id, *> ?: return
        val opIds = archived.mapTo(mutableSetOf()) { it.opId }
        faults.guarded(SharedDocumentFault.Kind.SAVE_FAILED, "log.compact") { log.dropArchived(id, opIds) }
    }

    private fun overBudget(reason: String, error: Throwable?) {
        if (!live.escalate(HistoryPressure.OVER)) return
        if (error != null) Telemetry.error(options.tag, "history.archive_failed", error, "documentId" to id.toString())
        faults.report(
            SharedDocumentFault.Kind.OVER_BUDGET,
            "History of ${live.describe()} is over its budget and $reason; all of it is kept",
        )
    }

    /** Opens on [seed] when the store has nothing, or on what it has; folds the log's ops past it. */
    private suspend fun load(seed: Snapshot) = mutex.withLock {
        val stored = attempt { backend.store.get(id) }
        stored.exceptionOrNull()?.let { faults.failed(SharedDocumentFault.Kind.LOAD_FAILED, "store.read", it) }
        val found = stored.getOrNull()
        if (found != null) {
            _snapshot.value = found
            live.continueFrom(found)
            lamport = reducer.lamportOf(found)
        } else if (stored.isSuccess) {
            faults.guarded(SharedDocumentFault.Kind.SAVE_FAILED, "store.create") { backend.store.upsert(seed) }
        }
        recover()
    }

    /** Under the lock: folds ops the log holds past the snapshot's clock (a crash between log and store). */
    private suspend fun recover() {
        val pending = faults.guarded(SharedDocumentFault.Kind.LOAD_FAILED, "log.read", fallback = emptyList()) {
            backend.opLog.getOps(id, reducer.lamportOf(_snapshot.value))
        }
        if (pending.isNotEmpty()) commit(_snapshot.value, pending.sortedWith(DocumentOpOrder), appendToLog = false)
    }

    companion object {
        private const val MAX_SAVE_ATTEMPTS = 3

        /**
         * Opens document `seed.id`: the stored snapshot, or [seed] (stored now) when there is none,
         * with any ops the log holds beyond it folded in. Never throws for storage: a failed read
         * opens on [seed] and reports a LOAD_FAILED fault.
         */
        suspend fun <Id, Op : DocumentOp, Snapshot : DocumentSnapshot<Id>> open(
            seed: Snapshot,
            backend: DocumentBackend<Id, Op, Snapshot>,
            options: SharedDocumentOptions<Id, Op, Snapshot> = SharedDocumentOptions(),
        ): SharedDocument<Id, Op, Snapshot> = SharedDocument(seed, backend, options).also { it.load(seed) }
    }
}
