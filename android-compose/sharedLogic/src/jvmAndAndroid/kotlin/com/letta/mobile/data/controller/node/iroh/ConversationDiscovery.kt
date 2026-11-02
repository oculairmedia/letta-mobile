package com.letta.mobile.data.controller.node.iroh

import com.letta.mobile.data.controller.node.listcache.HeldListCache
import com.letta.mobile.data.transport.appserver.AppServerClient
import com.letta.mobile.data.transport.appserver.AppServerCommand
import com.letta.mobile.util.Telemetry
import com.letta.mobile.util.attr
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** Primes records created by other local-backend processes before either kind of held list is served. */
internal class ConversationDiscovery(
    private val directory: File,
    val heldList: HeldListCache?,
    private val clock: () -> Long = { System.nanoTime() / NANOS_PER_MILLI },
) {
    private val scanning = Mutex()
    private val known = mutableSetOf<String>()
    private val pending = ArrayDeque<String>()
    private var lastScanAt: Long? = null
    private var directoryMtime: Long? = null
    private var directoryReadAt: Long? = null

    /** Remember raw server rows, prior to the caller's output scope and page filters. */
    fun remember(rows: JsonArray) {
        val ids = rows.mapNotNull { (it as? JsonObject)?.get("id") as? JsonPrimitive }
            .mapNotNull { it.contentOrNull }
        synchronized(known) { known.addAll(ids) }
    }

    suspend fun prime(client: AppServerClient, authenticated: Boolean) {
        if (!authenticated) return
        scanning.withLock {
            val now = clock()
            if (lastScanAt?.let { now - it < MIN_INTERVAL_MS } == true) return
            lastScanAt = now
            refreshPending(now)
            pending.removeAll { isKnown(it) }
            var failures = 0
            var timedOut = false
            repeat(minOf(MAX_PER_PASS, pending.size)) {
                val remaining = PASS_BUDGET_MS - (clock() - now)
                if (remaining <= 0) {
                    timedOut = true
                    return@repeat
                }
                val id = pending.removeFirst()
                if (isKnown(id)) return@repeat
                if (!primeOne(client, id, remaining)) failures++
            }
            if (failures > 0 || timedOut) {
                Telemetry.event(
                    "IrohNode", "conversation_discovery.retrieve_failed",
                    "count" attr failures, "timedOut" attr timedOut, level = Telemetry.Level.WARN,
                )
            }
        }
    }

    private suspend fun primeOne(client: AppServerClient, id: String, remaining: Long): Boolean {
        val loaded = try {
            retrieve(client, id, remaining)
        } catch (cancelled: CancellationException) {
            pending.addFirst(id)
            throw cancelled
        }
        if (loaded) {
            recordPrimed(id)
        } else {
            pending.addLast(id)
        }
        return loaded
    }

    private fun recordPrimed(id: String) {
        synchronized(known) { known.add(id) }
        heldList?.invalidate()
    }

    private fun refreshPending(now: Long) {
        if (!directory.isDirectory) {
            directoryMtime = null
            directoryReadAt = null
            pending.clear()
            return
        }
        val modified = directory.lastModified()
        if (directoryMtime == modified && directoryReadAt?.let { now - it < FULL_RECHECK_MS } == true) return
        val ids = readCandidateIds() ?: return
        pending.retainAll(ids)
        val queued = pending.toSet()
        ids.sorted().filterNot { it in queued || isKnown(it) }.forEach(pending::addLast)
        directoryMtime = modified
        directoryReadAt = now
    }

    private fun readCandidateIds(): Set<String>? {
        val names = directory.list() ?: run {
            Telemetry.event("IrohNode", "conversation_discovery.directory_unreadable", level = Telemetry.Level.WARN)
            return null
        }
        var malformed = 0
        val ids = names.mapNotNull { conversationId(it) { malformed++ } }.toSet()
        if (malformed > 0) {
            Telemetry.event(
                "IrohNode", "conversation_discovery.invalid_names",
                "count" attr malformed, level = Telemetry.Level.WARN,
            )
        }
        return ids
    }

    private fun isKnown(id: String): Boolean = synchronized(known) { id in known }

    private suspend fun retrieve(client: AppServerClient, id: String, remainingMs: Long): Boolean = try {
        NativeAdmin.require(client, NativeAdminOp.ConversationDiscovery) { c ->
            withTimeoutOrNull(remainingMs.milliseconds) { sendRetrieve(c, id) } ?: false
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        false
    }

    private suspend fun sendRetrieve(client: AppServerClient, id: String): Boolean = try {
        val response = client.conversationRetrieve(
            AppServerCommand.ConversationRetrieve(requestId = NativeAdmin.requestId(), conversationId = id),
        )
        response.success && (response.conversation?.get("id") as? JsonPrimitive)?.contentOrNull == id
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        false
    }

    private fun conversationId(name: String, onMalformed: () -> Unit): String? {
        val key = try {
            LocalBackendLayout.b64UrlDecode(name)
        } catch (_: IllegalArgumentException) {
            onMalformed()
            return null
        }
        if (LocalBackendLayout.b64UrlEncode(key) != name) {
            onMalformed()
            return null
        }
        if (!key.startsWith(KEY_PREFIX)) return null
        return key.removePrefix(KEY_PREFIX).takeIf { it.isNotEmpty() && it != LocalBackendLayout.DEFAULT_CONVERSATION_ID }
    }

    companion object {
        internal const val MAX_PER_PASS = 32
        internal const val MIN_INTERVAL_MS = 5_000L
        private const val PASS_BUDGET_MS = 1_500L
        private const val FULL_RECHECK_MS = 60_000L
        private const val NANOS_PER_MILLI = 1_000_000L
        private const val KEY_PREFIX = "conversation:"
    }
}
