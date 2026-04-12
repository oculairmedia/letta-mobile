package com.letta.mobile.data.repository

import com.letta.mobile.data.api.StepApi
import com.letta.mobile.data.model.LettaMessage
import com.letta.mobile.data.model.ProviderTrace
import com.letta.mobile.data.model.Step
import com.letta.mobile.data.model.StepFeedbackUpdateParams
import com.letta.mobile.data.model.StepListParams
import com.letta.mobile.data.model.StepMetrics
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class StepRepository @Inject constructor(
    private val stepApi: StepApi,
) {
    private val _steps = MutableStateFlow<List<Step>>(emptyList())
    val steps: StateFlow<List<Step>> = _steps.asStateFlow()

    suspend fun refreshSteps(params: StepListParams = StepListParams()) {
        _steps.value = stepApi.listSteps(params)
    }

    suspend fun listSteps(params: StepListParams = StepListParams()): List<Step> {
        return stepApi.listSteps(params)
    }

    suspend fun getStep(stepId: String): Step {
        return stepApi.retrieveStep(stepId)
    }

    suspend fun getStepMetrics(stepId: String): StepMetrics {
        return stepApi.retrieveStepMetrics(stepId)
    }

    suspend fun getStepTrace(stepId: String): ProviderTrace? {
        return stepApi.retrieveStepTrace(stepId)
    }

    suspend fun getStepMessages(stepId: String): List<LettaMessage> {
        return stepApi.listStepMessages(stepId = stepId, order = "asc")
    }

    suspend fun updateStepFeedback(stepId: String, params: StepFeedbackUpdateParams): Step {
        val step = stepApi.updateStepFeedback(stepId, params)
        upsertStep(step)
        return step
    }

    fun upsertStep(step: Step) {
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
