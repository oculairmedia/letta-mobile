package com.letta.mobile.data.transport.iroh

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

/** Tracks request failures, proof-of-life, and short-lived admin RPC congestion. */
internal class IrohAdminRpcRetryState {
    private val mutex = Mutex()
    @Volatile
    var consecutiveFailures = 0
    @Volatile
    private var lastProofOfLifeMs = System.currentTimeMillis()
    private val inFlightStartByToken = ConcurrentHashMap<Long, Long>()
    private val nextInFlightToken = java.util.concurrent.atomic.AtomicLong(0L)

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
