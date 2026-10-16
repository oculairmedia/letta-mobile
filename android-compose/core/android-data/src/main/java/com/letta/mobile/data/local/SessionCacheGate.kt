package com.letta.mobile.data.local

import dagger.Lazy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred

/**
 * letta-mobile-by4vm.94: one session generation's "the previous session's Room cache is gone"
 * barrier.
 *
 * The session graph factory no longer blocks on the cache clear; it starts the clear in the
 * background and hands the generation's repositories their DAOs as [GatedDao]s, which await this
 * gate before every Room access. So a read can never see the previous backend's rows, and a write
 * can never land before the delete and be wiped by it.
 *
 * A gate ends in exactly one of three states:
 * - open: the clear finished; every access proceeds.
 * - failed: the clear threw (or never ran); every access rethrows that failure rather than serve
 *   rows that may belong to the previous session.
 * - superseded: a newer generation was created first; access throws [SessionSupersededException],
 *   so a stale generation's queued write cannot land after the newer generation's clear.
 */
class SessionCacheGate private constructor(opened: Boolean) {
    private val cleared = CompletableDeferred<Unit>().apply { if (opened) complete(Unit) }

    /** Suspends until the clear finished; throws if it failed or this generation was superseded. */
    suspend fun await() {
        cleared.await()
    }

    /**
     * Runs [clear] unless this gate already settled (a superseded generation skips its clear: the
     * newer generation's own clear covers it), then opens it, or fails it with the clear's error.
     */
    internal suspend fun runClear(clear: suspend () -> Unit) {
        if (cleared.isCompleted) return
        try {
            clear()
            cleared.complete(Unit)
        } catch (cancelled: CancellationException) {
            cleared.completeExceptionally(cancelled)
            throw cancelled
        } catch (failure: Throwable) {
            // Fail closed, and keep the background job quiet: callers see the error on access.
            cleared.completeExceptionally(failure)
        }
    }

    /** A newer generation exists; anything still waiting on this one must not touch Room. */
    internal fun supersede() {
        cleared.completeExceptionally(SessionSupersededException())
    }

    /** The clear will never run (its job ended before it could); fail closed. */
    internal fun abandon(cause: Throwable) {
        cleared.completeExceptionally(cause)
    }

    companion object {
        /** A generation waiting on its clear. */
        internal fun pending(): SessionCacheGate = SessionCacheGate(opened = false)

        /** No clear to wait for: repositories built outside a session graph (tests, previews). */
        val Open: SessionCacheGate = SessionCacheGate(opened = true)
    }
}

/** Thrown to a superseded session generation's pending cache access; see [SessionCacheGate]. */
class SessionSupersededException : CancellationException("Session generation superseded before its cache cleared")

/**
 * A Room DAO a session generation may only reach once its [SessionCacheGate] opened. [get] also
 * resolves the [Lazy] only then, so Room still initialises off the main thread (letta-mobile-g2ff0).
 */
class GatedDao<T>(
    private val dao: Lazy<T>,
    private val gate: SessionCacheGate,
) {
    suspend fun get(): T {
        gate.await()
        return dao.get()
    }
}
