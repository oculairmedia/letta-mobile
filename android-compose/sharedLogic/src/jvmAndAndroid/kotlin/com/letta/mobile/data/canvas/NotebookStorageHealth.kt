package com.letta.mobile.data.canvas

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.ForkJoinWorkerThread
import org.automerge.repo.Repo
import org.automerge.repo.Storage
import org.automerge.repo.StorageKey

/**
 * Keeps one notebook repository's storage healthy and its failures visible: the history budget
 * at open, storage failures and repository-thread errors as [CanvasStorageFault]s, the documents
 * set aside because they could not be opened safely, and the read-only state the store falls back
 * to when it cannot vouch for itself.
 */
internal class NotebookStorageHealth(
    private val storageRoot: Path,
    private val budget: NotebookHistoryBudget,
    private val documentKeys: () -> List<String>,
    /** The repository's cross-process canvas lock; restarts and recovery run under it. */
    private val lock: (() -> Unit) -> Unit,
    private val archive: NotebookHistoryArchive = NotebookHistoryArchive(storageRoot),
) : AutoCloseable {
    val faults = NotebookStorageFaults()
    private val quarantined = ConcurrentHashMap.newKeySet<String>()
    private val canvases = ConcurrentHashMap<String, CanvasId>()
    private val budgetLevel = ConcurrentHashMap<String, Int>()
    private val storageNames = ConcurrentHashMap<String, String>()
    private val layoutRefused = ConcurrentHashMap.newKeySet<String>()
    private val syncMarker = storageRoot.resolve(SYNC_MARKER)

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
     * Recover interrupted restarts and apply the history budget to every indexed document, on a
     * background thread: reading the index and walking each document's files is disk I/O, and
     * Android builds the store on its main thread. [awaitPrepared] holds back every open until it
     * is done, so the repository never loads a document mid-restart.
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

    /** Whether this repository was ever handed to peer sync, by this process or an earlier one. */
    fun everSynced(): Boolean = Files.exists(syncMarker, NOFOLLOW_LINKS)

    /**
     * Record, durably, that this repository syncs with peers. From then on no store restarts its
     * documents, whatever its budget asks for: a peer may hold any document's old history.
     */
    fun markSynced() {
        lock {
            if (!everSynced()) Files.writeString(syncMarker, "Documents here sync with peers; histories are never restarted.\n", UTF_8)
        }
    }

    private fun scan() {
        val keys = try {
            documentKeys()
        } catch (error: Exception) {
            record(CanvasStorageFault.Kind.LOAD_FAILED, null, null, "Notebook index unreadable", error)
            return
        }
        for (key in keys) {
            try {
                lock {
                    archive.recoverInterrupted(key)
                    // Measured under the lock: no other store can restart it between here and the move.
                    val bytes = archive.documentBytes(key)
                    when {
                        bytes > budget.maxDocumentBytes && mayRestart() -> restart(key, bytes)
                        bytes > budget.maxDocumentBytes -> nearBudget(key, bytes, OVER)
                        bytes >= budget.warnBytes -> nearBudget(key, bytes, WARNED)
                    }
                }
            } catch (error: Exception) {
                record(CanvasStorageFault.Kind.LOAD_FAILED, key, null, "Notebook storage could not be inspected", error)
            }
        }
    }

    private fun mayRestart(): Boolean = budget.compactOversized && !everSynced()

    private fun restart(key: String, bytes: Long) {
        try {
            compact(key, bytes)
        } catch (error: Throwable) {
            // Put back whatever the failed restart moved; the document stays as it was.
            runCatching { archive.recoverInterrupted(key) }.exceptionOrNull()?.let(error::addSuppressed)
            // A document too large to restart is too large for the repository to save: set it
            // aside so this process does not die on it. Its files stay where they are.
            val tooLarge = bytes > budget.maxDocumentBytes * QUARANTINE_FACTOR
            if (tooLarge) quarantined += key
            record(
                if (tooLarge) CanvasStorageFault.Kind.QUARANTINED else CanvasStorageFault.Kind.LOAD_FAILED,
                key,
                bytes,
                if (tooLarge) "Notebook history of ${mb(bytes)} could not be archived; the document is set aside unopened"
                else "Notebook history of ${mb(bytes)} could not be archived; opening it as it is",
                error,
            )
        }
    }

    private fun compact(key: String, bytes: Long) {
        val result = archive.compact(key, System.currentTimeMillis())
        budgetLevel.remove(key)
        record(
            CanvasStorageFault.Kind.COMPACTED,
            key,
            result.bytesAfter,
            "History of ${mb(bytes)} passed the ${mb(budget.maxDocumentBytes)} budget; archived to " +
                "${result.archive.fileName} and continued from ${result.bytesAfter / KIB} KB",
        )
    }

    private fun nearBudget(key: String, bytes: Long, level: Int) {
        if ((budgetLevel[key] ?: 0) >= level) return
        budgetLevel[key] = level
        val note = when {
            level != OVER -> "${(budget.warnFraction * PERCENT).toInt()}% of it"
            mayRestart() -> "it will be archived and restarted the next time the notebook store opens"
            budget.compactOversized -> "this repository has synced with peers, so it is not restarted " +
                "(a fresh history would conflict with theirs)"
            else -> "this repository does or may sync with peers, so it is not restarted automatically"
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

    fun observe(storage: Storage): Storage = ObservedStorage(storage, ::onStorageFailure)

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
            documentKeys().filterNot(::isQuarantined).maxByOrNull { archive.documentBytes(it) }
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
        runCatching { documentKeys() }.getOrNull().orEmpty().forEach { key ->
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

        /** Past this many budgets, a document that could not be restarted is not opened at all. */
        private const val QUARANTINE_FACTOR = 4

        /** Present once the repository was handed to peer sync; never removed. */
        const val SYNC_MARKER = "notebook-synced"

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
