package com.letta.mobile.data.canvas

import com.letta.mobile.data.model.CanvasOpId
import com.letta.mobile.util.Telemetry
import kotlinx.coroutines.CancellationException

/**
 * A [CanvasSession]'s side of the op log's journal ([CanvasOpLog.unsettled], letta-mobile-by4vm.22).
 *
 * The op log (Room `canvas_ops` on Android, JSONL files on desktop) and the stored document (the
 * Automerge notebook) are two stores with no shared transaction. The session therefore always
 * appends first and writes the document second; the ops a document write carries are settled only
 * once that write succeeded. Whatever is left unsettled - the process died between the two, the
 * write threw, or a change gave up after appending - is folded into the document by the session's
 * next change, or when the canvas is next opened. Folding is idempotent: ops are projected
 * last-writer-wins, and an op the document already holds changes nothing and is simply settled.
 *
 * Not thread-safe: used only under the session's lock.
 */
internal class CanvasOpSettlement(private val canvasId: CanvasId, private val opLog: CanvasOpLog) {
    /** Op ids appended since the last document write, which that write carries. */
    private val carried = mutableListOf<CanvasOpId>()

    /** True until the log's unsettled ops have been checked: on open, and after any failed write. */
    private var recoveryDue = true

    /**
     * Starts a change under the session's lock; true when unsettled ops must be folded in first.
     * Ops a previous change appended without writing the document (it gave up midway) are left to
     * that recovery rather than settled by whichever write comes next.
     */
    fun begin(): Boolean {
        if (carried.isNotEmpty()) recoveryDue = true
        carried.clear()
        return recoveryDue
    }

    suspend fun append(op: CanvasOp) {
        opLog.append(canvasId, op)
        carried += op.canvasOpId
    }

    /** The ops to fold in now, oldest first, carried by the next write; null when the log cannot say. */
    suspend fun takeUnsettled(): List<CanvasOp>? = guarded("read") {
        opLog.unsettled(canvasId).also { pending ->
            recoveryDue = false
            pending.mapTo(carried) { it.canvasOpId }
        }
    }

    /** Runs the document write [write], then settles every op it carries. */
    suspend fun <T> writing(write: suspend () -> T): T {
        var written = false
        val result = try {
            write().also { written = true }
        } finally {
            if (!written) recoveryDue = true
        }
        settleCarried()
        return result
    }

    /** Settles the carried ops without a write: the document already held all of them. */
    suspend fun settleCarried() {
        val opIds = carried.toList()
        carried.clear()
        if (opIds.isEmpty()) return
        guarded("settle") { opLog.settle(canvasId, opIds) }
    }

    /**
     * Runs a recovery step. A failure is logged and leaves recovery due for the next change; the
     * session keeps working from the document it has rather than failing to open.
     */
    suspend fun recovering(step: suspend () -> Unit) {
        guarded("recover") { step() }
    }

    private suspend fun <T> guarded(stage: String, block: suspend () -> T): T? = try {
        block()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        recoveryDue = true
        carried.clear()
        Telemetry.error(TAG, "journal.$stage.failed", error, "canvasId" to canvasId.value)
        null
    }

    private companion object {
        const val TAG = "CanvasOpSettlement"
    }
}
