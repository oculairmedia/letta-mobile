package com.letta.mobile.data.controller.node.iroh.discovery

import com.letta.mobile.data.controller.node.listcache.HeldListCache
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

/**
 * Primes records created by other local-backend processes before either kind of held list is served.
 * Every record is primed, but [onPrimed] hears only of ids that appeared after the first directory
 * read: what was already on disk when this host started (a wrapper restart) is not news to clients.
 */
internal class ConversationDiscovery(
    private val directory: File,
    val heldList: HeldListCache?,
    private val decodeId: (String) -> String?,
    private val onPrimed: suspend (String, String?) -> Unit = { _, _ -> },
    private val clock: () -> Long = { System.nanoTime() / NANOS_PER_MILLI },
) {
    private val scanning = Mutex()
    private val known = mutableSetOf<String>()
    private val pending = ArrayDeque<String>()
    /** Ids on disk at the first successful directory read; null until that read. */
    private var baseline: Set<String>? = null
    private var lastScanAt: Long? = null
    private var directoryMtime: Long? = null
    private var directoryReadAt: Long? = null

    /** Remember raw server rows, prior to the caller's output scope and page filters. */
    fun remember(rows: JsonArray) {
        val ids = rows.mapNotNull { (it as? JsonObject)?.get("id") as? JsonPrimitive }
            .mapNotNull { it.contentOrNull }
        synchronized(known) { known.addAll(ids) }
    }

    suspend fun prime(
        authenticated: Boolean,
        warn: (String, Int, Boolean) -> Unit,
        retrieve: suspend (String, Long) -> Boolean,
    ) {
        if (!authenticated) return
        scanning.withLock {
            val now = clock()
            if (lastScanAt?.let { now - it < MIN_INTERVAL_MS } == true) return
            lastScanAt = now
            refreshPending(now, warn)
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
                if (!primeOne(id, remaining, retrieve)) failures++
            }
            if (failures > 0 || timedOut) {
                warn("conversation_discovery.retrieve_failed", failures, timedOut)
            }
        }
    }

    private suspend fun primeOne(id: String, remaining: Long, retrieve: suspend (String, Long) -> Boolean): Boolean {
        val loaded = try {
            withTimeoutOrNull((remaining + RETRIEVE_GRACE_MS).milliseconds) { retrieve(id, remaining) } ?: false
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

    private suspend fun recordPrimed(id: String) {
        synchronized(known) { known.add(id) }
        heldList?.invalidate()
        if (baseline?.contains(id) != true) onPrimed(id, null)
    }

    private fun refreshPending(now: Long, warn: (String, Int, Boolean) -> Unit) {
        if (!directory.isDirectory) {
            directoryMtime = null
            directoryReadAt = null
            pending.clear()
            return
        }
        val modified = directory.lastModified()
        if (directoryMtime == modified && directoryReadAt?.let { now - it < FULL_RECHECK_MS } == true) return
        val ids = readCandidateIds(warn) ?: return
        if (baseline == null) baseline = ids
        pending.retainAll(ids)
        val queued = pending.toSet()
        ids.sorted().filterNot { it in queued || isKnown(it) }.forEach(pending::addLast)
        directoryMtime = modified
        directoryReadAt = now
    }

    private fun readCandidateIds(warn: (String, Int, Boolean) -> Unit): Set<String>? {
        val names = directory.list() ?: run {
            warn("conversation_discovery.directory_unreadable", 0, false)
            return null
        }
        var malformed = 0
        val ids = names.mapNotNull { conversationId(it) { malformed++ } }.toSet()
        if (malformed > 0) {
            warn("conversation_discovery.invalid_names", malformed, false)
        }
        return ids
    }

    private fun isKnown(id: String): Boolean = synchronized(known) { id in known }

    private fun conversationId(name: String, onMalformed: () -> Unit): String? = try {
        decodeId(name)
    } catch (_: IllegalArgumentException) {
        onMalformed()
        null
    }

    companion object {
        internal const val MAX_PER_PASS = 32
        internal const val MIN_INTERVAL_MS = 5_000L
        private const val PASS_BUDGET_MS = 1_500L
        private const val RETRIEVE_GRACE_MS = 100L
        private const val FULL_RECHECK_MS = 60_000L
        private const val NANOS_PER_MILLI = 1_000_000L
    }
}
