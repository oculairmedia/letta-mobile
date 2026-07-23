package com.letta.mobile.appservercli

/**
 * Supervises the lifecycle of one JVM-launched Letta App Server process
 * (letta-mobile-lgns8.6): bounded startup gated on readiness, bounded graceful
 * shutdown that escalates to a forced kill of the whole process tree, and
 * bounded diagnostics when the child exits before it becomes ready.
 *
 * This class holds the *policy* (timeouts, ordering, escalation). The
 * effectful pieces — spawning the process, probing `/readyz`, and destroying the
 * process tree — are injected via [ProcessController] so the policy is unit
 * -testable without real processes or sockets. `appserver-cli` wires the real
 * ProcessBuilder/ProcessHandle-backed controller.
 *
 * systemd owns the long-horizon restart authority; this supervisor owns a
 * single start/stop of one process and never implements its own restart loop.
 */
class AppServerSupervisor(
    private val process: ProcessController,
    private val readiness: ReadinessProbe,
    private val config: SupervisorConfig = SupervisorConfig(),
    private val sleep: (Long) -> Unit = { Thread.sleep(it) },
    private val nowMs: () -> Long = { System.currentTimeMillis() },
    private val onEvent: (SupervisorLifecycleEvent) -> Unit = {},
) {
    /**
     * Start the process and wait until it reports ready or fails. Blocks up to
     * [SupervisorConfig.startupTimeoutMs].
     *
     * @return [StartupOutcome.Ready] on success, or a failure outcome carrying
     *   bounded diagnostics. Never throws for expected failure modes.
     */
    fun start(): StartupOutcome {
        onEvent(SupervisorLifecycleEvent.Starting)
        process.spawn()
        val deadline = nowMs() + config.startupTimeoutMs
        while (nowMs() < deadline) {
            // A child that exits before readiness is a hard startup failure.
            val exit = process.exitCodeOrNull()
            if (exit != null) {
                val diag = process.drainDiagnostics(config.maxDiagnosticChars)
                onEvent(SupervisorLifecycleEvent.ExitedBeforeReady(exit, diag))
                return StartupOutcome.ExitedBeforeReady(exit, diag)
            }
            if (readiness.isReady()) {
                onEvent(SupervisorLifecycleEvent.Ready)
                return StartupOutcome.Ready
            }
            sleep(config.readinessPollIntervalMs)
        }
        // Timed out waiting for readiness — stop the half-started process.
        val diag = process.drainDiagnostics(config.maxDiagnosticChars)
        onEvent(SupervisorLifecycleEvent.StartupTimedOut(diag))
        stop()
        return StartupOutcome.StartupTimedOut(diag)
    }

    /**
     * Gracefully terminate the process, escalating to a forced tree-kill if it
     * does not exit within [SupervisorConfig.gracefulShutdownMs]. Guarantees no
     * descendant processes are left behind.
     */
    fun stop() {
        if (process.exitCodeOrNull() != null) {
            onEvent(SupervisorLifecycleEvent.AlreadyStopped)
            return
        }
        onEvent(SupervisorLifecycleEvent.Stopping)
        process.terminateGracefully()
        val deadline = nowMs() + config.gracefulShutdownMs
        while (nowMs() < deadline) {
            if (process.exitCodeOrNull() != null) {
                onEvent(SupervisorLifecycleEvent.StoppedGracefully)
                process.destroyTree() // reap any stragglers, best-effort
                return
            }
            sleep(config.shutdownPollIntervalMs)
        }
        onEvent(SupervisorLifecycleEvent.ForceKilled)
        process.destroyTree()
    }

    /** Effectful process operations, injected for testability. */
    interface ProcessController {
        fun spawn()
        /** Current exit code, or null if still running. */
        fun exitCodeOrNull(): Int?
        /** Ask the process to terminate (SIGTERM-equivalent). */
        fun terminateGracefully()
        /** Force-kill the process AND all descendants; leave nothing behind. */
        fun destroyTree()
        /** Up to [limit] chars of captured stdout/stderr for diagnostics. */
        fun drainDiagnostics(limit: Int): String
    }

    /** Readiness signal — production polls GET /readyz for HTTP 200. */
    fun interface ReadinessProbe {
        fun isReady(): Boolean
    }

    sealed interface StartupOutcome {
        data object Ready : StartupOutcome
        data class ExitedBeforeReady(val exitCode: Int, val diagnostics: String) : StartupOutcome
        data class StartupTimedOut(val diagnostics: String) : StartupOutcome
    }

    sealed interface SupervisorLifecycleEvent {
        data object Starting : SupervisorLifecycleEvent
        data object Ready : SupervisorLifecycleEvent
        data class ExitedBeforeReady(val exitCode: Int, val diagnostics: String) : SupervisorLifecycleEvent
        data class StartupTimedOut(val diagnostics: String) : SupervisorLifecycleEvent
        data object Stopping : SupervisorLifecycleEvent
        data object StoppedGracefully : SupervisorLifecycleEvent
        data object ForceKilled : SupervisorLifecycleEvent
        data object AlreadyStopped : SupervisorLifecycleEvent
    }
}

/**
 * Bounded timeouts for the supervisor. All in ms.
 *
 * @param startupTimeoutMs max wait for the process to become ready.
 * @param gracefulShutdownMs max wait after graceful terminate before force-kill.
 * @param readinessPollIntervalMs poll cadence while waiting for readiness.
 * @param shutdownPollIntervalMs poll cadence while waiting for graceful exit.
 * @param maxDiagnosticChars cap on captured diagnostic output.
 */
data class SupervisorConfig(
    val startupTimeoutMs: Long = 60_000,
    val gracefulShutdownMs: Long = 10_000,
    val readinessPollIntervalMs: Long = 250,
    val shutdownPollIntervalMs: Long = 100,
    val maxDiagnosticChars: Int = 8_192,
) {
    init {
        require(startupTimeoutMs > 0) { "startupTimeoutMs must be > 0" }
        require(gracefulShutdownMs > 0) { "gracefulShutdownMs must be > 0" }
        require(readinessPollIntervalMs > 0) { "readinessPollIntervalMs must be > 0" }
        require(shutdownPollIntervalMs > 0) { "shutdownPollIntervalMs must be > 0" }
        require(maxDiagnosticChars > 0) { "maxDiagnosticChars must be > 0" }
    }
}
