package com.letta.mobile.data.transport.iroh

import com.letta.mobile.data.transport.appserver.AppServerInboundFrame
import com.letta.mobile.util.Telemetry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Generation-scoped Admin RPC executor and retry/escalation state for [IrohChannelTransport].
 *
 * Encapsulates generation-keyed retry state, same-connection retry, reconnect escalation,
 * and in-flight RPC tracking for liveness-probe congestion gating.
 */
internal class IrohAdminRpcExecutor(
    private val supervisor: IrohConnectionSupervisor,
    private val connectionGeneration: () -> Long,
    private val recordViewedConversation: (method: String, path: String) -> Unit,
) {
    private val retryStates = ConcurrentHashMap<Long, GenerationRetryState>()

    fun retryStateFor(generation: Long): GenerationRetryState =
        retryStates.computeIfAbsent(generation) {
            retryStates.keys.removeIf { it < generation - RETAINED_GENERATIONS }
            GenerationRetryState(generation)
        }

    fun currentRetryState(): GenerationRetryState = retryStateFor(connectionGeneration())

    suspend fun execute(method: String, path: String, body: String?): AppServerInboundFrame.AdminRpcResponse {
        recordViewedConversation(method, path)
        val request = AdminRpcRequest(method, path, body)
        val attempt = acquireStableReadyHandle()
        val retryState = retryStateFor(attempt.generation)
        val token = retryState.beginAdminRpc()
        val call = TrackedCall(request, attempt, retryState)
        try {
            return executeTracked(call)
        } finally {
            retryState.endAdminRpc(token)
        }
    }

    /** Retries acquisition until a ready handle is bracketed by the same generation. */
    private suspend fun acquireStableReadyHandle(): ReadyHandle {
        while (true) {
            val generation = connectionGeneration()
            val handle = supervisor.ready()
            if (connectionGeneration() == generation) return ReadyHandle(handle, generation)
        }
    }

    private suspend fun executeTracked(call: TrackedCall): AppServerInboundFrame.AdminRpcResponse {
        val firstAttempt = runCatching { call.handle.adminRpc(call.request.method, call.request.path, call.request.body) }
        if (firstAttempt.isSuccess) return firstAttempt.getOrThrow().also { onAttemptSuccess(call) }

        val error = firstAttempt.exceptionOrNull()!!
        validateRetryableError(call, error)
        val metrics = RetryMetrics(call.retryState.recordFailure(), call.retryState.millisSinceLastStream())
        return if (metrics.shouldEscalate) escalateReconnect(call, error, metrics) else retryOnSameConnection(call, error, metrics)
    }

    private suspend fun onAttemptSuccess(call: TrackedCall) {
        if (connectionGeneration() == call.generation) {
            call.retryState.reset()
            call.retryState.recordProofOfLife()
        }
    }

    private fun validateRetryableError(call: TrackedCall, error: Throwable) {
        throwIfNotRetryable(call.request, error)
        throwIfConnectionStillAlive(call, error)
        throwIfGenerationChanged(call, error)
    }

    private fun throwIfNotRetryable(request: AdminRpcRequest, error: Throwable) {
        if (error is CancellationException) throw error
        if (error.isAdminRpcPayloadError()) throw error
        if (!error.isConnectionLostClass()) throw error
        if (!request.method.isReadOnlyAdminRpcMethod()) throw error
    }

    private fun throwIfConnectionStillAlive(call: TrackedCall, error: Throwable) {
        if (!call.handle.isConnectionAlive) return
        Telemetry.event(
            "IrohTransport", "admin_rpc.request_isolated",
            "method" to call.request.method,
            "path" to call.request.path,
            "error" to error.description(),
            "class" to error::class.simpleName,
        )
        throw error
    }

    private fun throwIfGenerationChanged(call: TrackedCall, error: Throwable) {
        val currentGeneration = connectionGeneration()
        if (currentGeneration == call.generation) return
        Telemetry.event(
            "IrohTransport", "admin_rpc.stale_generation_ignored",
            "callGeneration" to call.generation.toString(),
            "currentGeneration" to currentGeneration.toString(),
            "method" to call.request.method,
        )
        throw error
    }

    private suspend fun retryOnSameConnection(
        call: TrackedCall,
        error: Throwable,
        metrics: RetryMetrics,
    ): AppServerInboundFrame.AdminRpcResponse {
        recordRetry("admin_rpc.retry.same_connection", call, error, metrics)
        return runCatching { call.handle.adminRpc(call.request.method, call.request.path, call.request.body) }
            .getOrElse { retryError ->
                if (retryError is CancellationException || connectionGeneration() != call.generation) throw retryError
                escalateReconnect(call, retryError, metrics.nextFailure())
            }
            .also { onAttemptSuccess(call) }
    }

    private suspend fun escalateReconnect(
        call: TrackedCall,
        error: Throwable,
        metrics: RetryMetrics,
    ): AppServerInboundFrame.AdminRpcResponse {
        recordRetry("admin_rpc.escalate.reconnect", call, error, metrics)
        supervisor.onConnectionLost("admin_rpc_failed_after_retry: ${error.description()}", call.handle)
        val handle = supervisor.ready()
        val retryState = retryStateFor(connectionGeneration())
        return handle.adminRpc(call.request.method, call.request.path, call.request.body).also {
            retryState.reset()
            retryState.recordProofOfLife()
        }
    }

    private fun recordRetry(event: String, call: TrackedCall, error: Throwable, metrics: RetryMetrics) {
        Telemetry.event(
            "IrohTransport", event,
            "method" to call.request.method,
            "path" to call.request.path,
            "error" to error.description(),
            "class" to error::class.simpleName,
            "consecutiveFailures" to metrics.failures.toString(),
            "idleMs" to metrics.idleMs.toString(),
        )
    }

    fun recordStreamActivity() {
        currentRetryState().recordProofOfLife()
    }

    fun millisSinceLastProofOfLife(): Long = currentRetryState().millisSinceLastStream()

    fun youngInFlightAdminRpcCount(graceMs: Long = IrohLivenessProbe.CONGESTION_GRACE_MS): Int =
        currentRetryState().youngInFlightAdminRpcCount(graceMs)

    fun clear() {
        retryStates.clear()
    }

    private data class AdminRpcRequest(val method: String, val path: String, val body: String?)
    private data class ReadyHandle(val handle: IrohConnectionHandle, val generation: Long)
    private data class TrackedCall(val request: AdminRpcRequest, val readyHandle: ReadyHandle, val retryState: GenerationRetryState) {
        val handle: IrohConnectionHandle get() = readyHandle.handle
        val generation: Long get() = readyHandle.generation
    }
    private data class RetryMetrics(val failures: Int, val idleMs: Long) {
        val shouldEscalate: Boolean get() = failures >= ADMIN_RPC_FAILURE_THRESHOLD && idleMs > STREAM_IDLE_THRESHOLD_MS
        fun nextFailure(): RetryMetrics = copy(failures = failures + 1)
    }

    internal class GenerationRetryState(val generation: Long) {
        private val mutex = Mutex()
        @Volatile var consecutiveFailures = 0
        @Volatile private var lastProofOfLifeMs = System.currentTimeMillis()
        private val inFlightStartByToken = ConcurrentHashMap<Long, Long>()
        private val nextInFlightToken = AtomicLong(0L)

        suspend fun recordFailure(): Int = mutex.withLock { ++consecutiveFailures }
        suspend fun reset() = mutex.withLock { consecutiveFailures = 0 }
        fun recordProofOfLife() { lastProofOfLifeMs = System.currentTimeMillis() }
        fun recordStreamActivity() = recordProofOfLife()
        fun millisSinceLastStream(): Long = System.currentTimeMillis() - lastProofOfLifeMs
        fun beginAdminRpc(): Long = nextInFlightToken.incrementAndGet().also { inFlightStartByToken[it] = System.currentTimeMillis() }
        fun endAdminRpc(token: Long) { inFlightStartByToken.remove(token) }
        fun youngInFlightAdminRpcCount(graceMs: Long = IrohLivenessProbe.CONGESTION_GRACE_MS): Int {
            val now = System.currentTimeMillis()
            return inFlightStartByToken.values.count { now - it in 0 until graceMs }
        }
    }

    private fun Throwable.description(): String = message ?: toString()
    private fun String.isReadOnlyAdminRpcMethod(): Boolean = this in READ_ONLY_ADMIN_RPC_METHODS

    companion object {
        private const val RETAINED_GENERATIONS = 4L
        internal const val ADMIN_RPC_FAILURE_THRESHOLD = 3
        internal const val STREAM_IDLE_THRESHOLD_MS = 30_000L
        private val READ_ONLY_ADMIN_RPC_METHODS = setOf(
            "message.list", "message.get", "conversation.list", "conversation.get", "agent.get", "agent.list", "agent.count", "agent.context", "tool.get", "tool.list", "block.get", "block.list", "block.list_agent", "skill.get", "skill.list", "skill.list_agent", "slash_command.list", "slash_command.list_agent", "schedule.get", "schedule.list", "project.get", "project.list", "project.beadsRemoteStatus", "cron.list", "cron.get", "subagent.list", "subagent.todos", "health.check", "model.list", "goal.get",
        )
    }
}
