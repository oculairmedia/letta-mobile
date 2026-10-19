package com.letta.mobile.data.chat.runtime

import com.letta.mobile.util.Telemetry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Clock

/** One press of Stop for the run keyed [key] (a conversation, for every host so far). */
class ChatStopRequest<K : Any>(
    val key: K,
    /** Telemetry label for the transport the abort goes over. */
    val transport: String,
    /**
     * Asks the server to abort the run, true once it went out. Null when the transport cannot
     * abort, which falls straight back to the local clear.
     */
    val abort: (suspend () -> Boolean)?,
    /**
     * Suspends until the run's own terminal is observed, which settles the stop. Null when the host
     * reports the terminal itself through [ChatRunInterrupter.settle].
     */
    val awaitTerminal: (suspend () -> Unit)? = null,
)

/** What a host does at each step of a stop; the [ChatRunInterrupter] owns the state and the telemetry. */
fun interface ChatRunInterruptHost<K : Any> {
    /** The stop was accepted: show "stopping…". The run stays live until its terminal. */
    fun onCancelling(key: K) = Unit

    /** Drop the run's streaming UI locally, without a terminal. The server turn may still be running. */
    fun onForcedClear(key: K, reason: String)

    /** The run's terminal landed after the stop. */
    fun onSettled(key: K) = Unit

    /** The abort threw before it reached the server; the local clear follows. */
    fun onAbortFailed(key: K, failure: Exception) = Unit
}

/** The telemetry tag a host's interrupt events go under, and the attributes every event carries. */
class ChatRunInterruptTelemetry(
    val tag: String,
    val attributes: List<Pair<String, String?>> = emptyList(),
)

/**
 * The Stop button's lifecycle, one implementation for Android's
 * composer and desktop's controller (they used to carry a copy each).
 *
 * Stop does not settle the UI. It enters CANCELLING ([cancelling]) and asks the server to abort;
 * the run resolves to idle only when its own terminal lands ([ChatStopRequest.awaitTerminal], or the
 * host calling [settle]). The local clear ([ChatRunInterruptHost.onForcedClear]) is the escape hatch
 * for everything else: a second press while cancelling, a transport that cannot abort, an abort that
 * never went out, and a terminal that has not landed within [terminalTimeoutMs] of the press.
 *
 * Call it from the host's own scope (the main thread on both clients); its bookkeeping is not
 * synchronised.
 */
class ChatRunInterrupter<K : Any>(
    private val scope: CoroutineScope,
    private val host: ChatRunInterruptHost<K>,
    private val telemetry: ChatRunInterruptTelemetry,
    private val nowMs: () -> Long = { Clock.System.now().toEpochMilliseconds() },
    private val terminalTimeoutMs: Long = CANCEL_TERMINAL_TIMEOUT_MS,
) {
    private val _cancelling = MutableStateFlow<K?>(null)

    /** The run whose abort is in flight; null when no stop is pending. */
    val cancelling: StateFlow<K?> = _cancelling.asStateFlow()

    /** Bumped by every stop and every new turn, so a stale watcher or abort stands down. */
    private var attempt = 0
    private var requestedAtMs = 0L
    private var watchJob: Job? = null

    fun isCancelling(key: K): Boolean = _cancelling.value == key

    /** Stop pressed for [ChatStopRequest.key]. A second press while it is cancelling clears locally. */
    fun request(stop: ChatStopRequest<K>) {
        val key = stop.key
        if (isCancelling(key)) return forceClear(key, REASON_SECOND_PRESS)
        val abort = stop.abort ?: return forceClear(key, REASON_CANNOT_ABORT)
        val current = begin(stop)
        watchJob = scope.launch { watchForTerminal(key, current, stop.awaitTerminal) }
        scope.launch { dispatch(key, current, abort) }
    }

    /**
     * The host saw [key]'s terminal. True when that settled a pending stop (the host then clears
     * whatever it shows for the run); false when no stop was pending for it.
     */
    fun settle(key: K): Boolean {
        if (!isCancelling(key)) return false
        cancelWatcher()
        settled(key)
        return true
    }

    /** A new turn starts: whatever the last stop left behind must not touch it. */
    fun retire() {
        attempt += 1
        cancelWatcher()
        _cancelling.value = null
    }

    private fun begin(stop: ChatStopRequest<K>): Int {
        attempt += 1
        cancelWatcher()
        _cancelling.value = stop.key
        requestedAtMs = nowMs()
        host.onCancelling(stop.key)
        Telemetry.event(telemetry.tag, "interrupt.cancelRequested", *attributes(stop.key), "transport" to stop.transport)
        return attempt
    }

    private suspend fun dispatch(key: K, current: Int, abort: suspend () -> Boolean) {
        val outcome = try {
            AbortOutcome(dispatched = abort(), failure = null)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            AbortOutcome(dispatched = false, failure = failure)
        }
        if (!isCurrent(key, current) || outcome.dispatched) return
        outcome.failure?.let { failure ->
            Telemetry.error(telemetry.tag, "interrupt.abortDispatchFailed", failure, *attributes(key))
            host.onAbortFailed(key, failure)
        }
        forceClear(key, REASON_NOT_DISPATCHED)
    }

    private suspend fun watchForTerminal(key: K, current: Int, awaitTerminal: (suspend () -> Unit)?) {
        val terminal = withTimeoutOrNull(terminalTimeoutMs) {
            if (awaitTerminal != null) awaitTerminal() else _cancelling.first { it != key }
            true
        } ?: false
        if (!isCurrent(key, current)) return
        if (terminal) {
            settled(key)
            return
        }
        Telemetry.event(
            telemetry.tag,
            "interrupt.terminalTimeout",
            *attributes(key),
            durationMs = terminalTimeoutMs,
            level = Telemetry.Level.WARN,
        )
        forceClear(key, REASON_TERMINAL_TIMEOUT, fromWatcher = true)
    }

    private fun settled(key: K) {
        watchJob = null
        _cancelling.value = null
        Telemetry.event(telemetry.tag, "interrupt.terminalAfterCancel", *attributes(key), durationMs = nowMs() - requestedAtMs)
        host.onSettled(key)
    }

    private fun forceClear(key: K, reason: String, fromWatcher: Boolean = false) {
        if (fromWatcher) watchJob = null else cancelWatcher()
        _cancelling.value = null
        host.onForcedClear(key, reason)
        Telemetry.event(
            telemetry.tag,
            "interrupt.forcedLocalClear",
            *attributes(key),
            "reason" to reason,
            level = Telemetry.Level.WARN,
        )
    }

    private fun cancelWatcher() {
        watchJob?.cancel()
        watchJob = null
    }

    private fun isCurrent(key: K, current: Int): Boolean = attempt == current && isCancelling(key)

    private fun attributes(key: K): Array<Pair<String, String?>> =
        (telemetry.attributes + ("conversationId" to key.toString())).toTypedArray()

    private class AbortOutcome(val dispatched: Boolean, val failure: Exception?)

    companion object {
        /** Upper bound on holding "stopping…" before falling back to the local clear. */
        const val CANCEL_TERMINAL_TIMEOUT_MS = 30_000L

        const val REASON_SECOND_PRESS = "secondStopPress"
        const val REASON_CANNOT_ABORT = "gatewayCannotAbort"
        const val REASON_NOT_DISPATCHED = "abortNotDispatched"
        const val REASON_TERMINAL_TIMEOUT = "terminalTimeout"
    }
}
