package com.letta.mobile.desktop.runtime

import com.letta.mobile.data.runtime.supervisor.RuntimeCrashDecision
import com.letta.mobile.data.runtime.supervisor.RuntimeCrashPolicy
import com.letta.mobile.data.runtime.supervisor.RuntimeExit
import com.letta.mobile.data.runtime.supervisor.RuntimeHealth
import com.letta.mobile.data.runtime.supervisor.StderrRing
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.milliseconds

/**
 * Crash supervisor for the bundled letta-code child (letta-mobile-bzvro.3, F03).
 *
 * Watches every child the [manager] starts. An unexpected exit while someone still holds a lease
 * ([wanted]) is restarted after the shared [RuntimeCrashPolicy] backoff (2/4/8/16 s, 30 s cap);
 * five crashes in a minute stop automatic restarts until [forceRestart]. Our own stops and clean
 * exits never restart. The last stderr lines are flushed to the log on every crash and kept in
 * [health] for the settings card.
 *
 * Suspend awareness (F04): [onSuspended] stops exits from counting; [onResumed] clears the crash
 * state and restarts a dead child at once.
 *
 * Locking: event callbacks arrive on process threads, sometimes while the manager's monitor is
 * held. They only touch this class's own [lock] and launch work on [scope]; nothing here calls
 * the manager while holding [lock], so the two monitors never nest the other way round. [wanted]
 * is read under [lock], so it must not take a lock itself.
 */
internal class DesktopRuntimeSupervisor(
    private val manager: DesktopLocalRuntimeManager,
    private val scope: CoroutineScope,
    private val wanted: () -> Boolean,
    private val logLine: (String) -> Unit,
    private val clockMs: () -> Long = System::currentTimeMillis,
    private val policy: RuntimeCrashPolicy = RuntimeCrashPolicy(),
) : DesktopRuntimeEvents {
    private val lock = Any()
    private val stderr = StderrRing()
    private var pendingRestart: Job? = null

    private val _health = MutableStateFlow(RuntimeHealth())
    val health: StateFlow<RuntimeHealth> = _health.asStateFlow()

    private val _restarted = MutableSharedFlow<String>(extraBufferCapacity = 8)

    /** URLs of children this supervisor (re)started on its own; the chat reconnects to them. */
    val restarted: SharedFlow<String> = _restarted.asSharedFlow()

    init {
        manager.events = this
    }

    /** Starts the child if it is not running (a lease acquire). Blocking. */
    fun ensureStarted(): String = manager.ensureStarted()

    override fun onStarted(url: String) {
        synchronized(lock) {
            policy.onStarted(clockMs())
            pendingRestart?.cancel()
            pendingRestart = null
            _health.update {
                it.copy(
                    active = true,
                    restartPending = false,
                    gaveUp = false,
                    stoppedUnexpectedly = false,
                    generation = it.generation + 1,
                    consecutiveCrashes = policy.consecutiveCrashes,
                    lastError = null,
                )
            }
        }
    }

    override fun onStderr(line: String) {
        synchronized(lock) { stderr.add(line) }
    }

    override fun onExit(exit: RuntimeExit) {
        if (exit.intentional) {
            _health.update { it.copy(active = false, stoppedUnexpectedly = false) }
            return
        }
        handleCrash(exit, error = null)
    }

    private fun handleCrash(exit: RuntimeExit, error: String?) {
        synchronized(lock) {
            val recent = stderr.snapshot()
            val inUse = wanted()
            val decision = if (inUse) policy.onExit(clockMs(), exit) else RuntimeCrashDecision.Ignore("no lease holders")
            logCrash(exit, decision, recent)
            _health.update {
                it.copy(
                    active = false,
                    lastExitCode = exit.exitCode,
                    lastSignal = exit.signal,
                    recentStderr = recent,
                    consecutiveCrashes = policy.consecutiveCrashes,
                    restartPending = decision is RuntimeCrashDecision.Restart,
                    gaveUp = decision is RuntimeCrashDecision.GiveUp,
                    stoppedUnexpectedly = inUse,
                    lastError = error ?: it.lastError,
                )
            }
            if (decision is RuntimeCrashDecision.Restart) scheduleRestart(decision.delayMs)
        }
    }

    private fun logCrash(exit: RuntimeExit, decision: RuntimeCrashDecision, recent: List<String>) {
        logLine("[supervisor] runtime exited unexpectedly (exit=${exit.exitCode}); decision=$decision")
        recent.forEach { logLine("[supervisor] stderr: $it") }
    }

    /** Caller holds [lock]. */
    private fun scheduleRestart(delayMs: Long) {
        pendingRestart?.cancel()
        pendingRestart = scope.launch {
            delay(delayMs.milliseconds)
            restartNow()
        }
    }

    private fun restartNow() {
        if (!wanted()) {
            _health.update { it.copy(restartPending = false) }
            return
        }
        val url = try {
            manager.ensureStarted()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val code = (e as? DesktopRuntimeStartException)?.exitCode
            handleCrash(RuntimeExit(exitCode = code ?: -1, intentional = false), error = e.message)
            return
        }
        _restarted.tryEmit(url)
    }

    /** The user pressed Force restart: forget past crashes, stop the child, start a fresh one. */
    fun forceRestart() {
        synchronized(lock) {
            pendingRestart?.cancel()
            pendingRestart = null
            policy.reset()
            stderr.clear()
            _health.update { it.copy(consecutiveCrashes = 0, gaveUp = false, restartPending = true, recentStderr = emptyList(), lastError = null) }
        }
        scope.launch {
            manager.close()
            restartNow()
        }
    }

    /** The system is going to sleep (F04): exits from here on are not crashes. */
    fun onSuspended() {
        synchronized(lock) { policy.onSuspended() }
    }

    /** The system woke (F04): clear crash state and bring a dead child back without backoff. */
    fun onResumed() {
        val restart = synchronized(lock) {
            policy.onResumed()
            pendingRestart?.cancel()
            pendingRestart = null
            _health.update { it.copy(consecutiveCrashes = 0, gaveUp = false) }
            !_health.value.active
        }
        if (!restart) return
        scope.launch {
            if (!manager.isAlive) restartNow()
        }
    }
}
