package com.letta.mobile.data.repository

import com.letta.mobile.data.model.Step
import com.letta.mobile.data.repository.api.IStepRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Step queries. No App Server or Iroh route serves them (an Iroh host lists a run's steps through
 * the run repository), so production binds no remote source and reports [supportsStepQueries] false.
 */
class StepRepository : IStepRepository {
    private val _steps = MutableStateFlow<List<Step>>(emptyList())
    override val steps: StateFlow<List<Step>> = _steps.asStateFlow()

    override val supportsStepQueries: Boolean get() = false
}
