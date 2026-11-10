package com.letta.mobile.data.meridian.endpoint

import com.letta.mobile.data.transport.appserver.AppServerReceivedFrame
import com.letta.mobile.data.transport.appserver.AppServerRuntimeScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

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
 * The shell calls running `meridian` in the runtimes this host relays.
 *
 * Fed with every frame the wrapper receives from the App Server ([observe]). Each call gets one
 * capability token: letta-code's PreToolUse hook asks for it by the call's id
 * ([issueToken], once per call) and hands it to the shell as `LETTA_TOOL_TOKEN`. A CLI request is
 * bound by that token alone ([claim]); the `LETTA_AGENT_ID` / `LETTA_CONVERSATION_ID` the shell sends
 * play no part, so overriding them cannot reach another conversation's call.
 *
 * An entry, and its token, ends with its call (`client_tool_end`, the tool return, or the turn's
 * end), and in any case after [maxAgeMs], so a lost end frame cannot keep a token alive. [nowMs] is
 * a monotonic clock; [newToken] makes an unguessable token (the wrapper's SecureRandom).
 *
 * Every step goes to [log] as one `[meridian-tools]` line (a call seen starting or ending, a token
 * issued, refused or claimed), never the token itself. A refusal carries what this host has seen so
 * far, so "the frames never arrive" can be told from "they arrive but none is a `meridian` shell call".
 */
class MeridianLiveCalls(
    private val nowMs: () -> Long,
    private val newToken: () -> String,
    private val maxAgeMs: Long = DEFAULT_MAX_AGE_MS,
    private val maxTracked: Int = DEFAULT_MAX_TRACKED,
    private val log: (String) -> Unit = {},
) {
    private data class Entry(
        val scope: AppServerRuntimeScope,
        val startedAtMs: Long,
        val invocations: Int,
        val token: String? = null,
    )

    private val mutex = Mutex()
    private val calls = LinkedHashMap<String, Entry>()

    /** Bumped on every start, so [issueToken] can wait for a start frame still in flight. */
    private val starts = MutableStateFlow(0L)

    /** What this host has seen since it started, for the refusal logs. */
    private val seen = MeridianFramesSeen()

    suspend fun observe(received: AppServerReceivedFrame) {
        val startedTool = MeridianShellCallSignal.startedToolName(received)
        val signal = MeridianShellCallSignal.of(received)
        mutex.withLock {
            seen.frame(startedTool)
            when (signal) {
                is MeridianShellCallSignal.Started -> start(signal).also { starts.value += 1 }
                is MeridianShellCallSignal.Ended -> end(signal.toolCallId)
                is MeridianShellCallSignal.TurnEnded -> calls.entries.removeAll { it.value.scope.sameRuntime(signal.scope) }
                null -> Unit
            }
        }
    }

    /**
     * The token of the live `meridian` call [toolCallId], made on the first request and never handed
     * out again, or null when the call is unknown or its token was already issued. letta-code runs
     * the hook right after it emits `client_tool_start`, so the frame may still be on its way: this
     * waits up to [waitMs] for it.
     */
    suspend fun issueToken(toolCallId: String, waitMs: Long = DEFAULT_ISSUE_WAIT_MS): String? {
        withTimeoutOrNull(waitMs) { starts.first { isLive(toolCallId) } } ?: return refused(toolCallId, "call_start_not_seen")
        return mutex.withLock {
            expire()
            val entry = calls[toolCallId]?.takeIf { it.token == null } ?: return@withLock refused(toolCallId, "token_already_issued")
            log("$TAG token.issued toolCallId=$toolCallId conversationId=${entry.scope.conversationId}")
            newToken().also { calls[toolCallId] = entry.copy(token = it) }
        }
    }

    /** Logs why [toolCallId] got no token, with what this host has seen so far; always null. */
    private fun refused(toolCallId: String, reason: String): String? {
        log(
            "$TAG token.refused toolCallId=$toolCallId reason=$reason framesSeen=${seen.frames} " +
                "toolStartsSeen=${seen.toolStarts} meridianStartsSeen=${starts.value} lastToolStarted=${seen.lastTool ?: "none"}",
        )
        return null
    }

    /** Binds one CLI invocation to the live call that owns [token], counting it, or null when none does. */
    suspend fun claim(token: String): MeridianLiveCall? = mutex.withLock {
        expire()
        val match = calls.entries.firstOrNull { (_, entry) -> entry.token?.let { constantTimeEquals(it, token) } == true }
        if (match == null) {
            log("$TAG claim.refused reason=no_live_call_for_token tracked=${calls.size}")
            return@withLock null
        }
        val next = match.value.copy(invocations = match.value.invocations + 1)
        calls[match.key] = next
        log("$TAG claim.bound toolCallId=${match.key} invocation=${next.invocations}")
        MeridianLiveCall(match.key, next.scope, next.invocations)
    }

    private suspend fun isLive(toolCallId: String): Boolean = mutex.withLock {
        expire()
        toolCallId in calls
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
        log("$TAG call.started toolCallId=${signal.toolCallId} conversationId=${signal.scope.conversationId} tracked=${calls.size}")
    }

    /** Forgets [toolCallId]; only a call this host was tracking is worth a log line. */
    private fun end(toolCallId: String) {
        if (calls.remove(toolCallId) != null) log("$TAG call.ended toolCallId=$toolCallId")
    }

    private fun expire() {
        val now = nowMs()
        calls.entries.removeAll { now - it.value.startedAtMs >= maxAgeMs }
    }

    private fun AppServerRuntimeScope.sameRuntime(other: AppServerRuntimeScope): Boolean =
        agentId == other.agentId && conversationId == other.conversationId

    companion object {
        private const val TAG = "[meridian-tools]"

        /** Longer than letta-code's longest shell timeout (10 minutes), so a slow call stays bound. */
        const val DEFAULT_MAX_AGE_MS: Long = 15 * 60 * 1000L
        const val DEFAULT_MAX_TRACKED: Int = 4096

        /** How long [issueToken] waits for the call's start frame; under the hook's 5 s timeout. */
        const val DEFAULT_ISSUE_WAIT_MS: Long = 2_000L
    }
}

/** Counts of what reached [MeridianLiveCalls.observe]: every frame, and every tool start with the last tool started. */
private class MeridianFramesSeen {
    var frames: Long = 0
        private set
    var toolStarts: Long = 0
        private set
    var lastTool: String? = null
        private set

    /** One more frame; [startedTool] is the tool it starts, when it is a tool start. */
    fun frame(startedTool: String?) {
        frames += 1
        if (startedTool == null) return
        toolStarts += 1
        lastTool = startedTool
    }
}
