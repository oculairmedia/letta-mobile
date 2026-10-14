package com.letta.mobile.data.timeline

import com.letta.mobile.data.timeline.snapshot.ConfirmedTimelineStore
import com.letta.mobile.data.timeline.snapshot.NormalizedTimelineCommitPlan
import com.letta.mobile.data.timeline.snapshot.NormalizedTimelineWriteResult
import com.letta.mobile.data.timeline.snapshot.StoredTimelineEnvelope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Publishes the highest revision the real store reports as durably [NormalizedTimelineWriteResult.Committed],
 * so a test can await a BACKGROUND persist (one it did not drive through `flushSnapshotNow()`)
 * instead of reading Room after `advanceUntilIdle()`. Room commits on real `Dispatchers.IO`,
 * outside the test scheduler, so "advance then read" races the DB thread.
 */
internal class CommitObservingStore(
    private val delegate: ConfirmedTimelineStore,
) : ConfirmedTimelineStore by delegate {
    private val committed = MutableStateFlow(0L)
    val committedRevision: StateFlow<Long> = committed.asStateFlow()

    override suspend fun commitNormalized(
        plan: NormalizedTimelineCommitPlan,
        fullEnvelope: StoredTimelineEnvelope,
        checkpointLegacyEnvelope: Boolean,
    ): NormalizedTimelineWriteResult =
        delegate.commitNormalized(plan, fullEnvelope, checkpointLegacyEnvelope).also { result ->
            if (result is NormalizedTimelineWriteResult.Committed) {
                committed.update { maxOf(it, result.revision.value) }
            }
        }
}
