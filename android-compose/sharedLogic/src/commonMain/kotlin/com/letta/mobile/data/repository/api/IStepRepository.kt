package com.letta.mobile.data.repository.api

import com.letta.mobile.data.model.Step
import kotlinx.coroutines.flow.StateFlow

/**
 * A run's steps as a session's own read of them.
 *
 * No production backend serves this: an Iroh host lists a run's steps through
 * [IRunRepository.getRunSteps], so nothing in the app reads [steps].
 */
interface IStepRepository {
    val steps: StateFlow<List<Step>>

    /**
     * False when the active backend has no step query route (iroh:// only exposes `step.list` by
     * run, via [IRunRepository.getRunSteps]).
     */
    val supportsStepQueries: Boolean
}
