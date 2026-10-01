package com.letta.mobile.data.canvas

import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import org.automerge.repo.Storage
import org.automerge.repo.StorageKey

/**
 * Keeps one notebook repository's storage healthy and its failures visible: the history budget
 * at open, storage failures and repository-thread errors as [CanvasStorageFault]s, and the
 * documents set aside because they could not be opened safely.
 */
internal class NotebookStorageHealth(
    storageRoot: Path,
    private val budget: NotebookHistoryBudget,
    private val documentKeys: () -> List<String>,
) : AutoCloseable {
    val faults = NotebookStorageFaults()
    private val archive = NotebookHistoryArchive(storageRoot)
    private val quarantined = ConcurrentHashMap.newKeySet<String>()
    private val canvases = ConcurrentHashMap<String, CanvasId>()
    private val budgetLevel = ConcurrentHashMap<String, Int>()
    private val storageNames = ConcurrentHashMap<String, String>()
    private val guard: (Thread, Throwable) -> Unit = ::onRepositoryError

    init {
        AutomergeFaultGuard.register(guard)
    }

    @Volatile
    private var preparing: Thread? = null

    /**
     * Apply the history budget to every indexed document. Sizes are checked here, which is cheap.
     * Restarting an over-budget document (seconds for tens of MB) runs on its own thread, and
     * [awaitPrepared] holds back every open until it is done, so the repository never loads a
     * document mid-restart. Android builds the store on its main thread, which must not wait.
     */
    fun prepare() {
        val keys = try {
            documentKeys()
        } catch (error: Exception) {
            record(CanvasStorageFault.Kind.LOAD_FAILED, null, null, "Notebook index unreadable", error)
            return
        }
        val oversized = keys.mapNotNull { key -> sizeChecked(key)?.takeIf { it > budget.maxDocumentBytes }?.let { key to it } }
        if (oversized.isEmpty()) return
        if (!budget.compactOversized) {
            oversized.forEach { (key, bytes) -> nearBudget(key, bytes, OVER) }
            return
        }
        preparing = Thread({ oversized.forEach { (key, bytes) -> restart(key, bytes) } }, "notebook-history-compactor")
            .apply { isDaemon = true; start() }
    }

    /** Wait for [prepare]'s restarts; every repository open goes through here. */
    fun awaitPrepared() {
        preparing?.takeIf { it !== Thread.currentThread() }?.join()
    }

    /** Finish an interrupted restart, warn near the budget, and return the size; null if unreadable. */
    private fun sizeChecked(key: String): Long? = try {
        archive.recoverInterrupted(key)
        archive.documentBytes(key).also { bytes ->
            if (bytes >= budget.warnBytes && bytes <= budget.maxDocumentBytes) nearBudget(key, bytes, WARNED)
        }
    } catch (error: Exception) {
        record(CanvasStorageFault.Kind.LOAD_FAILED, key, null, "Notebook storage could not be inspected", error)
        null
    }

    private fun restart(key: String, bytes: Long) {
        try {
            compact(key, bytes)
        } catch (error: Throwable) {
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
        val note = if (level == OVER) {
            if (budget.compactOversized) "it will be archived and restarted the next time the notebook store opens"
            else "this repository syncs with peers, so it is not restarted automatically"
        } else "${(budget.warnFraction * PERCENT).toInt()}% of it"
        record(
            CanvasStorageFault.Kind.NEAR_BUDGET,
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
     * An error escaped the repository's own threads, and [AutomergeFaultGuard] kept it from ending
     * the process. The repository does not say which document it was saving; the largest one is
     * named, as an oversized snapshot is what such failures have been.
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

    private companion object {
        const val WARNED = 1
        const val OVER = 2
        const val KIB = 1024L
        const val PERCENT = 100
        /** Past this many budgets, a document that could not be restarted is not opened at all. */
        const val QUARANTINE_FACTOR = 4
    }
}
