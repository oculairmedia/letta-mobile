package com.letta.mobile.data.repository

import com.letta.mobile.data.model.LettaMessage
import com.letta.mobile.data.model.ProviderTrace
import com.letta.mobile.data.model.Step
import com.letta.mobile.data.model.StepFeedbackUpdateParams
import com.letta.mobile.data.model.StepListParams
import com.letta.mobile.data.model.StepMetrics
import com.letta.mobile.data.repository.api.IStepRepository
import com.letta.mobile.data.repository.api.StepRemoteSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** Phase 5m: platform-neutral cached step repository. */
open class CachedStepRepository(
    private val remote: StepRemoteSource,
) : IStepRepository {
    private val _steps = MutableStateFlow<List<Step>>(emptyList())
    override val steps: StateFlow<List<Step>> = _steps.asStateFlow()

    override suspend fun refreshSteps(params: StepListParams) {
        _steps.value = remote.listSteps(params)
    }

    override suspend fun listSteps(params: StepListParams): List<Step> = remote.listSteps(params)

    override suspend fun getStep(stepId: String): Step = remote.retrieveStep(stepId)

    override suspend fun getStepMetrics(stepId: String): StepMetrics = remote.retrieveStepMetrics(stepId)

    override suspend fun getStepTrace(stepId: String): ProviderTrace? = remote.retrieveStepTrace(stepId)

    override suspend fun getStepMessages(stepId: String): List<LettaMessage> =
        remote.listStepMessages(stepId = stepId, order = "asc")

    override suspend fun updateStepFeedback(stepId: String, params: StepFeedbackUpdateParams): Step {
        val step = remote.updateStepFeedback(stepId, params)
        upsertStep(step)
        return step
    }

    override fun upsertStep(step: Step) {
        _steps.update { current ->
            val index = current.indexOfFirst { it.id == step.id }
            if (index >= 0) {
                current.toMutableList().apply { this[index] = step }
            } else {
                current + step
            }
        }
    }
}
