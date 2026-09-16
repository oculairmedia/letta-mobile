package com.letta.mobile.data.timeline

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Runtime must supply an epoch-fenced cursor repair; never a mutable-settings cursor store. */
class IndexedCanonicalTimelineMaintenance(
    private val coordinator: CanonicalTimelineCoordinator,
    private val scope: CoroutineScope,
    private val repairCommittedCursor: suspend (CanonicalTimelineCoordinator.Owner, Long? /* expected */, Long? /* committed */) -> Unit,
    private val reportFailure: (Throwable) -> Unit,
) : CanonicalTimelineMaintenance {
    private val mutex = Mutex()
    private val jobs = mutableMapOf<CanonicalTimelineCoordinator.Owner, Job>()

    override suspend fun turnStarted(owner: CanonicalTimelineCoordinator.Owner, runId: String?, turnId: String?) {
        mutex.withLock {
            jobs.remove(owner)?.cancel()
            owner.session.engine.advanceToolSweep(owner.selection)
        }
    }

    override suspend fun turnEnded(owner: CanonicalTimelineCoordinator.Owner, clean: Boolean) {
        mutex.withLock {
            jobs.remove(owner)?.cancel()
            val generation = owner.session.engine.advanceToolSweep(owner.selection)
            coordinator.retainRepair(owner)
            jobs[owner] = scope.launch(start = kotlinx.coroutines.CoroutineStart.ATOMIC) {
                try {
                    for (wait in DanglingToolCallResolver.DEFAULT_BACKOFF_MS) {
                        delay(wait)
                        val result = coordinator.reconcileRecent(owner)
                        check(result == TimelineEnginePageOutcome.Applied) { "Canonical sweep repair unavailable: $result" }
                    }
                    // Each settlement transaction is row/body bounded; new turns fence every batch.
                    while (owner.session.engine.settleToolSweep(owner.selection, generation) > 0) {
                        kotlinx.coroutines.yield()
                    }
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    reportFailure(failure)
                } finally {
                    val completed = kotlinx.coroutines.currentCoroutineContext()[Job]
                    kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                        mutex.withLock { if (jobs[owner] === completed) jobs.remove(owner) }
                        coordinator.releaseRepair(owner)
                    }
                }
            }
        }
    }

    override suspend fun cleanup(owner: CanonicalTimelineCoordinator.Owner, request: TimelineTurnCleanup): Int =
        coordinator.withRepairLease(owner) {
            owner.session.engine.suppressAbandonedTail(
                owner.selection, request.runId, request.turnId, request.reason, request.candidateRunIds,
            )
        }

    override suspend fun repairCursor(
        owner: CanonicalTimelineCoordinator.Owner,
        fallbackSeq: Long?,
        expectedWatermark: Long?,
    ) = coordinator.withRepairLease(owner) {
        val result = coordinator.reconcileRecentDetailed(owner)
        check(result.outcome == TimelineEnginePageOutcome.Applied) { "Cursor repair did not commit" }
        // A transport fallback is not proof that its frames reached durable storage.
        val watermark = checkNotNull(result.committedSequence) { "Cursor repair has no committed sequence" }
        // Expected must be captured before/with this lease; never sample a mutable store after commit.
        repairCommittedCursor(owner, expectedWatermark, watermark)
    }
}
