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
 * Encapsulates:
 * - Generation-keyed [GenerationRetryState] so concurrent or stale RPCs across generations/handles do not bleed failure counts or resets.
 * - Retry on same connection vs. escalation to reconnect logic.
 * - In-flight RPC tracking for liveness probe congestion gating.
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
        val attempt = acquireStableReadyHandle()
        val retryState = retryStateFor(attempt.generation)
        val inFlightToken = retryState.beginAdminRpc()
        try {
            return executeTracked(method, path, body, attempt.handle, attempt.generation, retryState)
        } finally {
            retryState.endAdminRpc(inFlightToken)
        }
    }

    /**
     * A handle acquired while Ready changes cannot safely be attributed to either
     * adjacent generation. Retry until the generation is stable around ready().
     */
    private suspend fun acquireStableReadyHandle(): ReadyHandle {
        while (true) {
            val generationBeforeReady = connectionGeneration()
            val handle = supervisor.ready()
            if (connectionGeneration() == generationBeforeReady) {
                return ReadyHandle(handle, generationBeforeReady)
            }
        }
    }

    private data class ReadyHandle(
        val handle: IrohConnectionHandle,
        val generation: Long,
    )

    private suspend fun executeTracked(
        method: String,
        path: String,
        body: String?,
        first: IrohConnectionHandle,
        callGeneration: Long,
        retryState: GenerationRetryState,
    ): AppServerInboundFrame.AdminRpcResponse {
        val firstAttempt = runCatching {
            first.adminRpc(method = method, path = path, body = body)
        }
        if (firstAttempt.isSuccess) {
            onAttemptSuccess(callGeneration, retryState)
            return firstAttempt.getOrThrow()
        }

        val firstError = firstAttempt.exceptionOrNull()!!
        validateRetryableError(first, firstError, method, path, callGeneration)

        val failures = retryState.recordFailure()
        val idleMs = retryState.millisSinceLastStream()
        val shouldEscalate = failures >= ADMIN_RPC_FAILURE_THRESHOLD && idleMs > STREAM_IDLE_THRESHOLD_MS

        return if (!shouldEscalate) {
            retryOnSameConnection(first, firstError, failures, idleMs, method, path, body, callGeneration, retryState)
        } else {
            escalateReconnect(first, firstError, failures, idleMs, method, path, body)
        }
    }

    private suspend fun onAttemptSuccess(callGeneration: Long, retryState: GenerationRetryState) {
        if (connectionGeneration() == callGeneration) {
            retryState.reset()
            retryState.recordProofOfLife()
        }
    }

    private fun validateRetryableError(
        first: IrohConnectionHandle,
        firstError: Throwable,
        method: String,
        path: String,
        callGeneration: Long,
    ) {
        if (firstError is CancellationException) throw firstError
        if (firstError.isAdminRpcPayloadError()) throw firstError
        if (!firstError.isConnectionLostClass()) throw firstError
        if (!method.isReadOnlyAdminRpcMethod()) throw firstError

        if (first.isConnectionAlive) {
            Telemetry.event(
                "IrohTransport", "admin_rpc.request_isolated",
                "method" to method,
                "path" to path,
                "error" to (firstError.message ?: firstError.toString()),
                "class" to firstError::class.simpleName,
            )
            throw firstError
        }

        if (connectionGeneration() != callGeneration) {
            Telemetry.event(
                "IrohTransport", "admin_rpc.stale_generation_ignored",
                "callGeneration" to callGeneration.toString(),
                "currentGeneration" to connectionGeneration().toString(),
                "method" to method,
            )
            throw firstError
        }
    }

    private suspend fun retryOnSameConnection(
        first: IrohConnectionHandle,
        firstError: Throwable,
        failures: Int,
        idleMs: Long,
        method: String,
        path: String,
        body: String?,
        callGeneration: Long,
        retryState: GenerationRetryState,
    ): AppServerInboundFrame.AdminRpcResponse {
        Telemetry.event(
            "IrohTransport", "admin_rpc.retry.same_connection",
            "method" to method,
            "path" to path,
            "error" to (firstError.message ?: firstError.toString()),
            "class" to firstError::class.simpleName,
            "consecutiveFailures" to failures.toString(),
            "idleMs" to idleMs.toString(),
        )
        return runCatching {
            first.adminRpc(method = method, path = path, body = body)
        }.getOrElse { retryError ->
            if (retryError is CancellationException) throw retryError
            if (connectionGeneration() != callGeneration) throw retryError
            escalateReconnect(first, retryError, failures + 1, idleMs, method, path, body)
        }.also {
            onAttemptSuccess(callGeneration, retryState)
        }
    }

    private suspend fun escalateReconnect(
        first: IrohConnectionHandle,
        error: Throwable,
        failures: Int,
        idleMs: Long,
        method: String,
        path: String,
        body: String?,
    ): AppServerInboundFrame.AdminRpcResponse {
        Telemetry.event(
            "IrohTransport", "admin_rpc.escalate.reconnect",
            "method" to method,
            "path" to path,
            "error" to (error.message ?: error.toString()),
            "class" to error::class.simpleName,
            "consecutiveFailures" to failures.toString(),
            "idleMs" to idleMs.toString(),
        )
        supervisor.onConnectionLost("admin_rpc_failed_after_retry: ${error.message ?: error.toString()}", first)
        val newHandle = supervisor.ready()
        val nextGeneration = connectionGeneration()
        val nextRetryState = retryStateFor(nextGeneration)
        return newHandle.adminRpc(method = method, path = path, body = body).also {
            nextRetryState.reset()
            nextRetryState.recordProofOfLife()
        }
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

    private fun String.isReadOnlyAdminRpcMethod(): Boolean = this in READ_ONLY_ADMIN_RPC_METHODS

    internal class GenerationRetryState(val generation: Long) {
        private val mutex = Mutex()
        @Volatile var consecutiveFailures = 0
        @Volatile private var lastProofOfLifeMs = System.currentTimeMillis()
        private val inFlightStartByToken = ConcurrentHashMap<Long, Long>()
        private val nextInFlightToken = AtomicLong(0L)

        suspend fun recordFailure(): Int = mutex.withLock {
            consecutiveFailures += 1
            consecutiveFailures
        }

        suspend fun reset() = mutex.withLock {
            consecutiveFailures = 0
        }

        fun recordProofOfLife() {
            lastProofOfLifeMs = System.currentTimeMillis()
        }

        fun recordStreamActivity() = recordProofOfLife()

        fun millisSinceLastStream(): Long = System.currentTimeMillis() - lastProofOfLifeMs

        fun beginAdminRpc(): Long {
            val token = nextInFlightToken.incrementAndGet()
            inFlightStartByToken[token] = System.currentTimeMillis()
            return token
        }

        fun endAdminRpc(token: Long) {
            inFlightStartByToken.remove(token)
        }

        fun youngInFlightAdminRpcCount(graceMs: Long = IrohLivenessProbe.CONGESTION_GRACE_MS): Int {
            val now = System.currentTimeMillis()
            var count = 0
            for (startMs in inFlightStartByToken.values) {
                val age = now - startMs
                if (age in 0 until graceMs) count += 1
            }
            return count
        }
    }

    companion object {
        private const val RETAINED_GENERATIONS = 4L
        internal const val ADMIN_RPC_FAILURE_THRESHOLD = 3
        internal const val STREAM_IDLE_THRESHOLD_MS = 30_000L

        private val READ_ONLY_ADMIN_RPC_METHODS = setOf(
            "message.list",
            "message.get",
            "conversation.list",
            "conversation.get",
            "agent.get",
            "agent.list",
            "agent.count",
            "agent.context",
            "tool.get",
            "tool.list",
            "block.get",
            "block.list",
            "block.list_agent",
            "skill.get",
            "skill.list",
            "skill.list_agent",
            "slash_command.list",
            "slash_command.list_agent",
            "schedule.get",
            "schedule.list",
            "project.get",
            "project.list",
            "project.beadsRemoteStatus",
            "cron.list",
            "cron.get",
            "subagent.list",
            "subagent.todos",
            "health.check",
            "model.list",
            "goal.get",
        )
    }
}
