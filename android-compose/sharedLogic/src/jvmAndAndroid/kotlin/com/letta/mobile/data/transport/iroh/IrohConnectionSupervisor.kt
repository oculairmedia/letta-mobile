package com.letta.mobile.data.transport.iroh

import com.letta.mobile.data.runtime.AppServerTurnEngine
import com.letta.mobile.data.transport.appserver.AppServerInboundFrame
import com.letta.mobile.util.Telemetry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.min
import kotlin.random.Random

data class IrohConnectConfig(
    val baseShimUrl: String,
    val token: String,
    val deviceId: String,
    val clientVersion: String,
)

data class IrohConnectionHandle(
    val config: IrohConnectConfig,
    val ticket: String,
    val sessionId: String,
    val transport: IrohAppServerTransport? = null,
    val turnEngine: AppServerTurnEngine? = null,
    val adminRpcCall: (suspend (method: String, path: String, body: String?) -> AppServerInboundFrame.AdminRpcResponse)? = null,
    val close: suspend (String) -> Unit,
) {
    suspend fun adminRpc(method: String, path: String, body: String?): AppServerInboundFrame.AdminRpcResponse =
        adminRpcCall?.invoke(method, path, body)
            ?: transport?.adminRpc(method = method, path = path, body = body)
            ?: error("Iroh admin_rpc requested without an active transport")
}

sealed interface IrohConnectionState {
    data object Disconnected : IrohConnectionState
    data object Dialing : IrohConnectionState
    data object Handshaking : IrohConnectionState
    data class Ready(val handle: IrohConnectionHandle) : IrohConnectionState
    data class Degraded(val reason: String) : IrohConnectionState
    data class AuthFailed(val reason: String) : IrohConnectionState
    data object Closed : IrohConnectionState
}

class IrohAuthFailure(message: String, cause: Throwable? = null) : IllegalStateException(message, cause)

