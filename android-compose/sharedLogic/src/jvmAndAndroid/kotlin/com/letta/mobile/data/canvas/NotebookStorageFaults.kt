package com.letta.mobile.data.canvas

import java.util.Optional
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import org.automerge.repo.Storage
import org.automerge.repo.StorageKey

/**
 * A [Storage] that reports failures. The repository runs storage on its own executors and only
 * logs a failure there, so without this a save that never reached disk would go unnoticed.
 */
internal class ObservedStorage(
    private val delegate: Storage,
    private val onFailure: (operation: String, key: StorageKey, bytes: Long?, error: Throwable) -> Unit,
) : Storage {
    override fun load(key: StorageKey): CompletableFuture<Optional<ByteArray>> =
        observe("load", key, null) { delegate.load(key) }

    override fun loadRange(prefix: StorageKey): CompletableFuture<MutableMap<StorageKey, ByteArray>> =
        observe("loadRange", prefix, null) { delegate.loadRange(prefix) }

    override fun put(key: StorageKey, value: ByteArray): CompletableFuture<Void> =
        observe("put", key, value.size.toLong()) { delegate.put(key, value) }

    override fun delete(key: StorageKey): CompletableFuture<Void> =
        observe("delete", key, null) { delegate.delete(key) }

    private fun <T> observe(
        operation: String,
        key: StorageKey,
        bytes: Long?,
        action: () -> CompletableFuture<T>,
    ): CompletableFuture<T> {
        val future = try {
            action()
        } catch (error: Throwable) {
            CompletableFuture<T>().apply { completeExceptionally(error) }
        }
        return future.whenComplete { _, error ->
            if (error != null) runCatching { onFailure(operation, key, bytes, error) }
        }
    }
}

/**
 * Thrown by a notebook write the store refuses: the store is read-only after an error it cannot
 * vouch for having recovered from, or the document's board layout is newer than this build
 * understands. The refusal is already recorded as a [CanvasStorageFault.Kind.READ_ONLY] fault.
 */
class NotebookReadOnlyException(message: String) : IllegalStateException(message)

/**
 * Keeps an [Error] thrown on an Automerge repository's own threads (an [OutOfMemoryError] copying
 * a huge snapshot, say) from killing the process. The repository catches [Exception] but not
 * [Error], and an uncaught throwable on any thread ends an Android process.
 *
 * Only [Error]s are absorbed, and only on a thread an open store claims as its repository's (see
 * [Listener.owns]) or, failing that, when the error was raised in `org.automerge` code itself. An
 * [Exception] (an app bug inside a `withDocument` block, a sync-protocol fault), anything on the
 * main thread, and anything on another thread go to the previous handler unchanged.
 */
internal object AutomergeFaultGuard {
    /** One open store: which threads are its repository's, and what to do with an absorbed error. */
    class Listener(
        val owns: (Thread) -> Boolean,
        val onError: (Thread, Throwable) -> Unit,
    )

    private val listeners = CopyOnWriteArrayList<Listener>()
    private var installed = false

    @Synchronized
    fun register(listener: Listener) {
        listeners += listener
        if (installed) return
        installed = true
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            val claimants = claimants(thread, error)
            if (claimants.isNotEmpty()) {
                claimants.forEach { runCatching { it.onError(thread, error) } }
            } else {
                previous?.uncaughtException(thread, error) ?: run {
                    System.err.print("Exception in thread \"${thread.name}\" ")
                    error.printStackTrace()
                }
            }
        }
    }

    fun unregister(listener: Listener) {
        listeners -= listener
    }

    /** Whether [error] on [thread] is a repository's to absorb. */
    fun handles(thread: Thread, error: Throwable): Boolean = claimants(thread, error).isNotEmpty()

    private fun claimants(thread: Thread, error: Throwable): List<Listener> {
        if (error !is Error || thread.name == "main") return emptyList()
        val owners = listeners.filter { runCatching { it.owns(thread) }.getOrDefault(false) }
        if (owners.isNotEmpty()) return owners
        val raisedInAutomerge = error.stackTrace.firstOrNull()?.className?.startsWith("org.automerge.") == true
        return if (raisedInAutomerge) listeners.toList() else emptyList()
    }

    /** RepoRuntime names its hub, IO and tick executors' threads `automerge-*`. */
    fun isRepositoryThreadName(name: String): Boolean = name.startsWith("automerge-")
}
