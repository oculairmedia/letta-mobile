package com.letta.mobile.desktop.runtime

import com.letta.mobile.data.transport.appserver.AppServerClient
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withTimeout
import kotlin.time.Duration.Companion.milliseconds

/**
 * Shares the App Server session the desktop dials directly (a ws:// address, a developer-only setting) with the
 * repository adapters and workspace pages that read over it. The bundled runtime is never dialled directly: the shell
 * reaches it through the desktop's own node.
 *
 * Instance-based (not a process-global `object`) so tests can isolate generations
 * and desktop bootstrap can inject a shared singleton.
 */
class DesktopLocalAppServerClientRegistry {
    private data class Entry(val generation: Long, val token: String?, val client: AppServerClient?)

    private var nextGeneration = 0L
    private val entry = MutableStateFlow(Entry(0L, null, null))

    @Synchronized
    fun install(client: AppServerClient): AutoCloseable {
        nextGeneration += 1
        val token = UUID.randomUUID().toString()
        entry.value = Entry(nextGeneration, token, client)
        return AutoCloseable {
            synchronized(this) {
                val current = entry.value
                if (current.token == token) entry.value = current.copy(token = null, client = null)
            }
        }
    }

    @Synchronized
    fun generation(): Long = entry.value.generation

    suspend fun currentClient(timeoutMs: Long = 10_000L): AppServerClient = withTimeout(timeoutMs.milliseconds) {
        entry.first { it.client != null }.client!!
    }

    /** The installed client, or null right now: for callers that must not wait for one. */
    fun currentOrNull(): AppServerClient? = entry.value.client

    /** The installed client as it comes and goes (null between sessions). */
    val clients: Flow<AppServerClient?> = entry.map { it.client }.distinctUntilChanged()

    suspend fun awaitClientAfter(
        generation: Long,
        timeoutMs: Long = 10_000L,
    ): AppServerClient = withTimeout(timeoutMs.milliseconds) {
        entry.first { it.generation > generation && it.client != null }.client!!
    }

    companion object {
        /**
         * Process-wide registry used by desktop bootstrap wiring: empty unless a developer points the desktop at an
         * App Server directly, since every node (Iroh, or the desktop's own) relays its workspace commands instead.
         */
        val shared: DesktopLocalAppServerClientRegistry = DesktopLocalAppServerClientRegistry()
    }
}
