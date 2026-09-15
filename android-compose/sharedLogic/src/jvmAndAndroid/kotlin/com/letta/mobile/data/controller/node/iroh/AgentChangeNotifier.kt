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

/** What happened to an agent, as the `reason` of an `agent_updated` frame. */
enum class AgentChangeKind(val wire: String) {
    Created("created"),
    Updated("updated"),
    Deleted("deleted"),
}

/** Where coalesced `agent_updated` frames go; the Iroh endpoint writes them to its connections. */
fun interface AgentChangeTarget {
    suspend fun broadcast(frame: String)
}

/**
 * Tells every connected client that an agent changed, so rosters refresh without a restart.
 *
 * The App Server has no agent-change event, but every agent write from an Iroh client passes
 * through Meridian's `agent.*` handlers, which call [notify]. Changes are coalesced per agent over
 * [windowMs] so a bulk operation sends one frame per agent, not one per write, then sent as the
 * `agent_updated` frame clients already understand (`ServerFrame.AgentUpdated`: `reason` is
 * `deleted` to drop the agent, anything else to refetch it). A client that was offline misses the
 * push and refreshes on reconnect instead, so this is an optimisation, never the source of truth.
 */
class AgentChangeNotifier(
    private val scope: CoroutineScope,
    private val windowMs: Long = DEFAULT_WINDOW_MS,
    private val clock: () -> Instant = Instant::now,
) {
    private val mutex = Mutex()
    private val pending = LinkedHashMap<String, AgentChangeKind>()
    private var flushJob: Job? = null

    @Volatile private var target: AgentChangeTarget? = null

    fun attach(target: AgentChangeTarget) {
        this.target = target
    }

    suspend fun notify(agentId: String, kind: AgentChangeKind) {
        if (agentId.isBlank()) return
        mutex.withLock {
            pending[agentId] = merge(pending[agentId], kind)
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
            Telemetry.event("AgentChangeNotifier", "broadcast.no_target", "agents" to batch.size)
            return
        }
        batch.forEach { (agentId, kind) -> sink.broadcast(frame(agentId, kind)) }
    }

    private fun frame(agentId: String, kind: AgentChangeKind): String {
        val at = clock().toString()
        return buildJsonObject {
            put("v", 1)
            put("type", FRAME_TYPE)
            put("id", "agent-updated-${UUID.randomUUID()}")
            put("ts", at)
            put("agent_id", agentId)
            put("reason", kind.wire)
            put("at", at)
        }.toString()
    }

    companion object {
        const val FRAME_TYPE: String = "agent_updated"
        const val DEFAULT_WINDOW_MS: Long = 250

        /**
         * The net change within one window: a delete always wins; created-then-updated is still a
         * creation; deleted-then-created is an agent that exists again, so clients refetch it.
         */
        internal fun merge(previous: AgentChangeKind?, next: AgentChangeKind): AgentChangeKind = when {
            next == AgentChangeKind.Deleted -> AgentChangeKind.Deleted
            previous == AgentChangeKind.Created && next == AgentChangeKind.Updated -> AgentChangeKind.Created
            previous == AgentChangeKind.Deleted && next == AgentChangeKind.Created -> AgentChangeKind.Updated
            else -> next
        }
    }
}
