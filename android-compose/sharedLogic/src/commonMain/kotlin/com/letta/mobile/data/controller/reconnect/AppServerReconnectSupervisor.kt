package com.letta.mobile.data.controller.reconnect

import com.letta.mobile.data.controller.AppServerControllerState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlin.random.Random

/**
 * Drives automatic reconnect/replay of the App Server controller on connection
 * drops, with bounded full-jitter backoff (letta-mobile-lgns8.5).
 *
 * The [ReconnectCoordinator] performs a SINGLE reconnect (reattach runtimes,
 * re-register external tools, sync with approval/device recovery). This
 * supervisor is the self-driving *loop* around it: while the observed
 * connection state is a retryable drop, it schedules a reconnect after a
 * [FullJitterBackoff] delay and keeps retrying (each attempt growing the
 * backoff ceiling) until the connection is restored, a terminal condition is
 * hit, or [maxAttempts] is exhausted. Once the connection returns to
 * [AppServerControllerState.Connected] and stays stable for
 * [stableConnectedResetMs], the attempt counter resets so a later drop starts
 * fresh from the base delay instead of an inflated ceiling.
 *
 * Because it reads [StateFlow.value] it drives retries itself rather than
 * depending on one state *emission* per attempt — a server that stays down
 * emits its "disconnected" state once, but the supervisor must keep retrying.
 *
 * Design goals:
 * - **No thundering herd**: full-jitter delays de-synchronize many clients
 *   reconnecting to one restarted server.
 * - **Ambiguity-safe**: this supervisor only orchestrates *reconnect* (idempotent
 *   reattach + read-style sync). It never replays the application's
 *   non-idempotent writes — that guard lives in the request layer.
 * - **Deterministic/testable**: [delayProvider] and [random] are injectable so
 *   the loop runs under virtual time with a seeded RNG.
 *
 * @param connectionState the controller's connection-state (a StateFlow so the
 *   supervisor can both react to changes and poll the latest value between
 *   backoff waits).
 * @param reconnect performs one reconnect attempt; must be idempotent.
 * @param backoff the full-jitter delay policy.
 * @param stableConnectedResetMs how long the connection must remain Connected
 *   before the attempt counter resets to 0.
 * @param maxAttempts optional hard cap on consecutive failed attempts; null =
 *   retry indefinitely. A terminal signal always stops regardless.
 * @param isTerminal classifies a Disconnected/Error state as terminal (must not
 *   be retried — e.g. auth/config failure). Default: never terminal.
 */
class AppServerReconnectSupervisor(
    private val connectionState: StateFlow<AppServerControllerState>,
    private val reconnect: suspend () -> ReconnectResult,
    private val backoff: FullJitterBackoff = FullJitterBackoff(),
    private val stableConnectedResetMs: Long = DEFAULT_STABLE_RESET_MS,
    private val maxAttempts: Int? = null,
    private val random: Random = Random.Default,
    private val delayProvider: suspend (Long) -> Unit = { delay(it) },
    private val isTerminal: (AppServerControllerState) -> Boolean = { false },
    private val onEvent: (SupervisorEvent) -> Unit = {},
) {
    /**
     * Runs the supervisor loop. Collects [connectionState]; each time the state
     * enters a retryable drop it runs the internal retry loop. Returns when the
     * flow completes, a terminal condition is hit, [maxAttempts] is exhausted,
     * or the coroutine is cancelled.
     */
    suspend fun run() {
        var attempt = 0
        var connectedSinceMs: Long? = null
        var elapsedMs = 0L

        try {
            connectionState.collect { state ->
            when (state) {
                is AppServerControllerState.Connected -> {
                    connectedSinceMs = elapsedMs
                    if (attempt != 0 && stableConnectedResetMs <= 0) {
                        attempt = 0
                        onEvent(SupervisorEvent.AttemptsReset)
                    }
                    onEvent(SupervisorEvent.Connected)
                }

                is AppServerControllerState.Disconnected,
                is AppServerControllerState.Error,
                -> {
                    if (isTerminal(state)) {
                        onEvent(SupervisorEvent.TerminalStop(state))
                        throw StopSupervision
                    }

                    // A stable prior connection resets the sequence.
                    val since = connectedSinceMs
                    if (since != null && elapsedMs - since >= stableConnectedResetMs) {
                        attempt = 0
                        onEvent(SupervisorEvent.AttemptsReset)
                    }
                    connectedSinceMs = null

                    // Self-driving retry loop: keep retrying while the *current*
                    // state remains a retryable drop. Reading connectionState.value
                    // lets a single "disconnected" emission produce many attempts.
                    while (isRetryableDrop(connectionState.value)) {
                        if (maxAttempts != null && attempt >= maxAttempts) {
                            onEvent(SupervisorEvent.GaveUp(attempt))
                            throw StopSupervision
                        }
                        val waitMs = backoff.delayMs(attempt, random)
                        onEvent(SupervisorEvent.Scheduled(attempt, waitMs))
                        delayProvider(waitMs)
                        elapsedMs += waitMs
                        attempt++

                        val result = try {
                            reconnect()
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Throwable) {
                            onEvent(SupervisorEvent.AttemptFailed(attempt, e))
                            continue
                        }
                        onEvent(SupervisorEvent.AttemptCompleted(attempt, result))
                    }
                }
            }
        }
        } catch (_: StopSupervision) {
            // Normal, expected termination on terminal state or give-up.
        }
    }

    /** Private sentinel to break the infinite StateFlow collect on stop. */
    private object StopSupervision : CancellationException("reconnect supervision stopped")

    private fun isRetryableDrop(state: AppServerControllerState): Boolean =
        (state is AppServerControllerState.Disconnected ||
            state is AppServerControllerState.Error) &&
            !isTerminal(state)

    /** Observable lifecycle events, primarily for logging/telemetry and tests. */
    sealed interface SupervisorEvent {
        data object Connected : SupervisorEvent
        data object AttemptsReset : SupervisorEvent
        data class Scheduled(val attempt: Int, val delayMs: Long) : SupervisorEvent
        data class AttemptCompleted(val attempt: Int, val result: ReconnectResult) : SupervisorEvent
        data class AttemptFailed(val attempt: Int, val cause: Throwable) : SupervisorEvent
        data class GaveUp(val afterAttempts: Int) : SupervisorEvent
        data class TerminalStop(val state: AppServerControllerState) : SupervisorEvent
    }

    companion object {
        const val DEFAULT_STABLE_RESET_MS: Long = 10_000
    }
}
