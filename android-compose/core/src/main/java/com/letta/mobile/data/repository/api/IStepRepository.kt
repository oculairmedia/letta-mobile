package com.letta.mobile.data.repository.api

import com.letta.mobile.data.model.LettaMessage
import com.letta.mobile.data.model.ProviderTrace
import com.letta.mobile.data.model.Step
import com.letta.mobile.data.model.StepFeedbackUpdateParams
import com.letta.mobile.data.model.StepListParams
import com.letta.mobile.data.model.StepMetrics
import kotlinx.coroutines.flow.StateFlow

interface IStepRepository {
    val steps: StateFlow<List<Step>>
    suspend fun refreshSteps(params: StepListParams = StepListParams())
    suspend fun listSteps(params: StepListParams = StepListParams()): List<Step>
    suspend fun getStep(stepId: String): Step
    suspend fun getStepMetrics(stepId: String): StepMetrics
    suspend fun getStepTrace(stepId: String): ProviderTrace?
    suspend fun getStepMessages(stepId: String): List<LettaMessage>
    suspend fun updateStepFeedback(stepId: String, params: StepFeedbackUpdateParams): Step
    fun upsertStep(step: Step)
}
