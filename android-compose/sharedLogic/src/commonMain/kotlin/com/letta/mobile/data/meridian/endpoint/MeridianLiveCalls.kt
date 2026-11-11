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
        val signal = MeridianShellCallSignal.of(received)
        mutex.withLock {
            seen.frame(received)
            when (signal) {
                is MeridianShellCallSignal.Started -> start(signal).also { starts.value += 1 }
                is MeridianShellCallSignal.Ended -> end(signal)
                is MeridianShellCallSignal.TurnEnded -> endTurn(signal.scope)
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
        // Logs why this call got no token, with what this host has seen so far; always null.
        fun refused(reason: TokenRefusal): String? {
            log("$TAG token.refused toolCallId=${toolCallId.logSafe()} reason=${reason.wire} ${seen.summary(starts.value)}")
            return null
        }
        withTimeoutOrNull(waitMs) { starts.first { isLive(toolCallId) } } ?: return refused(TokenRefusal.CALL_NOT_LIVE)
        return mutex.withLock {
            expire()
            val entry = calls[toolCallId] ?: return@withLock refused(TokenRefusal.CALL_ENDED)
            if (entry.token != null) return@withLock refused(TokenRefusal.TOKEN_ALREADY_ISSUED)
            log("$TAG token.issued toolCallId=${toolCallId.logSafe()} conversationId=${entry.scope.conversationId.logSafe()}")
            newToken().also { calls[toolCallId] = entry.copy(token = it) }
        }
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
        log("$TAG claim.bound toolCallId=${match.key.logSafe()} invocation=${next.invocations}")
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
        // A call announced again in the same runtime (the hook rewrote its command to carry the token)
        // is the same call: it keeps its token and its count, or the token just issued would be refused.
        val again = calls.remove(signal.toolCallId)?.takeIf { it.scope.sameRuntime(signal.scope) }
        calls[signal.toolCallId] = again ?: Entry(signal.scope, nowMs(), invocations = 0)
        val conversation = signal.scope.conversationId.logSafe()
        val event = if (again == null) "call.started" else "call.restarted"
        log("$TAG $event toolCallId=${signal.toolCallId.logSafe()} conversationId=$conversation tracked=${calls.size}")
    }

    /** Forgets the call [signal] ends; only a call this host was tracking is worth a log line. */
    private fun end(signal: MeridianShellCallSignal.Ended) {
        if (calls.remove(signal.toolCallId) != null) log("$TAG call.ended toolCallId=${signal.toolCallId.logSafe()}")
    }

    /** Forgets every call of the turn that ended in [scope], each with its own line (its end frame was lost). */
    private fun endTurn(scope: AppServerRuntimeScope) {
        calls.filterValues { it.scope.sameRuntime(scope) }.keys.toList().forEach { end(MeridianShellCallSignal.Ended(it)) }
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

    /** These counts as log fields, with [meridianStarts] (the `meridian` shell calls seen starting) among them. */
    fun summary(meridianStarts: Long): String =
        "framesSeen=$frames toolStartsSeen=$toolStarts meridianStartsSeen=$meridianStarts lastToolStarted=${lastTool?.logSafe() ?: "none"}"

    /** One more frame; when it is a tool start, that tool becomes the last one started. */
    fun frame(received: AppServerReceivedFrame) {
        frames += 1
        val startedTool = MeridianShellCallSignal.startedToolName(received) ?: return
        toolStarts += 1
        lastTool = startedTool
    }
}

/** Why a call got no token, as its `reason=` in the log. */
private enum class TokenRefusal(val wire: String) {
    /**
     * No such call is running (after the wait): the host never saw it start, or it had already
     * ended. `meridianStartsSeen` on the same line says whether any start has reached this host.
     */
    CALL_NOT_LIVE("call_not_live"),

    /** The call was running when the wait ended but was gone a moment later. */
    CALL_ENDED("call_ended"),

    /** The call's one token was handed out already. */
    TOKEN_ALREADY_ISSUED("token_already_issued"),
}

/**
 * This value as one log field: ids and tool names come from the App Server and the agent's shell,
 * so anything that could break the line or forge another (control characters, spaces) becomes `_`,
 * and an over-long value is cut.
 */
private fun String.logSafe(): String =
    take(MAX_LOGGED_FIELD).map { if (it.isLetterOrDigit() || it in "-_.:/") it else '_' }.joinToString("")

private const val MAX_LOGGED_FIELD = 96
