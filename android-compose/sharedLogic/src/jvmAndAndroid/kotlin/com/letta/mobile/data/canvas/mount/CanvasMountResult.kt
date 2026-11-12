package com.letta.mobile.data.canvas.mount

import com.letta.mobile.data.model.CanvasId
import java.io.IOException
import java.nio.charset.CharacterCodingException

/**
 * Outcome of one [AuthorizedCanvasMounts] call. Only the success outcomes carry a [CanvasId], and only
 * one the caller was authorized to see or had just been authorized to store.
 */
sealed interface CanvasMountResult {
    /** A read found [canvas] and the principal may see it. */
    data class Found(
        /** The stored canvas. */
        val canvas: CanvasId,
    ) : CanvasMountResult

    /** A bind-if-absent finished with [canvas] as the durable winner, which the principal may see. */
    data class Bound(
        /** The winner: an existing binding, or the caller's own proposal when none existed. */
        val canvas: CanvasId,
    ) : CanvasMountResult

    /** A conversation binding now holds the caller's authorized [canvas]. */
    data class Replaced(
        /** The canvas the caller stored. */
        val canvas: CanvasId,
    ) : CanvasMountResult

    /** An agent default now holds the caller's authorized [canvas]. */
    data class DefaultSet(
        /** The canvas the caller stored. */
        val canvas: CanvasId,
    ) : CanvasMountResult

    /** Authorized for the scope, and the addressed record does not exist. Never returned before scope authorization. */
    data object None : CanvasMountResult

    /**
     * Not permitted. Carries nothing: not the winner, not which check failed, and not whether a record
     * exists, so it is identical for every denial.
     */
    data object Denied : CanvasMountResult

    /** An id was blank or longer than [AuthorizedCanvasMounts.MAX_ID_BYTES]; nothing was asked or touched. */
    data object InvalidInput : CanvasMountResult

    /**
     * Storage failed after authorization. Carries only a fixed [reason], never a path, message or id.
     * After a mutation it does not say whether the write was applied: the store may replace a record and
     * then fail a later durability barrier. Re-read to learn the current state.
     */
    data class StorageFailure(
        /** The fixed failure category. */
        val reason: CanvasMountFailure,
    ) : CanvasMountResult
}

/** Fixed categories for [CanvasMountResult.StorageFailure]. */
enum class CanvasMountFailure {
    /** On-disk state failed validation: corrupt, misplaced, foreign, oversized or not a regular file. */
    INVALID_STATE,

    /** The file system failed to read, write, lock or replace. */
    IO_FAILURE,

    /** The host could not provide a required durability guarantee. */
    UNSUPPORTED,

    /** Any other failure. */
    UNEXPECTED,
    ;

    internal companion object {
        /** Classifies [failure] by type alone; its message is never read. VM errors propagate. */
        fun of(failure: Throwable): CanvasMountFailure {
            if (failure is VirtualMachineError) throw failure
            return when (failure) {
                is CharacterCodingException, is IllegalArgumentException, is IllegalStateException -> INVALID_STATE
                is IOException -> IO_FAILURE
                is UnsupportedOperationException -> UNSUPPORTED
                else -> UNEXPECTED
            }
        }
    }
}
