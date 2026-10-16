package com.letta.mobile.data.runtime.host

import com.letta.mobile.data.repository.modelcontrol.secureRandomBytes
import com.letta.mobile.data.runtime.supervisor.RuntimeCrashDecision
import com.letta.mobile.data.runtime.supervisor.RuntimeCrashPolicy
import com.letta.mobile.data.runtime.supervisor.RuntimeExit
import com.letta.mobile.data.runtime.supervisor.RuntimeHealth
import com.letta.mobile.data.runtime.supervisor.StderrRing
import kotlin.concurrent.Volatile
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.atomicfu.atomic
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.atomicfu.updateAndGet
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/** One owner's claim on the local App Server: where to dial it and the bearer token it requires. */
interface LocalAppServerLease : AutoCloseable {
    val serverUrl: String
    val bearerToken: String
}

/** The server died or stayed silent before it was ready. */
class LocalAppServerStartException(message: String, val exitCode: Int?) : IllegalStateException(message)

/** The user stopped the local App Server; it stays off until they start it again. */
class LocalAppServerStoppedException :
    IllegalStateException("The local runtime is stopped. Start it from Settings or the chat banner.")

/**
 * The one supervisor of a loopback letta-code App Server, shared by every client that hosts its
 * own (letta-mobile-by4vm.110.4; lifted from the desktop runtime manager and supervisor).
 *
 * Every launch is `<command> server --backend local --listen ws://127.0.0.1:0 --ws-auth
 * capability-token --ws-token-file <backendDir>/ws-token` with a fresh 32-byte token. The host
 * reads the announced port from `Listening on ws://…`, waits for `/readyz`, and hands out
 * [LocalAppServerLease]s carrying the URL and the token. The server stops when the last lease
 * closes.
 *
 * Crash supervision (letta-mobile-bzvro.3): an unexpected exit while a lease is held restarts
 * after the [policy] backoff; giving up waits for [forceRestart]. Our own stops and clean exits
 * never restart. [onSuspended]/[onResumed] keep a laptop sleep from counting as crashes.
 *
 * Locking: [lifecycle] serialises starting and stopping the server; [lock] guards the crash
 * bookkeeping and is never held while waiting on [lifecycle].
 */
@Suppress("TooManyFunctions") // One supervisor: lease, lifecycle and crash handling share the same state.
class LocalAppServerHost(
    private val launcher: LocalAppServerLauncher,
    private val launch: () -> LocalAppServerLaunch,
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

    private val lifecycle = Mutex()
    private val lock = SynchronizedObject()
    private val stderr = StderrRing()
    private val owners = atomic(0)
    private var pendingRestart: Job? = null

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
            _health.update { it.copy(stoppedByUser = false, consecutiveCrashes = 0, gaveUp = false, lastError = null) }
        }
        scope.launch { if (!isRunning) restartNow() }
    }

    /** The user pressed Force restart: forget past crashes, stop the server, start a fresh one. */
    fun forceRestart() {
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
            _health.update { it.copy(consecutiveCrashes = 0, gaveUp = false) }
            !_health.value.active
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

    private suspend fun ensureStarted(): RunningServer = lifecycle.withLock {
        if (_health.value.stoppedByUser) throw LocalAppServerStoppedException()
        server?.takeIf { it.process.isAlive } ?: run {
            stopLocked()
            startLocked()
        }
    }

    /** Caller holds [lifecycle]. */
    private fun stopLocked() {
        val running = server ?: return
        server = null
        running.stoppedByUs = true
        running.process.kill()
    }

    /** Caller holds [lifecycle]. */
    private suspend fun startLocked(): RunningServer {
        val spec = launch()
        val token = newToken()
        val tokenFile = "${spec.backendDir}/$TOKEN_FILE_NAME"
        val serverLaunch = spec.copy(
            command = spec.command + serverArguments(tokenFile),
            environment = spec.environment + mapOf(
                "LETTA_LOCAL_BACKEND_EXPERIMENTAL" to "1",
                "LETTA_LOCAL_BACKEND_DIR" to spec.backendDir,
            ),
        )
        val process = launcher.start(serverLaunch, tokenFile, token)
        val running = runCatching { RunningServer(process, awaitReady(process), token) }
            .onFailure { process.kill() }
            .getOrThrow()
        server = running
        onStarted()
        // Watch exits only once ready: a server that dies before that is reported by awaitReady.
        scope.launch { onExit(RuntimeExit(exitCode = process.awaitExit(), intentional = running.stoppedByUs)) }
        return running
    }

    /** The announced URL once `/readyz` answers; throws when the server ends or stays silent first. */
    private suspend fun awaitReady(process: LocalAppServerProcess): String {
        val announced = CompletableDeferred<String?>()
        scope.launch {
            process.stderr.collect { line ->
                logLine("[stderr] $line")
                synchronized(lock) { stderr.add(line) }
            }
        }
        scope.launch {
            process.stdout.collect { line ->
                logLine("[stdout] $line")
                if (!announced.isCompleted) parseLocalAppServerListenUrl(line)?.let(announced::complete)
            }
            announced.complete(null)
        }
        val url = withTimeoutOrNull(readyTimeoutMs.milliseconds) {
            announced.await()?.also { url -> while (!readyz(url)) delay(READY_POLL_MS.milliseconds) }
        }
        if (url != null) return url
        if (!announced.isCompleted) {
            throw LocalAppServerStartException("The local App Server was not ready within $readyTimeoutMs ms", null)
        }
        val exitCode = withTimeoutOrNull(EXIT_WAIT_MS.milliseconds) { process.awaitExit() }
        throw LocalAppServerStartException("The local App Server exited before ready (exit=$exitCode)", exitCode)
    }

    private fun release() {
        // Never below zero: a lease may outlive close(), which drops every claim at once.
        if (owners.updateAndGet { maxOf(it - 1, 0) } > 0) return
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
            val decision = if (inUse) policy.onExit(clockMs(), exit) else RuntimeCrashDecision.Ignore("no lease holders")
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
                    lastError = error ?: it.lastError,
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
        private const val READY_POLL_MS = 100L
        private const val EXIT_WAIT_MS = 2_000L
        private const val TOKEN_BYTES = 32

        private fun serverArguments(tokenFile: String): List<String> = listOf(
            "server", "--backend", "local",
            "--listen", "ws://127.0.0.1:0",
            "--ws-auth", "capability-token", "--ws-token-file", tokenFile,
        )

        /** A fresh 32-byte token, hex encoded. */
        @OptIn(ExperimentalStdlibApi::class)
        fun newCapabilityToken(): String = secureRandomBytes(TOKEN_BYTES).toHexString()
    }
}

/** The loopback URL from letta-code's `Listening on ws://127.0.0.1:<port>` line, else null. */
fun parseLocalAppServerListenUrl(line: String): String? =
    Regex("""Listening on (ws://(?:127\.0\.0\.1|localhost):\d+)\b""")
        .find(line)
        ?.groupValues
        ?.get(1)
