package com.letta.mobile.data.canvas

import com.letta.mobile.util.attr
import com.letta.mobile.util.Telemetry
import com.letta.mobile.data.model.CanvasId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * One canvas store's storage faults: logged loudly, kept for the board to show, never thrown at
 * the caller. The notebook repository and the key-value board stores report through it.
 */
class CanvasStorageFaults {
    private val saveFailures = MutableStateFlow<Map<CanvasId?, Long>>(emptyMap())
    private val _faults = MutableStateFlow<List<CanvasStorageFault>>(emptyList())
    val faults: StateFlow<List<CanvasStorageFault>> = _faults.asStateFlow()

    /** Counts every failed save concerning [canvasId], including failures with no known board. */
    fun saveFailureGeneration(canvasId: CanvasId): Long = saveFailures.value.let {
        (it[canvasId] ?: 0L) + (it[null] ?: 0L)
    }

    fun record(fault: CanvasStorageFault, throwable: Throwable? = null) {
        if (fault.kind == CanvasStorageFault.Kind.SAVE_FAILED) {
            saveFailures.update { it + (fault.canvasId to ((it[fault.canvasId] ?: 0L) + 1L)) }
        }
        log(fault, throwable)
        _faults.update { current ->
            // One entry per document and kind; the first SAVE_FAILED time is when changes became at risk.
            val previous = current.firstOrNull { it.kind == fault.kind && it.documentId == fault.documentId }
            val kept = fault.copy(
                atEpochMs = previous?.atEpochMs ?: fault.atEpochMs,
                canvasId = fault.canvasId ?: previous?.canvasId,
            )
            (current.filterNot { it.kind == fault.kind && it.documentId == fault.documentId } + kept).takeLast(MAX_FAULTS)
        }
    }

    private fun log(fault: CanvasStorageFault, throwable: Throwable?) {
        val name = fault.kind.name.lowercase()
        if (throwable != null) {
            Telemetry.error(
                TAG, name, throwable,
                "documentId" attr fault.documentId, "canvasId" attr fault.canvasId?.value,
                "documentBytes" attr fault.documentBytes, "message" attr fault.message,
            )
        } else {
            Telemetry.event(
                TAG, name,
                "documentId" attr fault.documentId, "canvasId" attr fault.canvasId?.value,
                "documentBytes" attr fault.documentBytes, "message" attr fault.message,
                level = if (fault.isError) Telemetry.Level.ERROR else Telemetry.Level.WARN,
            )
        }
        // Telemetry can be switched off; a storage fault is still written to the platform log.
        println(
            "[${if (fault.isError) "ERROR" else "WARN"}] $TAG ${fault.kind} document=${fault.documentId} " +
                "canvas=${fault.canvasId?.value} bytes=${fault.documentBytes}: ${fault.message}" +
                (throwable?.let { " (${it.stackTraceToString()})" } ?: ""),
        )
    }

    companion object {
        const val TAG = "CanvasStorage"
        private const val MAX_FAULTS = 32
    }
}