class IrohConnectionSupervisor(
    private val scope: CoroutineScope,
    private val configProvider: () -> IrohConnectConfig?,
    private val dialer: suspend (IrohConnectConfig) -> IrohConnectionHandle,
    private val backoffPolicy: BackoffPolicy = BackoffPolicy(),
    private val randomJitterMs: (Long) -> Long = { bound -> if (bound <= 0) 0L else Random.nextLong(bound + 1) },
    private val onStateChanged: (IrohConnectionState) -> Unit = {},
) {
    private val mutex = Mutex()
    private val _state = MutableStateFlow<IrohConnectionState>(IrohConnectionState.Disconnected)
    val state: StateFlow<IrohConnectionState> = _state.asStateFlow()

    private var dialJob: Job? = null
    private var dialResult: CompletableDeferred<IrohConnectionHandle>? = null
    private var redialJob: Job? = null
    private var redialReadyAtMs: Long? = null
    private var currentHandle: IrohConnectionHandle? = null
    private var currentConfig: IrohConnectConfig? = null
    private var dialConfig: IrohConnectConfig? = null
    private var closed = false
    private var authFailed = false
    private var failureCount = 0

    suspend fun ready(timeoutMs: Long = 30_000): IrohConnectionHandle {
        val deadline = System.currentTimeMillis() + timeoutMs
        var lastError: Throwable? = null
        while (System.currentTimeMillis() < deadline) {
            val waitMs = deadline - System.currentTimeMillis()
            val result = withTimeoutOrNull(waitMs) {
                val deferred = ensureDialing()
                runCatching { deferred.await() }
            } ?: break
            result.onSuccess { return it }
            lastError = result.exceptionOrNull()
        }
        val suffix = lastError?.message?.let { ": $it" }.orEmpty()
        error("Iroh connection not ready after ${timeoutMs}ms$suffix")
    }

    suspend fun refreshConfig() {
        val staleHandle = mutex.withLock {
            val desired = configProvider()
            val active = currentConfig ?: dialConfig
            if (desired != null && active != null && active != desired) {
                authFailed = false
                val existing = currentHandle
                currentHandle = null
                currentConfig = null
                cancelDialLocked("config_changed")
                transitionTo(IrohConnectionState.Degraded("config_changed"), "config_changed")
                existing
            } else {
                null
            }
        }
        staleHandle?.close("config_changed")
    }

    suspend fun onConnectionLost(reason: String) {
        val staleHandle = mutex.withLock {
            if (closed || authFailed) return@withLock null
            val existing = currentHandle
            currentHandle = null
            currentConfig = null
            cancelDialLocked(reason)
            transitionTo(IrohConnectionState.Degraded(reason), reason)
            scheduleRedialLocked(reason)
            existing
        }
        staleHandle?.close(reason)
    }

    fun onConnectionLostAsync(reason: String) {
        scope.launch { onConnectionLost(reason) }
    }

    suspend fun disconnect(reason: String = "disconnect") {
        val handle = mutex.withLock {
            authFailed = false
            redialJob?.cancel()
            redialJob = null
            redialReadyAtMs = null
            cancelDialLocked(reason)
            val existing = currentHandle
            currentHandle = null
            currentConfig = null
            transitionTo(IrohConnectionState.Disconnected, reason)
            existing
        }
        handle?.close(reason)
    }

    suspend fun close(reason: String = "closed") {
        val handle = mutex.withLock {
            closed = true
            authFailed = false
            redialJob?.cancel()
            redialJob = null
            redialReadyAtMs = null
            cancelDialLocked(reason)
            val existing = currentHandle
            currentHandle = null
            currentConfig = null
            transitionTo(IrohConnectionState.Closed, reason)
            existing
        }
        handle?.close(reason)
    }

    private suspend fun ensureDialing(): CompletableDeferred<IrohConnectionHandle> = mutex.withLock {
        check(!closed) { "Iroh connection supervisor is closed" }
        check(!authFailed) { authFailureMessage() }
        val desired = configProvider() ?: error("Iroh connection requested with no active iroh:// config")
        val ready = currentHandle
        if (ready != null && currentConfig == desired && state.value is IrohConnectionState.Ready) {
            return@withLock CompletableDeferred(ready)
        }
        if (ready != null && currentConfig != desired) {
            scope.launch { ready.close("config_changed") }
            currentHandle = null
            currentConfig = null
            transitionTo(IrohConnectionState.Degraded("config_changed"), "config_changed")
        }
        if (dialJob?.isActive == true && dialConfig != desired) {
            cancelDialLocked("config_changed")
            transitionTo(IrohConnectionState.Degraded("config_changed"), "config_changed")
        }
        dialResult?.takeIf { dialJob?.isActive == true && dialConfig == desired }?.let { return@withLock it }
        redialReadyAtMs?.let { readyAt ->
            val delayMs = readyAt - System.currentTimeMillis()
            if (delayMs > 0) {
                val waiting = dialResult ?: CompletableDeferred<IrohConnectionHandle>().also { dialResult = it }
                if (redialJob?.isActive != true) scheduleRedialLocked("await_backoff")
                return@withLock waiting
            }
        }
        startDialLocked(desired)
    }

    private fun startDialLocked(config: IrohConnectConfig): CompletableDeferred<IrohConnectionHandle> {
        redialJob?.cancel()
        redialJob = null
        redialReadyAtMs = null
        currentConfig = config
        dialConfig = config
        val result = dialResult?.takeIf { !it.isCompleted } ?: CompletableDeferred()
        dialResult = result
        dialJob = scope.launch {
            transitionTo(IrohConnectionState.Dialing, "dial")
            val dialAttempt = runCatching {
                transitionTo(IrohConnectionState.Handshaking, "handshake")
                dialer(config)
            }
            dialAttempt.onSuccess { handle ->
                mutex.withLock {
                    if (closed || authFailed || dialConfig != config || currentConfig != config) {
                        if (!result.isCompleted) result.completeExceptionally(CancellationException("stale Iroh dial result ignored"))
                        scope.launch { handle.close("stale_dial") }
                        return@withLock
                    }
                    currentHandle = handle
                    currentConfig = config
                    failureCount = 0
                    dialResult = null
                    dialJob = null
                    dialConfig = null
                    transitionTo(IrohConnectionState.Ready(handle), "ready")
                    if (!result.isCompleted) result.complete(handle)
                }
            }.onFailure { error ->
                mutex.withLock {
                    if (dialConfig != config) {
                        if (!result.isCompleted) result.completeExceptionally(CancellationException("stale Iroh dial failure ignored"))
                        return@withLock
                    }
                    currentHandle = null
                    dialResult = null
                    dialJob = null
                    dialConfig = null
                    if (error is IrohAuthFailure) {
                        authFailed = true
                        redialJob?.cancel()
                        redialJob = null
                        redialReadyAtMs = null
                        transitionTo(IrohConnectionState.AuthFailed(error.message ?: "Iroh auth failed"), "auth_failed")
                    } else {
                        failureCount += 1
                        transitionTo(IrohConnectionState.Degraded(error.message ?: error.toString()), "dial_failed")
                        if (!closed) scheduleRedialLocked(error.message ?: error.toString())
                    }
                    if (!result.isCompleted) result.completeExceptionally(error)
                }
            }
        }
        return result
    }

    private fun scheduleRedialLocked(reason: String) {
        if (redialJob?.isActive == true || closed || authFailed) return
        val attempt = failureCount + 1
        val delayMs = backoffPolicy.delayMs(attempt, randomJitterMs)
        redialReadyAtMs = System.currentTimeMillis() + delayMs
        redialJob = scope.launch {
            Telemetry.event("IrohSupervisor", "redial.scheduled", "reason" to reason, "delayMs" to delayMs.toString(), "attempt" to attempt.toString())
            delay(delayMs)
            val config = configProvider() ?: return@launch
            mutex.withLock {
                redialReadyAtMs = null
                if (!closed && !authFailed && currentHandle == null && dialJob?.isActive != true) {
                    startDialLocked(config)
                }
            }
        }
    }

    private fun cancelDialLocked(reason: String) {
        dialJob?.cancel(CancellationException(reason))
        dialJob = null
        dialConfig = null
        dialResult?.cancel(CancellationException(reason))
        dialResult = null
    }

    private fun authFailureMessage(): String = when (val value = state.value) {
        is IrohConnectionState.AuthFailed -> value.reason
        else -> "Iroh auth failed"
    }

    private fun transitionTo(next: IrohConnectionState, reason: String) {
        val previous = _state.value
        if (previous::class == next::class && previous == next) return
        _state.value = next
        onStateChanged(next)
        Telemetry.event(
            "IrohSupervisor", "state",
            "from" to previous.name,
            "to" to next.name,
            "reason" to reason,
        )
    }

    data class BackoffPolicy(
        val initialMs: Long = 500L,
        val maxMs: Long = 8_000L,
        val jitterMs: Long = 250L,
    ) {
        fun delayMs(attempt: Int, jitter: (Long) -> Long): Long {
            val exponent = (attempt - 1).coerceAtLeast(0).coerceAtMost(30)
            val base = initialMs * (1L shl exponent)
            return min(maxMs, base) + jitter(jitterMs)
        }
    }
}

private val IrohConnectionState.name: String
    get() = when (this) {
        IrohConnectionState.Disconnected -> "Disconnected"
        IrohConnectionState.Dialing -> "Dialing"
        IrohConnectionState.Handshaking -> "Handshaking"
        is IrohConnectionState.Ready -> "Ready"
        is IrohConnectionState.Degraded -> "Degraded"
        is IrohConnectionState.AuthFailed -> "AuthFailed"
        IrohConnectionState.Closed -> "Closed"
    }
