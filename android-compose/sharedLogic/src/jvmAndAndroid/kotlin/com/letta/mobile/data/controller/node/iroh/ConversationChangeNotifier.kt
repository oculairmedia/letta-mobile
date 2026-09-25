package com.letta.mobile.data.controller.node.iroh

import com.letta.mobile.util.Telemetry
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** What happened to a conversation, as the `reason` of a `conversation_updated` frame. */
enum class ConversationChangeKind(val wire: String) {
    Created("created"),
    Updated("updated"),
    Archived("archived"),
    Restored("restored"),
}

/** Where coalesced `conversation_updated` frames go; the Iroh endpoint writes them to its connections. */
fun interface ConversationChangeTarget {
    suspend fun broadcast(frame: String)
}

/**
 * Tells every connected client that a conversation changed, so conversation lists refresh without a
 * restart (letta-mobile-lks7m).
 *
 * The App Server has no conversation-change event and the node's live-turn fan-out reaches only the
 * connections VIEWING a conversation, so a chat started on one device was invisible to the others.
 * Every conversation write from an Iroh client passes through Meridian's `conversation.*` handlers,
 * which call [notify]. Changes are coalesced per conversation over [windowMs], then sent as a
 * `conversation_updated` frame (`ServerFrame.ConversationUpdated`). A client that was offline misses
 * the push and re-reads its list on reconnect instead, so this is an optimisation, never the source
 * of truth. The sibling of [AgentChangeNotifier].
 */
class ConversationChangeNotifier(
    private val scope: CoroutineScope,
    private val windowMs: Long = DEFAULT_WINDOW_MS,
    private val clock: () -> Instant = Instant::now,
) {
    private data class Pending(val agentId: String?, val kind: ConversationChangeKind)

    private val mutex = Mutex()
    private val pending = LinkedHashMap<String, Pending>()
    private var flushJob: Job? = null

    @Volatile private var target: ConversationChangeTarget? = null

    fun attach(target: ConversationChangeTarget) {
        this.target = target
    }

    suspend fun notify(conversationId: String, agentId: String?, kind: ConversationChangeKind) {
        if (conversationId.isBlank()) return
        mutex.withLock {
            val previous = pending[conversationId]
            pending[conversationId] = Pending(
                agentId = agentId?.takeIf { it.isNotBlank() } ?: previous?.agentId,
                kind = merge(previous?.kind, kind),
            )
            if (flushJob == null) {
                flushJob = scope.launch {
                    delay(windowMs)
                    flush()
                }
            }
        }
    }

    private suspend fun flush() {
        val batch = mutex.withLock {
            flushJob = null
            pending.toList().also { pending.clear() }
        }
        val sink = target
        if (sink == null) {
            Telemetry.event("ConversationChangeNotifier", "broadcast.no_target", "conversations" to batch.size)
            return
        }
        batch.forEach { (conversationId, change) -> sink.broadcast(frame(conversationId, change)) }
    }

    private fun frame(conversationId: String, change: Pending): String {
        val at = clock().toString()
        return buildJsonObject {
            put("v", 1)
            put("type", FRAME_TYPE)
            put("id", "conversation-updated-${UUID.randomUUID()}")
            put("ts", at)
            put("conversation_id", conversationId)
            change.agentId?.let { put("agent_id", it) }
            put("reason", change.kind.wire)
            put("at", at)
        }.toString()
    }

    companion object {
        const val FRAME_TYPE: String = "conversation_updated"
        const val DEFAULT_WINDOW_MS: Long = 250

        /**
         * The net change within one window: a creation stays a creation whatever follows (clients
         * have not seen it yet, and they refetch it either way); otherwise the latest change wins.
         */
        internal fun merge(previous: ConversationChangeKind?, next: ConversationChangeKind): ConversationChangeKind =
            if (previous == ConversationChangeKind.Created) ConversationChangeKind.Created else next
    }
}
