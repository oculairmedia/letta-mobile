package com.letta.mobile.data.document

import com.letta.mobile.util.Telemetry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * One document's storage faults: each is logged through [Telemetry] and kept (the latest
 * [MAX_FAULTS]) on [faults] for the surface showing the document. Storage failures are never
 * fatal and never silent.
 */
internal class DocumentFaults(
    private val tag: String,
    private val documentKey: String,
    private val clock: () -> Long,
) {
    private val held = MutableStateFlow<List<SharedDocumentFault>>(emptyList())
    val faults: StateFlow<List<SharedDocumentFault>> = held.asStateFlow()

    fun report(kind: SharedDocumentFault.Kind, message: String) {
        val fault = SharedDocumentFault(kind, documentKey, message, clock())
        Telemetry.event(
            tag, "fault.${kind.name.lowercase()}", "documentId" to documentKey, "message" to message,
            level = if (fault.isError) Telemetry.Level.ERROR else Telemetry.Level.INFO,
        )
        held.value = (held.value + fault).takeLast(MAX_FAULTS)
    }

    /** [error] while doing [what], reported as a [kind] fault with its stack in telemetry. */
    fun failed(kind: SharedDocumentFault.Kind, what: String, error: Throwable) {
        Telemetry.error(tag, "storage.$what", error, "documentId" to documentKey)
        report(kind, "$what failed: ${error.message ?: error::class.simpleName}")
    }

    /** Runs [action]; a failure becomes a [kind] fault and [fallback], never a crash. */
    suspend fun <T> guarded(kind: SharedDocumentFault.Kind, what: String, fallback: T, action: suspend () -> T): T =
        attempt(action).getOrElse { error ->
            failed(kind, what, error)
            fallback
        }

    suspend fun guarded(kind: SharedDocumentFault.Kind, what: String, action: suspend () -> Unit) =
        guarded(kind, what, Unit, action)

    private companion object {
        const val MAX_FAULTS = 32
    }
}

/** [action]'s result, or the exception it threw; cancellation is never caught. */
internal suspend fun <T> attempt(action: suspend () -> T): Result<T> =
    try {
        Result.success(action())
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        Result.failure(error)
    }
