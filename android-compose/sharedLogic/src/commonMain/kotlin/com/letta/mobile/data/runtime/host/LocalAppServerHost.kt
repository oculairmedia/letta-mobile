package com.letta.mobile.data.runtime.host

import com.letta.mobile.data.repository.modelcontrol.secureRandomBytes
import com.letta.mobile.data.runtime.supervisor.RuntimeCrashDecision
import com.letta.mobile.data.runtime.supervisor.RuntimeCrashPolicy
import com.letta.mobile.data.runtime.supervisor.RuntimeExit
import com.letta.mobile.data.runtime.supervisor.RuntimeHealth
import com.letta.mobile.data.runtime.supervisor.StderrRing
import com.letta.mobile.data.transport.TerminalConnectionFailure
import kotlin.concurrent.Volatile
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.atomicfu.atomic
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.atomicfu.updateAndGet
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/** One owner's claim on the local App Server: where to dial it and the bearer token it requires. */
interface LocalAppServerLease : AutoCloseable {
    val serverUrl: String
    val bearerToken: String
}

/** The server died or stayed silent before it was ready. */
class LocalAppServerStartException(
    message: String,
    val exitCode: Int?,
    cause: Throwable? = null,
) : IllegalStateException(message, cause)

/**
 * A single-launch server's one launch is over in this process: it
 * exited, or never became ready before its node ended. Only a new app process can run it, so the
 * connection supervisor stops redialling ([TerminalConnectionFailure]). [reason] says what ended it.
 */
class LocalAppServerTerminatedException(val reason: String) :
    IllegalStateException(LocalAppServerHost.RESTART_THE_APP),
    TerminalConnectionFailure

/** The user stopped the local App Server; it stays off until they start it again. */
class LocalAppServerStoppedException :
    IllegalStateException("The local runtime is stopped. Start it from Settings or the chat banner.")

/**
 * The one supervisor of a loopback letta-code App Server, shared by every client that hosts its
 * own (lifted from the desktop runtime manager and supervisor).
 *
 * Every launch is `<command> server --backend local --listen ws://127.0.0.1:0 --ws-auth
 * capability-token --ws-token-file <backendDir>/ws-token` with a fresh 32-byte token. The host
 * reads the announced port from `Listening on ws://…`, waits for `/readyz`, and hands out
 * [LocalAppServerLease]s carrying the URL and the token. The server stops when the last lease
 * closes.
 *
 * Crash supervision: an unexpected exit while a lease is held restarts
 * after the [policy] backoff; giving up waits for [forceRestart]. Our own stops and clean exits
 * never restart. [onSuspended]/[onResumed] keep a laptop sleep from counting as crashes.
 *
 * Single launch (decision D3): a [LocalAppServerLauncher.singleLaunch]
 * launcher can run the server once per OS process (Android runs node in-process, and `node::Start` cannot run
 * twice). The host then keeps the server when the last lease closes, leaves a live one alone on
 * [forceRestart], and never restarts it: any exit gives up with [RESTART_THE_APP], since only a
 * new app process can start it again. The one start runs in [scope] and every acquire waits on it
 * a caller that is cancelled or times out never abandons the node, and
 * once the launch is over every acquire fails with [LocalAppServerTerminatedException] without
 * launching again.
 *
 * Lifecycle lines (`[lifecycle] …`: launch, preparation time, listen line, readyz, exits, refusals)
 * go to [logLine] with the server's output, so a platform can keep them past its log buffer.
 *
 * Locking: [lifecycle] serialises starting and stopping the server; [lock] guards the crash
 * bookkeeping and is never held while waiting on [lifecycle].
 */
