package com.letta.mobile.data.transport.iroh

import com.letta.mobile.data.transport.appserver.AppServerInboundFrame
import com.letta.mobile.util.Telemetry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/** A complete App Server admin RPC request. */
internal data class AdminRpcRequest(
    val method: String,
    val path: String,
    val body: String?,
)

/**
 * Generation-scoped Admin RPC executor and retry/escalation state for [IrohChannelTransport].
 *
 * A call captures a ready handle together with its generation before it is ever retried. This
 * prevents a stale failure from closing a newer connection, while retry state remains shared by
 * all calls that proved activity on the same generation.
 */
internal class IrohAdminRpcExecutor(
    private val dependencies: Dependencies,
) {
    internal data class Dependencies(
        val supervisor: IrohConnectionSupervisor,
        val connectionGeneration: () -> Long,
        val onRequestObserved: (AdminRpcRequest) -> Unit,
    )

    private val retryStates = ConcurrentHashMap<Long, GenerationRetryState>()
    private val retryPolicy = AdminRpcRetryPolicy(dependencies.connectionGeneration)

    fun retryStateFor(generation: Long): GenerationRetryState =
        retryStates.computeIfAbsent(generation) {
            retryStates.keys.removeIf { it < generation - RETAINED_GENERATIONS }
            GenerationRetryState(generation)
        }

    fun currentRetryState(): GenerationRetryState = retryStateFor(dependencies.connectionGeneration())

    suspend fun execute(request: AdminRpcRequest): AppServerInboundFrame.AdminRpcResponse {
        dependencies.onRequestObserved(request)
        val call = newTrackedCall(request)
        val token = call.retryState.beginAdminRpc()
        try {
            return executeTracked(call)
        } finally {
            call.retryState.endAdminRpc(token)
        }
    }

    /** Retries acquisition until a ready handle is bracketed by one generation. */
    private suspend fun newTrackedCall(request: AdminRpcRequest): TrackedCall {
        while (true) {
            val generation = dependencies.connectionGeneration()
            val handle = dependencies.supervisor.ready()
            if (dependencies.connectionGeneration() == generation) {
                return TrackedCall(request, ReadyHandle(handle, generation), retryStateFor(generation))
            }
        }
    }

    private suspend fun executeTracked(call: TrackedCall): AppServerInboundFrame.AdminRpcResponse {
        return try {
            call.execute()
        } catch (error: Throwable) {
            retryAfter(call, error)
        }
    }

    private suspend fun retryAfter(call: TrackedCall, error: Throwable): AppServerInboundFrame.AdminRpcResponse {
        when (retryPolicy.eligibility(call, error)) {
            RetryEligibility.Rejected -> throw error
            RetryEligibility.StaleGeneration -> throw error
            RetryEligibility.Eligible -> Unit
        }
        val metrics = RetryMetrics(call.retryState.recordFailure(), call.retryState.millisSinceLastStream())
        return if (metrics.shouldEscalate) reconnectAndRetry(ReconnectRequest(call, error, metrics)) else retryOnce(call, error, metrics)
    }

    private suspend fun retryOnce(
        call: TrackedCall,
        error: Throwable,
        metrics: RetryMetrics,
    ): AppServerInboundFrame.AdminRpcResponse {
        recordRetry("admin_rpc.retry.same_connection", call, error, metrics)
        return try {
            call.execute()
        } catch (retryError: Throwable) {
            if (retryError is CancellationException || dependencies.connectionGeneration() != call.generation) throw retryError
            reconnectAndRetry(ReconnectRequest(call, retryError, metrics.nextFailure()))
        }
    }

    private suspend fun reconnectAndRetry(request: ReconnectRequest): AppServerInboundFrame.AdminRpcResponse {
        recordRetry("admin_rpc.escalate.reconnect", request.call, request.error, request.metrics)
        dependencies.supervisor.onConnectionLost(request.reason, request.call.handle)
        val retryCall = newTrackedCall(request.call.request)
        return retryCall.execute().also { retryCall.markSuccess() }
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

    fun recordStreamActivity() = currentRetryState().recordProofOfLife()
    fun millisSinceLastProofOfLife(): Long = currentRetryState().millisSinceLastStream()
    fun youngInFlightAdminRpcCount(graceMs: Long = IrohLivenessProbe.CONGESTION_GRACE_MS): Int =
        currentRetryState().youngInFlightAdminRpcCount(graceMs)
    fun clear() = retryStates.clear()

    private data class ReadyHandle(val handle: IrohConnectionHandle, val generation: Long)
    private data class TrackedCall(
        val request: AdminRpcRequest,
        val readyHandle: ReadyHandle,
        val retryState: GenerationRetryState,
    ) {
        val handle: IrohConnectionHandle get() = readyHandle.handle
        val generation: Long get() = readyHandle.generation
        suspend fun execute(): AppServerInboundFrame.AdminRpcResponse =
            handle.adminRpc(request.method, request.path, request.body).also { markSuccess() }
        suspend fun markSuccess() {
            // A concurrent success is proof-of-life for this generation and deliberately
            // resets the generation-shared failure count.
            retryState.reset()
            retryState.recordProofOfLife()
        }
    }
    private data class ReconnectRequest(
        val call: TrackedCall,
        val error: Throwable,
        val metrics: RetryMetrics,
    ) {
        val reason: String get() = "admin_rpc_failed_after_retry: ${error.message ?: error}"
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
        fun millisSinceLastStream(): Long = System.currentTimeMillis() - lastProofOfLifeMs
        fun beginAdminRpc(): Long = nextInFlightToken.incrementAndGet().also { inFlightStartByToken[it] = System.currentTimeMillis() }
        fun endAdminRpc(token: Long) { inFlightStartByToken.remove(token) }
        fun youngInFlightAdminRpcCount(graceMs: Long = IrohLivenessProbe.CONGESTION_GRACE_MS): Int {
            val now = System.currentTimeMillis()
            return inFlightStartByToken.values.count { now - it in 0 until graceMs }
        }
    }

    private fun Throwable.description(): String = message ?: toString()

    private inner class AdminRpcRetryPolicy(
        private val currentGeneration: () -> Long,
    ) {
        fun eligibility(call: TrackedCall, error: Throwable): RetryEligibility {
            if (error is CancellationException || error.isAdminRpcPayloadError() || !error.isConnectionLostClass()) return RetryEligibility.Rejected
            if (!call.request.method.isReadOnlyAdminRpcMethod()) return RetryEligibility.Rejected
            if (call.handle.isConnectionAlive) {
                Telemetry.event("IrohTransport", "admin_rpc.request_isolated", "method" to call.request.method, "path" to call.request.path, "error" to error.description(), "class" to error::class.simpleName)
                return RetryEligibility.Rejected
            }
            if (currentGeneration() != call.generation) {
                Telemetry.event("IrohTransport", "admin_rpc.stale_generation_ignored", "callGeneration" to call.generation.toString(), "currentGeneration" to currentGeneration().toString(), "method" to call.request.method)
                return RetryEligibility.StaleGeneration
            }
            return RetryEligibility.Eligible
        }
    }

    private enum class RetryEligibility { Eligible, Rejected, StaleGeneration }
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
