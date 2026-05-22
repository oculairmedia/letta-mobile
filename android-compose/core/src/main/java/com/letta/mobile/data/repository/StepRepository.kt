package com.letta.mobile.data.repository

import com.letta.mobile.data.api.StepApi
import com.letta.mobile.data.model.LettaMessage
import com.letta.mobile.data.model.ProviderTrace
import com.letta.mobile.data.model.Step
import com.letta.mobile.data.model.StepFeedbackUpdateParams
import com.letta.mobile.data.model.StepListParams
import com.letta.mobile.data.model.StepMetrics
import com.letta.mobile.data.repository.api.IStepRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

class StepRepository(
    private val stepApi: StepApi,
) : IStepRepository {
    private val _steps = MutableStateFlow<List<Step>>(emptyList())
    override val steps: StateFlow<List<Step>> = _steps.asStateFlow()

    override suspend fun refreshSteps(params: StepListParams) {
        _steps.value = stepApi.listSteps(params)
    }

    override suspend fun listSteps(params: StepListParams): List<Step> {
        return stepApi.listSteps(params)
    }

    override suspend fun getStep(stepId: String): Step {
        return stepApi.retrieveStep(stepId)
    }

    override suspend fun getStepMetrics(stepId: String): StepMetrics {
        return stepApi.retrieveStepMetrics(stepId)
    }

    override suspend fun getStepTrace(stepId: String): ProviderTrace? {
        return stepApi.retrieveStepTrace(stepId)
    }

    override suspend fun getStepMessages(stepId: String): List<LettaMessage> {
        return stepApi.listStepMessages(stepId = stepId, order = "asc")
    }

    override suspend fun updateStepFeedback(stepId: String, params: StepFeedbackUpdateParams): Step {
        val step = stepApi.updateStepFeedback(stepId, params)
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
