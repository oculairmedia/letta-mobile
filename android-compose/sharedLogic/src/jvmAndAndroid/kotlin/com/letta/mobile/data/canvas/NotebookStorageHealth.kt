package com.letta.mobile.data.canvas

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.Optional
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ForkJoinWorkerThread
import com.letta.mobile.util.Telemetry
import org.automerge.repo.Repo
import org.automerge.repo.Storage
import org.automerge.repo.StorageKey

/**
 * Keeps one notebook repository's storage healthy and its failures visible: the history budget
 * at open, storage failures and repository-thread errors as [CanvasStorageFault]s, the documents
 * set aside because they could not be opened safely, the documents retired by a move to a new
 * document, and the read-only state the store falls back to when it cannot vouch for itself.
 */
internal class NotebookStorageHealth(
    private val storageRoot: Path,
    private val budget: NotebookHistoryBudget,
    private val index: NotebookDocumentIndex,
    /** The repository's cross-process canvas lock; moves and recovery run under it. */
    private val lock: (() -> Unit) -> Unit,
    private val archive: NotebookHistoryArchive = NotebookHistoryArchive(storageRoot),
) : AutoCloseable {
    val faults = NotebookStorageFaults()
    private val quarantined = ConcurrentHashMap.newKeySet<String>()
    private val canvases = ConcurrentHashMap<String, CanvasId>()
    private val budgetLevel = ConcurrentHashMap<String, Int>()
    private val storageNames = ConcurrentHashMap<String, String>()
    private val layoutRefused = ConcurrentHashMap.newKeySet<String>()
    private val retired = ConcurrentHashMap.newKeySet<String>()
    private val retiredStorage = ConcurrentHashMap.newKeySet<String>()
    private val retiredOffers = ConcurrentHashMap.newKeySet<String>()

    @Volatile
    private var repositoryPool: ExecutorService? = null

    @Volatile
    private var readOnlyReason: String? = null

    private val guard = AutomergeFaultGuard.Listener(owns = ::ownsThread, onError = ::onRepositoryError)

    init {
        AutomergeFaultGuard.register(guard)
    }

    @Volatile
    private var preparing: Thread? = null

    /**
     * Recover interrupted moves and apply the history budget to every indexed document, on a
     * background thread: reading the index and walking each document's files is disk I/O, and
     * Android builds the store on its main thread. [awaitPrepared] holds back every open until it
     * is done, so the repository never loads a document mid-move, and no canvas session can be
     * bound to a document before it is moved.
     */
    fun prepare() {
        val thread = Thread(::scan, "notebook-history-compactor").apply { isDaemon = true }
        preparing = thread
        thread.start()
    }

    /** Wait for [prepare]; every repository open goes through here. */
    fun awaitPrepared() {
        preparing?.takeIf { it !== Thread.currentThread() }?.join()
    }

    private fun scan() {
        try {
            lock {
                archive.recoverInterrupted(index)
                learnRetired()
            }
        } catch (error: Exception) {
            record(CanvasStorageFault.Kind.LOAD_FAILED, null, null, "Interrupted notebook moves could not be recovered", error)
        }
        val keys = try {
            index.read()
        } catch (error: Exception) {
            record(CanvasStorageFault.Kind.LOAD_FAILED, null, null, "Notebook index unreadable", error)
            return
        }
        for (key in keys) {
            try {
                lock {
                    // Re-read under the lock: another store may have moved it since.
                    if (key in index.read()) {
                        val bytes = archive.documentBytes(key)
                        when {
                            bytes > budget.maxDocumentBytes -> move(key, bytes)
                            bytes >= budget.warnBytes -> nearBudget(key, bytes, WARNED)
                        }
                    }
                }
            } catch (error: Exception) {
                record(CanvasStorageFault.Kind.LOAD_FAILED, key, null, "Notebook storage could not be inspected", error)
            }
        }
        runCatching { lock { learnRetired() } }
    }

    private fun learnRetired() {
        index.retired().forEach { key ->
            if (retired.add(key)) retiredStorage += archive.storageName(key)
        }
    }

    /** Whether [key] was moved to a new document; it is never opened, indexed or stored again. */
    fun isRetired(key: String): Boolean = key in retired

    /** Retired documents a peer offered (and that were refused) since the store opened. */
    fun retiredOffered(): Set<String> = retiredOffers.toSet()

    /**
     * Move an over-budget document to a new one: archive its history, copy its current state, swap
     * the index, retire the old id. On failure everything is put back and the board shows why.
     */
    private fun move(key: String, bytes: Long) {
        val result = try {
            archive.compactToNewDocument(key, System.currentTimeMillis(), index, budget.restoreListBytes)
        } catch (error: Throwable) {
            failedMove(key, bytes, error)
            return
        } finally {
            runCatching { learnRetired() }
        }
        budgetLevel.remove(key)
        result.canvasId?.let { canvases[result.newKey] = it }
        val leftInArchive = result.restoreEntriesArchived
        record(
            CanvasStorageFault.Kind.COMPACTED,
            result.newKey,
            result.bytesAfter,
            "History of ${mb(bytes)} passed the ${mb(budget.maxDocumentBytes)} budget; archived to " +
                "${NotebookHistoryArchive.ARCHIVE_DIRECTORY}/$key/${result.archive.fileName}, and the board continues " +
                "in document ${result.newKey} from ${result.bytesAfter / KIB} KB" +
                if (leftInArchive > 0) "; $leftInArchive deleted elements are restorable only from the archive" else "",
        )
    }

    private fun failedMove(key: String, bytes: Long, error: Throwable) {
        // Finish the move if it got past its commit point; otherwise undo it.
        runCatching { archive.recoverInterrupted(index) }.exceptionOrNull()?.let(error::addSuppressed)
        runCatching { learnRetired() }
        if (runCatching { key !in index.read() && isRetired(key) }.getOrDefault(false)) {
            record(
                CanvasStorageFault.Kind.COMPACTED, null, null,
                "Notebook history of ${mb(bytes)} was archived and moved to a new document, with errors", error,
            )
            return
        }
        // A document too large to move is too large for the repository to save: set it aside so
        // this process does not die on it. Its files stay where they are.
        val tooLarge = bytes > budget.maxDocumentBytes * QUARANTINE_FACTOR
        if (tooLarge) quarantined += key
        budgetLevel[key] = OVER
        record(
            if (tooLarge) CanvasStorageFault.Kind.QUARANTINED else CanvasStorageFault.Kind.OVER_BUDGET,
            key,
            bytes,
            "Notebook history of ${mb(bytes)} is over its ${mb(budget.maxDocumentBytes)} budget and could not be " +
                "archived and moved to a new document; " +
                if (tooLarge) "the document is set aside unopened" else "opening it as it is",
            error,
        )
    }

    private fun nearBudget(key: String, bytes: Long, level: Int) {
        if ((budgetLevel[key] ?: 0) >= level) return
        budgetLevel[key] = level
        val note = if (level == OVER) {
            "it will be archived and moved to a new document the next time the notebook store opens"
        } else {
            "${(budget.warnFraction * PERCENT).toInt()}% of it"
        }
        record(
            if (level == OVER) CanvasStorageFault.Kind.OVER_BUDGET else CanvasStorageFault.Kind.NEAR_BUDGET,
            key,
            bytes,
            "Notebook history is ${mb(bytes)} of a ${mb(budget.maxDocumentBytes)} budget; $note",
        )
    }

    /** Check a document after a save; the repository writes in the background, so this trails by a save. */
    fun checkBudget(key: String) {
        val bytes = runCatching { archive.documentBytes(key) }.getOrNull() ?: return
        when {
            bytes > budget.maxDocumentBytes -> nearBudget(key, bytes, OVER)
            bytes >= budget.warnBytes -> nearBudget(key, bytes, WARNED)
        }
    }

    fun isQuarantined(key: String): Boolean = key in quarantined

    fun rememberCanvas(key: String, canvasId: CanvasId) {
        canvases[key] = canvasId
    }

    /**
     * The repository's storage: failures are reported, and retired documents are neither loaded
     * nor stored, so a peer offering one cannot bring it back.
     */
    fun observe(storage: Storage): Storage = RetiredDocumentFilter(ObservedStorage(storage, ::onStorageFailure))

    private inner class RetiredDocumentFilter(private val delegate: Storage) : Storage {
        private fun retired(key: StorageKey): Boolean = key.parts.firstOrNull()?.let { it in retiredStorage } == true

        private fun refused(key: StorageKey) {
            val name = key.parts.first()
            val documentKey = retired.firstOrNull { archive.storageName(it) == name } ?: name
            if (retiredOffers.add(documentKey)) {
                Telemetry.event(
                    NotebookStorageFaults.TAG, "retired_document_refused",
                    "documentId" to documentKey,
                    "reason" to "moved to a new document; a peer's copy is not stored",
                    level = Telemetry.Level.WARN,
                )
            }
        }

        override fun load(key: StorageKey): CompletableFuture<Optional<ByteArray>> =
            if (retired(key)) CompletableFuture.completedFuture(Optional.empty()) else delegate.load(key)

        override fun loadRange(prefix: StorageKey): CompletableFuture<MutableMap<StorageKey, ByteArray>> =
            if (retired(prefix)) {
                CompletableFuture.completedFuture(HashMap())
            } else {
                delegate.loadRange(prefix).thenApply { found -> found.filterKeys { !retired(it) }.toMutableMap() }
            }

        override fun put(key: StorageKey, value: ByteArray): CompletableFuture<Void> =
            if (retired(key)) {
                refused(key)
                CompletableFuture.completedFuture(null)
            } else {
                delegate.put(key, value)
            }

        override fun delete(key: StorageKey): CompletableFuture<Void> =
            if (retired(key)) CompletableFuture.completedFuture(null) else delegate.delete(key)
    }

    /** Learn [repo]'s document pool, so errors on its worker threads are recognised as its own. */
    fun attach(repo: Repo) {
        repositoryPool = documentPoolOf(repo)
    }

    private fun ownsThread(thread: Thread): Boolean {
        if (AutomergeFaultGuard.isRepositoryThreadName(thread.name)) return true
        val pool = repositoryPool ?: return false
        return thread is ForkJoinWorkerThread && thread.pool === pool
    }

    /** Throw if the store is read-only; the fault is already on the board. */
    fun checkWritable() {
        readOnlyReason?.let { throw NotebookReadOnlyException("Notebook store is read-only: $it") }
    }

    val isReadOnly: Boolean get() = readOnlyReason != null

    /**
     * [key]'s board layout is newer than this build: refuse to write it, so an older reader does
     * not overwrite fields it cannot see, and say so once.
     */
    fun refuseLayout(key: String, version: Long): NotebookReadOnlyException {
        val message = "Board layout $version is newer than this build reads (${NotebookBoardStorage.LAYOUT_VERSION}); " +
            "the notebook is read-only here so this build cannot overwrite what it does not understand. Update the app"
        if (layoutRefused.add(key)) {
            record(CanvasStorageFault.Kind.READ_ONLY, key, runCatching { archive.documentBytes(key) }.getOrNull(), message)
        }
        return NotebookReadOnlyException(message)
    }

    private fun onStorageFailure(operation: String, storageKey: StorageKey, bytes: Long?, error: Throwable) {
        val key = documentKeyOf(storageKey)
        val load = operation.startsWith("load")
        record(
            if (load) CanvasStorageFault.Kind.LOAD_FAILED else CanvasStorageFault.Kind.SAVE_FAILED,
            key,
            key?.let { runCatching { archive.documentBytes(it) }.getOrNull() } ?: bytes,
            "Notebook storage $operation of ${storageKey.parts.joinToString("/")}" +
                (bytes?.let { " (${mb(it)})" } ?: "") + " failed",
            error,
        )
    }

    /**
     * An [Error] escaped one of the repository's threads, and [AutomergeFaultGuard] kept it from
     * ending the process. The repository does not say which document it was saving, so the
     * largest one is named (an oversized snapshot is what such failures have been). Its state can
     * no longer be vouched for, so the store turns read-only rather than carrying on as if healthy.
     */
    private fun onRepositoryError(thread: Thread, error: Throwable) {
        val suspect = runCatching {
            index.read().filterNot(::isQuarantined).maxByOrNull { archive.documentBytes(it) }
        }.getOrNull()
        record(
            CanvasStorageFault.Kind.SAVE_FAILED,
            suspect,
            suspect?.let { runCatching { archive.documentBytes(it) }.getOrNull() },
            "The notebook repository failed on ${thread.name}; the largest document is named. " +
                "Changes since this time may not be on disk",
            error,
        )
        degrade("${error::class.java.simpleName} on ${thread.name}")
    }

    private fun degrade(reason: String) {
        synchronized(this) {
            if (readOnlyReason != null) return
            readOnlyReason = reason
        }
        record(
            CanvasStorageFault.Kind.READ_ONLY,
            null,
            null,
            "The notebook store hit $reason and is read-only until the app restarts; changes since this time are not saved",
        )
    }

    private fun documentKeyOf(storageKey: StorageKey): String? {
        val name = storageKey.parts.firstOrNull() ?: return null
        storageNames[name]?.let { return it }
        runCatching { index.read() }.getOrNull().orEmpty().forEach { key ->
            storageNames.getOrPut(archive.storageName(key)) { key }
        }
        return storageNames[name]
    }

    private fun record(kind: CanvasStorageFault.Kind, key: String?, bytes: Long?, message: String, error: Throwable? = null) {
        faults.record(
            CanvasStorageFault(
                kind = kind,
                documentId = key,
                canvasId = key?.let(canvases::get),
                documentBytes = bytes,
                message = message,
                atEpochMs = System.currentTimeMillis(),
            ),
            error,
        )
    }

    override fun close() {
        AutomergeFaultGuard.unregister(guard)
    }

    private fun mb(bytes: Long): String = "%.1f MB".format(bytes / (KIB * KIB).toDouble())

    companion object {
        private const val WARNED = 1
        private const val OVER = 2
        private const val KIB = 1024L
        private const val PERCENT = 100

        /** Past this many budgets, a document that could not be moved is not opened at all. */
        private const val QUARANTINE_FACTOR = 4

        /**
         * The repository's document pool (RepoRuntime runs document work on a work-stealing pool
         * whose threads carry default names). Read reflectively; app/proguard-rules.pro keeps
         * `org.automerge.**`. Null if this version of the library is laid out differently, and
         * then only the named `automerge-*` threads are recognised.
         */
        fun documentPoolOf(repo: Repo): ExecutorService? = runCatching {
            val runtime = Repo::class.java.getDeclaredField("runtime").apply { isAccessible = true }.get(repo)
            runtime.javaClass.getDeclaredField("documentExecutor").apply { isAccessible = true }.get(runtime) as ExecutorService
        }.getOrNull()
    }
}
