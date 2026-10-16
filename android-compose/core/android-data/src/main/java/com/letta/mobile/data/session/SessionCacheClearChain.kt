package com.letta.mobile.data.session

import com.letta.mobile.data.local.SessionCacheGate
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * letta-mobile-by4vm.94: starts each session generation's cache clear in the background, in
 * creation order, and returns the [SessionCacheGate] that generation's DAOs wait on.
 *
 * - Clears run strictly one after another: a generation's clear joins the previous one first, so an
 *   older generation's delete can never land after a newer generation's clear and wipe rows the
 *   newer one already wrote behind its open gate.
 * - Starting a generation supersedes the previous gate: anything of the old generation still
 *   waiting fails instead of writing old-session rows after the new clear, and an old clear that
 *   has not started yet is skipped (the new clear covers it).
 * - The clear runs in the caller's long-lived scope, not the graph's, so closing a graph never
 *   cancels a delete half-way; if the job is cancelled before it runs, the gate fails closed.
 */
internal class SessionCacheClearChain(
    private val clearContext: CoroutineContext,
    private val clear: suspend () -> Unit,
) {
    private val lock = Any()
    private var previousClear: Job? = null
    private var currentGate: SessionCacheGate? = null

    fun startGeneration(scope: CoroutineScope): SessionCacheGate = synchronized(lock) {
        val gate = SessionCacheGate.pending()
        currentGate?.supersede()
        currentGate = gate
        val previous = previousClear
        val job = scope.launch(clearContext) {
            previous?.join()
            gate.runClear(clear)
        }
        job.invokeOnCompletion { cause -> if (cause != null) gate.abandon(cause) }
        previousClear = job
        gate
    }
}