@Suppress("TooManyFunctions") // One supervisor: lease, lifecycle and crash handling share the same state.
class LocalAppServerHost(
    private val launcher: LocalAppServerLauncher,
    private val launch: suspend () -> LocalAppServerLaunch,
    private val readyz: suspend (serverUrl: String) -> Boolean,
    private val scope: CoroutineScope,
    private val logLine: (String) -> Unit = {},
    private val policy: RuntimeCrashPolicy = RuntimeCrashPolicy(),
    private val readyTimeoutMs: Long = DEFAULT_READY_TIMEOUT_MS,
    private val clockMs: () -> Long = { Clock.System.now().toEpochMilliseconds() },
    private val newToken: () -> String = ::newCapabilityToken,
) : AutoCloseable {
    private class RunningServer(val process: LocalAppServerProcess, val serverUrl: String, val bearerToken: String) {
        @Volatile
        var stoppedByUs: Boolean = false
    }

    private val singleLaunch = launcher.singleLaunch
    private val lifecycle = Mutex()
    private val lock = SynchronizedObject()
    private val stderr = StderrRing()
    private val owners = atomic(0)
    private var pendingRestart: Job? = null

    /** Single launch: the one start, shared by every acquire until it is ready or over; null until launched. */
    @Volatile
    private var singleStart: Deferred<RunningServer?>? = null

    /** Single launch: why the one launch is over; set once and never cleared in this process. */
    @Volatile
    private var endedReason: String? = null

    @Volatile
    private var server: RunningServer? = null

    private val _health = MutableStateFlow(RuntimeHealth())

    /** Health of the server, for the settings card and the chat banner. */
    val health: StateFlow<RuntimeHealth> = _health.asStateFlow()

    private val _restarted = MutableSharedFlow<String>(extraBufferCapacity = 8)

    /** URLs of servers the host restarted on its own; a client re-acquires to reconnect. */
    val restarted: SharedFlow<String> = _restarted.asSharedFlow()

    /**
     * Starts the server if it is not running and claims it until the lease closes. Refuses while
     * the user has stopped it ([stopByUser]).
     */
    suspend fun acquire(): LocalAppServerLease {
        owners.incrementAndGet()
        val running = runCatching { ensureStarted() }.onFailure { release() }.getOrThrow()
        return Lease(running)
    }

    /** The user turned the runtime off: stop the server and keep it off until [startByUser]. */
    fun stopByUser() {
        synchronized(lock) {
            cancelPendingRestart()
            _health.update { it.copy(stoppedByUser = true, restartPending = false) }
        }
        scope.launch { lifecycle.withLock { stopLocked() } }
    }

    /** The user turned the runtime back on: start it now if anyone wants it, else on next use. */
    fun startByUser() {
        synchronized(lock) {
            policy.reset()
            _health.update {
                if (singleLaunchOver) {
                    it.copy(stoppedByUser = false)
                } else {
                    it.copy(stoppedByUser = false, consecutiveCrashes = 0, gaveUp = false, lastError = null)
                }
            }
        }
        scope.launch { if (!isRunning) restartNow() }
    }

    /** The user pressed Force restart: forget past crashes, stop the server, start a fresh one. */
    fun forceRestart() {
        // A single launch is used once: a live server is left alone, an ended one cannot start again.
        if (singleLaunch && singleStart != null) return
        synchronized(lock) {
            cancelPendingRestart()
            policy.reset()
            stderr.clear()
            _health.update {
                it.copy(
                    consecutiveCrashes = 0,
                    gaveUp = false,
                    restartPending = true,
                    recentStderr = emptyList(),
                    lastError = null,
                    stoppedByUser = false,
                )
            }
        }
        scope.launch {
            lifecycle.withLock { stopLocked() }
            restartNow()
        }
    }

    /** The system is going to sleep: exits from here on are not crashes. */
    fun onSuspended() {
        synchronized(lock) { policy.onSuspended() }
    }

    /** The system woke: clear crash state and bring a dead server back without backoff. */
    fun onResumed() {
        val restart = synchronized(lock) {
            policy.onResumed()
            cancelPendingRestart()
            _health.update { it.copy(consecutiveCrashes = 0, gaveUp = singleLaunchOver) }
            !_health.value.active && !singleLaunchOver
        }
        if (restart) scope.launch { if (!isRunning) restartNow() }
    }

    /** Stops the server now, whoever holds a lease (process shutdown). */
    override fun close() {
        owners.value = 0
        synchronized(lock) { cancelPendingRestart() }
        server?.let { running ->
            server = null
            running.stoppedByUs = true
            running.process.kill()
        }
    }

    private val isRunning: Boolean get() = server?.process?.isAlive == true

    /** A single-launch server's one launch is over: it ran (or failed to) and only a new app process runs it again. */
    private val singleLaunchOver: Boolean get() = endedReason != null

    private fun lifecycleLine(message: String) = logLine("[lifecycle] $message")

    private fun since(startedMs: Long): Long = clockMs() - startedMs

    private suspend fun ensureStarted(): RunningServer {
        val pending = lifecycle.withLock {
            if (_health.value.stoppedByUser) throw LocalAppServerStoppedException()
            server?.takeIf { it.process.isAlive }?.let { return it }
            if (!singleLaunch) {
                stopLocked()
                return startLocked()
            }
            singleStartLocked()
        }
        return awaitSingleStart(pending)
    }

    /** Caller holds [lifecycle]. */
    private fun stopLocked() {
        val running = server ?: return
        server = null
        running.stoppedByUs = true
        running.process.kill()
    }

    /** Caller holds [lifecycle]. A restartable server: a start that fails or is cancelled kills what it began. */
    private suspend fun startLocked(): RunningServer {
        val prepared = prepareLaunch()
        val began = clockMs()
        val process = startProcess(prepared)
        val outcome = runCatching { awaitReady(process, began, readyTimeoutMs) }
            .onFailure { process.kill() }
            .getOrThrow()
        return when (outcome) {
            is LocalAppServerReadyOutcome.Ready -> becomeReady(process, outcome.url, prepared.token)
            is LocalAppServerReadyOutcome.Ended -> {
                process.kill()
                throw LocalAppServerStartException("The local App Server exited before ready (exit=${outcome.exitCode})", outcome.exitCode)
            }
            null -> {
                process.kill()
                throw LocalAppServerStartException("The local App Server was not ready within $readyTimeoutMs ms", null)
            }
        }
    }

    /**
     * Caller holds [lifecycle]. The one start of a single-launch server:
     * launched on first use, shared by every acquire while it starts, refused once it is over. The
     * platform's preparation (asset extraction, bridges) runs in the caller and may be retried; the
     * node itself is started at most once.
     */
    private suspend fun singleStartLocked(): Deferred<RunningServer?> {
        singleStart?.let { start ->
            if (start.isActive) return start
            throw terminated("the on-device runtime stopped")
        }
        val prepared = prepareLaunch()
        return scope.async { runSingleLaunch(prepared) }.also { singleStart = it }
    }

    /**
     * Runs in [scope], not the caller's coroutine: a dial that is cancelled or times out must not
     * abandon a node that can never start again. Waits as long as the node lives; null once it ended.
     */
    private suspend fun runSingleLaunch(prepared: PreparedLocalAppServerLaunch): RunningServer? {
        val began = clockMs()
        val process = runCatching { startProcess(prepared) }.getOrElse { error ->
            if (error is CancellationException) throw error
            endSingleLaunch("node did not start: ${error.message}")
            return null
        }
        return when (val outcome = awaitReady(process, began, timeoutMs = null)) {
            is LocalAppServerReadyOutcome.Ready -> becomeReady(process, outcome.url, prepared.token)
            else -> {
                val exitCode = (outcome as? LocalAppServerReadyOutcome.Ended)?.exitCode
                endSingleLaunch("node exited before it was ready (exit=$exitCode) ${since(began)} ms after it started")
                null
            }
        }
    }

    /** One caller's wait for the single launch: [readyTimeoutMs] at most, after which it may try again. */
    private suspend fun awaitSingleStart(pending: Deferred<RunningServer?>): RunningServer {
        val waitBegan = clockMs()
        val running = try {
            withTimeoutOrNull(readyTimeoutMs.milliseconds) { pending.await() }
        } catch (e: CancellationException) {
            lifecycleLine("a caller stopped waiting after ${since(waitBegan)} ms; the start goes on")
            throw e
        }
        if (running != null) return running
        // A completed start without a server ended before it was ready; else this caller's wait ran out.
        throw if (pending.isCompleted) {
            terminated("the on-device runtime stopped")
        } else {
            lifecycleLine("not ready within $readyTimeoutMs ms for this caller; node is still starting")
            LocalAppServerStartException("The on-device runtime is still starting (not ready within $readyTimeoutMs ms)", null)
        }
    }

    /** The platform's launch with the server arguments, the backend environment and a fresh token; nothing runs yet. */
    private suspend fun prepareLaunch(): PreparedLocalAppServerLaunch {
        val began = clockMs()
        lifecycleLine("launch requested (singleLaunch=$singleLaunch)")
        val spec = runCatching { launch() }
            .onFailure { e -> lifecycleLine("launch preparation failed after ${since(began)} ms, nothing started: ${e.message}") }
            .getOrThrow()
        lifecycleLine("launch prepared in ${since(began)} ms (runtime assets, bridges)")
        return preparedLocalAppServerLaunch(spec, newToken())
    }

    private fun startProcess(prepared: PreparedLocalAppServerLaunch): LocalAppServerProcess {
        lifecycleLine("node starting: ${prepared.launch.command.joinToString(" ")}")
        return runCatching { launcher.start(prepared.launch, prepared.tokenFile, prepared.token) }
            .onFailure { e -> lifecycleLine("node start refused: ${e.message}") }
            .getOrThrow()
    }

    private fun becomeReady(process: LocalAppServerProcess, url: String, token: String): RunningServer {
        val running = RunningServer(process, url, token)
        server = running
        onStarted()
        // Watch exits only once ready: a server that dies before that is reported by awaitReady.
        scope.launch {
            val exitCode = process.awaitExit()
            lifecycleLine("node exited (exit=$exitCode, stopped by the app=${running.stoppedByUs})")
            onExit(RuntimeExit(exitCode = exitCode, intentional = running.stoppedByUs))
        }
        return running
    }

    /**
     * Waits for the announced URL and `/readyz`: at most [timeoutMs], or as long as the server lives
     * when null. Null when the wait timed out with the server still running.
     */
    private suspend fun awaitReady(process: LocalAppServerProcess, began: Long, timeoutMs: Long?): LocalAppServerReadyOutcome? {
        val announced = scope.announcedListenUrl(
            process,
            onStdout = { line -> logLine("[stdout] $line") },
            onStderr = { line ->
                logLine("[stderr] $line")
                synchronized(lock) { stderr.add(line) }
            },
        )
        val ready: suspend () -> String? = {
            announced.await()?.let { url ->
                lifecycleLine("listen line seen ${since(began)} ms after start: $url")
                while (process.isAlive && !readyz(url)) delay(READY_POLL_MS.milliseconds)
                url.takeIf { process.isAlive }?.also { lifecycleLine("readyz ok ${since(began)} ms after start") }
            }
        }
        val url = if (timeoutMs == null) ready() else withTimeoutOrNull(timeoutMs.milliseconds) { ready() }
        if (url != null) return LocalAppServerReadyOutcome.Ready(url)
        val stdoutClosed = announced.isCompleted && announced.await() == null
        if (!stdoutClosed && process.isAlive) {
            lifecycleLine("readiness timed out after ${since(began)} ms (listen line seen=${announced.isCompleted})")
            return null
        }
        val exitCode = withTimeoutOrNull(EXIT_WAIT_MS.milliseconds) { process.awaitExit() }
        lifecycleLine("node exited before ready (exit=$exitCode) ${since(began)} ms after start")
        return LocalAppServerReadyOutcome.Ended(exitCode)
    }

    /** The single launch is over for this process: report it once, for good. */
    private fun endSingleLaunch(reason: String) {
        synchronized(lock) {
            if (endedReason == null) {
                endedReason = reason
                lifecycleLine("single launch over: $reason. Restart the app to run the runtime again.")
            }
            cancelPendingRestart()
            _health.update {
                it.copy(
                    active = false,
                    restartPending = false,
                    gaveUp = true,
                    stoppedUnexpectedly = true,
                    recentStderr = stderr.snapshot(),
                    lastError = RESTART_THE_APP,
                )
            }
        }
    }

    /** Why an acquire is refused once the single launch is over; [fallback] when nothing recorded why. */
    private fun terminated(fallback: String): LocalAppServerTerminatedException {
        endSingleLaunch(fallback)
        val reason = endedReason ?: fallback
        lifecycleLine("launch refused: the single launch is over ($reason)")
        return LocalAppServerTerminatedException(reason)
    }

    private fun release() {
        // Never below zero: a lease may outlive close(), which drops every claim at once.
        if (owners.updateAndGet { maxOf(it - 1, 0) } > 0 || singleLaunch) return
        scope.launch { lifecycle.withLock { if (owners.value == 0) stopLocked() } }
    }

    private fun onStarted() {
        synchronized(lock) {
            policy.onStarted(clockMs())
            cancelPendingRestart()
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

    private fun onExit(exit: RuntimeExit) {
        if (singleLaunch) {
            endSingleLaunch(if (exit.intentional) "the app stopped the runtime" else "node exited (exit=${exit.exitCode})")
            return
        }
        if (exit.intentional) {
            _health.update { it.copy(active = false, stoppedUnexpectedly = false) }
            return
        }
        handleCrash(exit, error = null)
    }

    private fun handleCrash(exit: RuntimeExit, error: String?) {
        synchronized(lock) {
            val recent = stderr.snapshot()
            val inUse = owners.value > 0
            val decision = crashDecision(exit, inUse)
            logLine("[supervisor] runtime exited unexpectedly (exit=${exit.exitCode}); decision=$decision")
            recent.forEach { logLine("[supervisor] stderr: $it") }
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
                    lastError = error ?: RESTART_THE_APP.takeIf { singleLaunch } ?: it.lastError,
                )
            }
            if (decision is RuntimeCrashDecision.Restart) {
                cancelPendingRestart()
                pendingRestart = scope.launch {
                    delay(decision.delayMs.milliseconds)
                    restartNow()
                }
            }
        }
    }

    /** Caller holds [lock]. A single-launch server can never start again in this process. */
    private fun crashDecision(exit: RuntimeExit, inUse: Boolean): RuntimeCrashDecision = when {
        singleLaunch -> RuntimeCrashDecision.GiveUp(policy.consecutiveCrashes)
        inUse -> policy.onExit(clockMs(), exit)
        else -> RuntimeCrashDecision.Ignore("no lease holders")
    }

    private suspend fun restartNow() {
        if (owners.value == 0 || _health.value.stoppedByUser) {
            _health.update { it.copy(restartPending = false) }
            return
        }
        val running = try {
            ensureStarted()
        } catch (e: CancellationException) {
            throw e
        } catch (e: IllegalStateException) {
            val code = (e as? LocalAppServerStartException)?.exitCode
            handleCrash(RuntimeExit(exitCode = code ?: -1, intentional = false), error = e.message)
            return
        }
        _restarted.tryEmit(running.serverUrl)
    }

    /** Caller holds [lock]. */
    private fun cancelPendingRestart() {
        pendingRestart?.cancel()
        pendingRestart = null
    }

    private inner class Lease(running: RunningServer) : LocalAppServerLease {
        override val serverUrl: String = running.serverUrl
        override val bearerToken: String = running.bearerToken
        private val released = atomic(false)

        override fun close() {
            if (released.compareAndSet(expect = false, update = true)) release()
        }
    }

    companion object {
        /** The capability-token file inside the backend directory. */
        const val TOKEN_FILE_NAME: String = "ws-token"
        const val DEFAULT_READY_TIMEOUT_MS: Long = 30_000L

        /** Why a single-launch host gave up: the server ran once in this process and cannot run again. */
        const val RESTART_THE_APP: String = "On-device runtime stopped. Restart the app."
        private const val READY_POLL_MS = 100L
        private const val EXIT_WAIT_MS = 2_000L
        private const val TOKEN_BYTES = 32

        /** A fresh 32-byte token, hex encoded. */
        @OptIn(ExperimentalStdlibApi::class)
        fun newCapabilityToken(): String = secureRandomBytes(TOKEN_BYTES).toHexString()
    }
}
