package com.letta.mobile.data.meridian.endpoint

import com.letta.mobile.data.transport.appserver.AppServerReceivedFrame
import com.letta.mobile.data.transport.appserver.AppServerRuntimeScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * A shell call running `meridian` right now: its [toolCallId] in the run, the [scope] the App Server
 * stamped on it, and how many `meridian` invocations it has made so far ([invocation], 1-based).
 */
data class MeridianLiveCall(
    val toolCallId: String,
    val scope: AppServerRuntimeScope,
    val invocation: Int,
)

/**
 * The shell calls running `meridian` in the runtimes this host relays (letta-mobile-jna0o.4).
 *
 * Fed with every frame the wrapper receives from the App Server ([observe]). A CLI request is bound
 * to one of these by conversation ([claim]): an agent can set `LETTA_CONVERSATION_ID` to anything,
 * but a forged scope only binds while that other conversation has a `meridian` shell call of its own
 * executing at that moment.
 *
 * An entry ends with its call (`client_tool_end`, the tool return, or the turn's end), and in any
 * case after [maxAgeMs], so a lost end frame cannot keep a conversation bindable. [nowMs] is a
 * monotonic clock.
 */
class MeridianLiveCalls(
    private val nowMs: () -> Long,
    private val maxAgeMs: Long = DEFAULT_MAX_AGE_MS,
    private val maxTracked: Int = DEFAULT_MAX_TRACKED,
) {
    private data class Entry(val scope: AppServerRuntimeScope, val startedAtMs: Long, val invocations: Int)

    private val mutex = Mutex()
    private val calls = LinkedHashMap<String, Entry>()

    suspend fun observe(received: AppServerReceivedFrame) {
        val signal = MeridianShellCallSignal.of(received) ?: return
        mutex.withLock {
            when (signal) {
                is MeridianShellCallSignal.Started -> start(signal)
                is MeridianShellCallSignal.Ended -> calls.remove(signal.toolCallId)
                is MeridianShellCallSignal.TurnEnded -> calls.entries.removeAll { it.value.scope.sameRuntime(signal.scope) }
            }
        }
    }

    /**
     * Binds one CLI invocation to the newest live `meridian` call in [conversationId], counting it,
     * or null when there is none. When [agentId] is given it must be the call's agent.
     */
    suspend fun claim(conversationId: String, agentId: String?): MeridianLiveCall? = mutex.withLock {
        expire()
        val match = calls.entries.lastOrNull { (_, entry) ->
            entry.scope.conversationId == conversationId && (agentId == null || entry.scope.agentId == agentId)
        } ?: return@withLock null
        val next = match.value.copy(invocations = match.value.invocations + 1)
        calls[match.key] = next
        MeridianLiveCall(match.key, next.scope, next.invocations)
    }

    /** How many calls are tracked now (diagnostics and tests). */
    suspend fun size(): Int = mutex.withLock {
        expire()
        calls.size
    }

    private fun start(signal: MeridianShellCallSignal.Started) {
        expire()
        if (calls.size >= maxTracked) calls.remove(calls.keys.first())
        calls.remove(signal.toolCallId)
        calls[signal.toolCallId] = Entry(signal.scope, nowMs(), invocations = 0)
    }

    private fun expire() {
        val now = nowMs()
        calls.entries.removeAll { now - it.value.startedAtMs >= maxAgeMs }
    }

    private fun AppServerRuntimeScope.sameRuntime(other: AppServerRuntimeScope): Boolean =
        agentId == other.agentId && conversationId == other.conversationId

    companion object {
        /** Longer than letta-code's longest shell timeout (10 minutes), so a slow call stays bound. */
        const val DEFAULT_MAX_AGE_MS: Long = 15 * 60 * 1000L
        const val DEFAULT_MAX_TRACKED: Int = 4096
    }
}
