package com.letta.mobile.data.meridian

import com.letta.mobile.data.controller.extras.ExternalToolCaller
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Whether a tool-running command may run now. */
sealed interface MeridianAdmission {
    data object Admitted : MeridianAdmission

    data class Limited(val retryAfterMs: Long) : MeridianAdmission
}

/**
 * The per-conversation rate-limit hook (design: "Calls are rate-limited per conversation"). The
 * router asks it before every command that runs a tool; help, schemas and guides are free.
 */
fun interface MeridianRateLimiter {
    suspend fun admit(caller: ExternalToolCaller, command: String): MeridianAdmission

    companion object {
        /** Admits everything: a native tool call has no limit either. */
        val Unlimited: MeridianRateLimiter = MeridianRateLimiter { _, _ -> MeridianAdmission.Admitted }
    }
}

/**
 * At most [maxCalls] tool-running commands per conversation in any fixed window of [windowMs]
 * (keyed by agent when the call has no conversation). [nowMs] is a monotonic clock.
 */
class PerConversationRateLimiter(
    private val maxCalls: Int,
    private val windowMs: Long,
    private val nowMs: () -> Long,
) : MeridianRateLimiter {
    private val mutex = Mutex()
    private val windows = HashMap<String, Window>()

    private data class Window(val startMs: Long, val count: Int)

    override suspend fun admit(caller: ExternalToolCaller, command: String): MeridianAdmission = mutex.withLock {
        val now = nowMs()
        val key = caller.conversationId ?: caller.agentId ?: ANONYMOUS
        if (windows.size > MAX_TRACKED) windows.entries.removeAll { now - it.value.startMs >= windowMs }
        val current = windows[key]?.takeIf { now - it.startMs < windowMs } ?: Window(now, 0)
        if (current.count >= maxCalls) {
            MeridianAdmission.Limited(retryAfterMs = windowMs - (now - current.startMs))
        } else {
            windows[key] = current.copy(count = current.count + 1)
            MeridianAdmission.Admitted
        }
    }

    private companion object {
        const val ANONYMOUS = ""
        const val MAX_TRACKED = 1024
    }
}
