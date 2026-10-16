package com.letta.mobile.data.canvas

import com.letta.mobile.util.Telemetry
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * One canvas store's storage faults: logged loudly, kept for the board to show, never thrown at
 * the caller. The notebook repository and the key-value board stores report through it.
 */
class CanvasStorageFaults {
    private val _faults = MutableStateFlow<List<CanvasStorageFault>>(emptyList())
    val faults: StateFlow<List<CanvasStorageFault>> = _faults.asStateFlow()

    fun record(fault: CanvasStorageFault, throwable: Throwable? = null) {
        log(fault, throwable)
        _faults.update { current ->
            // One entry per document and kind; the first SAVE_FAILED time is when changes became at risk.
            val previous = current.firstOrNull { it.kind == fault.kind && it.documentId == fault.documentId }
            val kept = fault.copy(
                atEpochMs = previous?.atEpochMs ?: fault.atEpochMs,
                canvasId = fault.canvasId ?: previous?.canvasId,
            )
            (current.filterNot { it === previous } + kept).takeLast(MAX_FAULTS)
        }
    }

    private fun log(fault: CanvasStorageFault, throwable: Throwable?) {
        val name = fault.kind.name.lowercase()
        if (throwable != null) {
            Telemetry.error(
                TAG, name, throwable,
                "documentId" to fault.documentId, "canvasId" to fault.canvasId?.value,
                "documentBytes" to fault.documentBytes, "message" to fault.message,
            )
        } else {
            Telemetry.event(
                TAG, name,
                "documentId" to fault.documentId, "canvasId" to fault.canvasId?.value,
                "documentBytes" to fault.documentBytes, "message" to fault.message,
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
