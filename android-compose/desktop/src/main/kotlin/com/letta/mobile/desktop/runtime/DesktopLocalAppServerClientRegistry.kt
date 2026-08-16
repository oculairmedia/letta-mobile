package com.letta.mobile.desktop.runtime

import com.letta.mobile.data.transport.appserver.AppServerClient
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Shares the single child-owned App Server session with LOCAL repository adapters. */
internal object DesktopLocalAppServerClientRegistry {
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

    suspend fun awaitClientAfter(generation: Long): AppServerClient =
        entry.first { it.generation > generation && it.client != null }.client!!
}

internal class DesktopLocalAppServerClientBinding(
    private val clientProvider: suspend () -> AppServerClient,
) {
    private val mutex = Mutex()
    private var pinned: AppServerClient? = null

    suspend fun client(): AppServerClient = pinned ?: mutex.withLock {
        pinned ?: clientProvider().also { pinned = it }
    }
}
