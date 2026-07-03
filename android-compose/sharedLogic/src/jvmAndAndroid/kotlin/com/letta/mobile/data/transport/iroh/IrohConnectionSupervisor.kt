package com.letta.mobile.data.transport.iroh

import com.letta.mobile.data.runtime.AppServerTurnEngine
import com.letta.mobile.data.transport.appserver.AppServerInboundFrame
import com.letta.mobile.util.Telemetry
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
    val close: suspend (String) -> Unit,
) {
    suspend fun adminRpc(method: String, path: String, body: String?): AppServerInboundFrame.AdminRpcResponse =
        transport?.adminRpc(method = method, path = path, body = body)
            ?: error("Iroh admin_rpc requested without an active transport")
}

sealed interface IrohConnectionState {
    data object Disconnected : IrohConnectionState
    data object Dialing : IrohConnectionState
    data object Handshaking : IrohConnectionState
    data class Ready(val handle: IrohConnectionHandle) : IrohConnectionState
    data class Degraded(val reason: String) : IrohConnectionState
    data object Closed : IrohConnectionState
}

class IrohConnectionSupervisor(
    private val scope: CoroutineScope,
    private val configProvider: () -> IrohConnectConfig?,
    private val dialer: suspend (IrohConnectConfig) -> IrohConnectionHandle,
    private val backoffPolicy: BackoffPolicy = BackoffPolicy(),
    private val randomJitterMs: (Long) -> Long = { bound -> if (bound <= 0) 0L else Random.nextLong(bound + 1) },
) {
    private val mutex = Mutex()
    private val _state = MutableStateFlow<IrohConnectionState>(IrohConnectionState.Disconnected)
    val state: StateFlow<IrohConnectionState> = _state.asStateFlow()

    private var dialJob: Job? = null
    private var dialResult: CompletableDeferred<IrohConnectionHandle>? = null
    private var redialJob: Job? = null
    private var currentHandle: IrohConnectionHandle? = null
    private var currentConfig: IrohConnectConfig? = null
    private var closed = false
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
        val desired = configProvider()
        val staleHandle = mutex.withLock {
            val active = currentConfig
            if (desired != null && active != null && active != desired) currentHandle else null
        }
        if (staleHandle != null) {
            staleHandle.close("config_changed")
            onConnectionLost("config_changed")
        }
    }

    fun onConnectionLost(reason: String) {
        scope.launch {
            val shouldRedial = mutex.withLock {
                if (closed) return@withLock false
                currentHandle = null
                dialResult = null
                dialJob = null
                transitionTo(IrohConnectionState.Degraded(reason), reason)
                true
            }
            if (shouldRedial) scheduleRedial(reason)
        }
    }

    suspend fun close(reason: String = "closed") {
        val handle = mutex.withLock {
            closed = true
            redialJob?.cancel()
            redialJob = null
            dialJob?.cancel()
            dialJob = null
            dialResult?.cancel()
            dialResult = null
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
        val desired = configProvider() ?: error("Iroh connection requested with no active iroh:// config")
        val ready = currentHandle
        if (ready != null && currentConfig == desired && state.value is IrohConnectionState.Ready) {
            return@withLock CompletableDeferred(ready)
        }
        if (ready != null && currentConfig != desired) {
            scope.launch { ready.close("config_changed") }
            currentHandle = null
            transitionTo(IrohConnectionState.Degraded("config_changed"), "config_changed")
        }
        dialResult?.takeIf { dialJob?.isActive == true }?.let { return@withLock it }
        startDialLocked(desired)
    }

    private fun startDialLocked(config: IrohConnectConfig): CompletableDeferred<IrohConnectionHandle> {
        redialJob?.cancel()
        redialJob = null
        currentConfig = config
        val result = CompletableDeferred<IrohConnectionHandle>()
        dialResult = result
        dialJob = scope.launch {
            transitionTo(IrohConnectionState.Dialing, "dial")
            val dialAttempt = runCatching {
                transitionTo(IrohConnectionState.Handshaking, "handshake")
                dialer(config)
            }
            dialAttempt.onSuccess { handle ->
                mutex.withLock {
                    currentHandle = handle
                    currentConfig = config
                    failureCount = 0
                    dialResult = null
                    dialJob = null
                    transitionTo(IrohConnectionState.Ready(handle), "ready")
                    if (!result.isCompleted) result.complete(handle)
                }
            }.onFailure { error ->
                mutex.withLock {
                    currentHandle = null
                    dialResult = null
                    dialJob = null
                    failureCount += 1
                    transitionTo(IrohConnectionState.Degraded(error.message ?: error.toString()), "dial_failed")
                    if (!result.isCompleted) result.completeExceptionally(error)
                }
                scheduleRedial(error.message ?: error.toString())
            }
        }
        return result
    }

    private fun scheduleRedial(reason: String) {
        if (redialJob?.isActive == true) return
        redialJob = scope.launch {
            val attempt = failureCount + 1
            val delayMs = backoffPolicy.delayMs(attempt, randomJitterMs)
            Telemetry.event("IrohSupervisor", "redial.scheduled", "reason" to reason, "delayMs" to delayMs.toString(), "attempt" to attempt.toString())
            delay(delayMs)
            val config = configProvider() ?: return@launch
            mutex.withLock {
                if (!closed && currentHandle == null && dialJob?.isActive != true) {
                    startDialLocked(config)
                }
            }
        }
    }

    private fun transitionTo(next: IrohConnectionState, reason: String) {
        val previous = _state.value
        if (previous::class == next::class && previous == next) return
        _state.value = next
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
        IrohConnectionState.Closed -> "Closed"
    }
