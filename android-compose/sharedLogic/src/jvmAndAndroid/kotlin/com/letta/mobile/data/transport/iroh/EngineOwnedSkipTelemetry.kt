package com.letta.mobile.data.transport.iroh

import com.letta.mobile.util.Telemetry
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** Bounded, thread-safe aggregation for engine-owned observer-frame skips. */
internal class EngineOwnedSkipTelemetry(
    private val flushEvery: Int = DEFAULT_FLUSH_EVERY,
    private val sink: (EngineOwnedSkipSnapshot) -> Unit = ::emitEngineOwnedSkipSnapshot,
) {
    private val lock = ReentrantLock()
    private var currentTurn: EngineOwnedTurn? = null
    private val typeCounts = LinkedHashMap<String, Int>()
    private var total = 0

    init {
        require(flushEvery > 0) { "flushEvery must be positive" }
    }

    /** Records one skip, flushing on a turn boundary or at the fixed threshold. */
    fun record(conversationId: String, turnId: String, deltaType: String) = lock.withLock {
        val nextTurn = EngineOwnedTurn(conversationId, turnId)
        if (currentTurn != null && currentTurn != nextTurn) flushLocked()
        currentTurn = nextTurn
        val safeType = safeDeltaType(deltaType)
        typeCounts[safeType] = (typeCounts[safeType] ?: 0) + 1
        total++
        if (total >= flushEvery) flushLocked()
    }

    /** Emits and clears the pending aggregate while retaining the active turn. */
    fun flush() = lock.withLock {
        flushLocked()
    }

    /** Flushes pending data and drops turn identity during cancellation/reset. */
    fun endTurn() = lock.withLock {
        flushLocked()
        currentTurn = null
    }

    private fun flushLocked() {
        if (total == 0) return
        val turn = currentTurn ?: return
        sink(
            EngineOwnedSkipSnapshot(
                conversationId = turn.conversationId,
                turnId = turn.turnId,
                skipped = total,
                deltaTypes = typeCounts.entries
                    .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
                    .take(MAX_TYPE_LABELS)
                    .joinToString(",") { "${it.key}=${it.value}" },
            ),
        )
        typeCounts.clear()
        total = 0
    }

    /** Never place arbitrary wire content in telemetry labels. */
    private fun safeDeltaType(deltaType: String): String =
        if (deltaType in SAFE_DELTA_TYPES) deltaType else OTHER_DELTA_TYPE

    companion object {
        const val DEFAULT_FLUSH_EVERY = 64
        private const val MAX_TYPE_LABELS = 8
        private const val OTHER_DELTA_TYPE = "<other>"
        private val SAFE_DELTA_TYPES = setOf(
            "assistant_message",
            "reasoning_message",
            "tool_call_message",
            "approval_request_message",
            "tool_return_message",
            "stop_reason",
            "loop_error",
            "error_message",
            "<non-object>",
            "<untyped>",
        )
    }
}

internal data class EngineOwnedSkipSnapshot(
    val conversationId: String,
    val turnId: String,
    val skipped: Int,
    val deltaTypes: String,
)

private fun emitEngineOwnedSkipSnapshot(snapshot: EngineOwnedSkipSnapshot) {
    Telemetry.event(
        "IrohObserver",
        "ingest.skip_engine_owned_aggregated",
        "conversationId" to snapshot.conversationId,
        "turnId" to snapshot.turnId,
        "skipped" to snapshot.skipped.toString(),
        "deltaTypes" to snapshot.deltaTypes,
    )
}

private data class EngineOwnedTurn(
    val conversationId: String,
    val turnId: String,
)
