package com.letta.mobile.data.canvas

import com.letta.mobile.data.document.DocumentBacking
import com.letta.mobile.data.document.HistoryBudget
import com.letta.mobile.data.document.attempt
import com.letta.mobile.data.model.CanvasId
import com.letta.mobile.data.model.CanvasOpId
import com.letta.mobile.data.serialization.LettaJson
import com.letta.mobile.data.storage.AssetStore
import com.letta.mobile.data.storage.KeyValueStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable

/**
 * The canvas's stores over one [KeyValueStore], for a platform without the notebook repository
 * (the web, over IndexedDB): the boards ([documents]) and their op log and journal ([opLog]),
 * the same [CanvasDocumentStore] and [CanvasOpLog] a [CanvasSession] and the canvas relay take on
 * desktop, so the shared session and sync code persist here unchanged.
 *
 * Storage failures are never fatal and never silent: each is logged at ERROR and kept in
 * [storageFaults], which the board shows; the canvas keeps working from memory.
 *
 * History is bounded by [budget]: once a board's log holds more than `budget.maxOps` ops, the
 * oldest settled ones (already folded into the stored board) are dropped down to the warning
 * share of the budget. Unsettled ops are never dropped. Dropped op ids are remembered for this
 * session only.
 */
class KeyValueCanvasStorage(
    store: KeyValueStore,
    budget: HistoryBudget = DEFAULT_BUDGET,
    clock: () -> Long = { kotlin.time.Clock.System.now().toEpochMilliseconds() },
) : CanvasStorageHealth {
    private val faults = CanvasStorageFaults()
    private val io = StorageIo(store, faults, clock)

    override val storageFaults: StateFlow<List<CanvasStorageFault>> get() = faults.faults

    /** The boards: held in memory, read from [store] once and written through to it. */
    val documents: CanvasDocumentStore = InMemoryCanvasDocumentStore(Boards(io))

    /** The boards' op log and write-ahead journal. */
    val opLog: CanvasOpLog = KeyValueCanvasOpLog(io, budget)

    /** The relay's durable delivery record (queued edits, host cursors), under [DELIVERY_KEY]. */
    val delivery: CanvasDeliveryStore = KeyValueCanvasDeliveryStore(io)

    /**
     * The canvas relay client over these stores, bound as desktop binds its own: topics by
     * conversation, ops for closed boards into the stored board, and - because this log is
     * bounded - a never-shared board's first join seeded from the board ([StoreCanvasSnapshotSeed]).
     */
    fun relayClient(assets: AssetStore? = null): CanvasRelayClient = CanvasRelayClient(
        opLog = opLog,
        delivery = delivery,
        topicOf = { documents.relayTopicOf(it) },
        assets = assets,
        closedBoard = StoreCanvasClosedBoardApplier(documents, opLog),
        snapshotSeed = StoreCanvasSnapshotSeed(documents, opLog),
    )

    companion object {
        const val BOARD_PREFIX: String = "canvas/board/"
        const val OPS_PREFIX: String = "canvas/ops/"
        const val DELIVERY_KEY: String = "canvas/delivery"

        /** Op-log entries kept per board, past which settled history is dropped. */
        val DEFAULT_BUDGET: HistoryBudget = HistoryBudget(maxOps = 2_000)
    }
}

/** One stored value: its key, and the board it belongs to when there is one. */
private data class StoredEntry(val key: String, val canvasId: CanvasId? = null) {
    companion object {
        fun board(id: CanvasId) = StoredEntry(KeyValueCanvasStorage.BOARD_PREFIX + id.value, id)

        fun ops(id: CanvasId) = StoredEntry(KeyValueCanvasStorage.OPS_PREFIX + id.value, id)
    }
}

/** [KeyValueStore] calls that report a failure as a storage fault instead of throwing. */
private class StorageIo(val store: KeyValueStore, val faults: CanvasStorageFaults, val clock: () -> Long) {
    suspend fun <T> guarded(kind: CanvasStorageFault.Kind, entry: StoredEntry, fallback: T, action: suspend () -> T): T =
        attempt(action).getOrElse { error ->
            faults.record(fault(kind, entry, describe(kind, error)), error)
            fallback
        }

    fun fault(kind: CanvasStorageFault.Kind, entry: StoredEntry, message: String) = CanvasStorageFault(
        kind, documentId = entry.key, canvasId = entry.canvasId, documentBytes = null, message = message, atEpochMs = clock(),
    )

    private fun describe(kind: CanvasStorageFault.Kind, error: Throwable): String {
        val what = if (kind == CanvasStorageFault.Kind.SAVE_FAILED) "Not saved; changes are only in memory" else "Could not be read"
        return "$what: ${error.message ?: error::class.simpleName}"
    }
}

/** The boards, one JSON value per board under [KeyValueCanvasStorage.BOARD_PREFIX]. */
private class Boards(private val io: StorageIo) : DocumentBacking<CanvasDocument>, CanvasStorageHealth {
    override val storageFaults: StateFlow<List<CanvasStorageFault>> get() = io.faults.faults

    override suspend fun loadAll(): List<CanvasDocument> {
        val prefix = KeyValueCanvasStorage.BOARD_PREFIX
        val keys = io.guarded(CanvasStorageFault.Kind.LOAD_FAILED, StoredEntry(prefix), emptyList()) { io.store.keys(prefix) }
        return keys.mapNotNull { key ->
            io.guarded(CanvasStorageFault.Kind.LOAD_FAILED, StoredEntry(key), null) {
                io.store.get(key)?.let { LettaJson.persisted.decodeFromString(CanvasDocument.serializer(), it) }
            }
        }
    }

    override suspend fun write(doc: CanvasDocument) {
        val entry = StoredEntry.board(doc.id)
        io.guarded(CanvasStorageFault.Kind.SAVE_FAILED, entry, Unit) {
            io.store.put(entry.key, LettaJson.persisted.encodeToString(CanvasDocument.serializer(), doc))
        }
    }
}

/** The relay's delivery record as one JSON value; a read or write that fails is a fault, never fatal. */
private class KeyValueCanvasDeliveryStore(private val io: StorageIo) : StateCanvasDeliveryStore() {
    private val entry = StoredEntry(KeyValueCanvasStorage.DELIVERY_KEY)

    override suspend fun load(): CanvasDeliveryState =
        io.guarded(CanvasStorageFault.Kind.LOAD_FAILED, entry, CanvasDeliveryState()) {
            io.store.get(entry.key)?.let { LettaJson.persisted.decodeFromString(CanvasDeliveryState.serializer(), it) }
                ?: CanvasDeliveryState()
        }

    override suspend fun save(state: CanvasDeliveryState) {
        io.guarded(CanvasStorageFault.Kind.SAVE_FAILED, entry, Unit) {
            io.store.put(entry.key, LettaJson.persisted.encodeToString(CanvasDeliveryState.serializer(), state))
        }
    }
}

/**
 * A board's log as stored: its kept ops in append order, which of them the board holds, and whether
 * settled history was ever dropped from it.
 */
@Serializable
private data class StoredCanvasOps(
    val ops: List<CanvasOp> = emptyList(),
    val settled: List<String> = emptyList(),
    val droppedHistory: Boolean = false,
)

/**
 * The [InMemoryCanvasOpLog] (dedup, journal, observers), read back from storage per board on first
 * use and written through after every append and settle.
 */
private class KeyValueCanvasOpLog(private val io: StorageIo, private val budget: HistoryBudget) : CanvasOpLog {
    private val memory = InMemoryCanvasOpLog()
    private val lock = Mutex()
    private val loaded = mutableSetOf<CanvasId>()
    private val dropped = mutableSetOf<CanvasId>()

    override suspend fun append(canvasId: CanvasId, op: CanvasOp) = lock.withLock {
        load(canvasId)
        if (!memory.has(canvasId, op.canvasOpId)) {
            memory.append(canvasId, op)
            save(canvasId)
        }
    }

    override suspend fun getOps(canvasId: CanvasId, sinceLamport: Long): List<CanvasOp> =
        lock.withLock { load(canvasId) }.let { memory.getOps(canvasId, sinceLamport) }

    override suspend fun has(canvasId: CanvasId, opId: CanvasOpId): Boolean =
        lock.withLock { load(canvasId) }.let { memory.has(canvasId, opId) }

    override fun observe(canvasId: CanvasId): Flow<CanvasOp> = memory.observe(canvasId)

    override suspend fun hasDroppedHistory(canvasId: CanvasId): Boolean =
        lock.withLock { load(canvasId).let { canvasId in dropped } }

    override suspend fun unsettled(canvasId: CanvasId): List<CanvasOp> =
        lock.withLock { load(canvasId) }.let { memory.unsettled(canvasId) }

    override suspend fun settle(canvasId: CanvasId, opIds: Collection<CanvasOpId>) = lock.withLock {
        load(canvasId)
        memory.settle(canvasId, opIds)
        save(canvasId)
    }

    /** Under [lock]: reads [canvasId]'s stored log into memory, once. */
    private suspend fun load(canvasId: CanvasId) {
        if (!loaded.add(canvasId)) return
        val entry = StoredEntry.ops(canvasId)
        val stored = io.guarded(CanvasStorageFault.Kind.LOAD_FAILED, entry, null) {
            io.store.get(entry.key)?.let { LettaJson.persisted.decodeFromString(StoredCanvasOps.serializer(), it) }
        } ?: return
        if (stored.droppedHistory) dropped += canvasId
        memory.restore(canvasId, stored.ops, stored.settled.toSet())
    }

    /** Under [lock]: keeps the log within [budget] and writes it. */
    private suspend fun save(canvasId: CanvasId) {
        val pending = memory.unsettled(canvasId).mapTo(mutableSetOf()) { it.opId }
        val ops = compact(canvasId, memory.getOps(canvasId, Long.MIN_VALUE), pending)
        val record = StoredCanvasOps(ops, ops.map { it.opId }.filterNot { it in pending }, droppedHistory = canvasId in dropped)
        val entry = StoredEntry.ops(canvasId)
        io.guarded(CanvasStorageFault.Kind.SAVE_FAILED, entry, Unit) {
            io.store.put(entry.key, LettaJson.persisted.encodeToString(StoredCanvasOps.serializer(), record))
        }
    }

    /** [ops] within the budget: past it, the oldest settled ones go, down to the warning share. */
    private suspend fun compact(canvasId: CanvasId, ops: List<CanvasOp>, pending: Set<String>): List<CanvasOp> {
        if (ops.size <= budget.maxOps) return ops
        val excess = ops.size - budget.warnOps
        val dropped = ops.asSequence().filter { it.opId !in pending }.take(excess).mapTo(mutableSetOf()) { it.opId }
        memory.dropArchived(canvasId, dropped)
        if (dropped.isNotEmpty()) this.dropped += canvasId
        val note = "Dropped ${dropped.size} settled ops the board already holds"
        io.faults.record(io.fault(CanvasStorageFault.Kind.COMPACTED, StoredEntry.ops(canvasId), note))
        return ops.filterNot { it.opId in dropped }
    }
}
