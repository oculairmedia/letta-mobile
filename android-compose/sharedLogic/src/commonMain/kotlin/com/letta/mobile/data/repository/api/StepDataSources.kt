package com.letta.mobile.data.repository.api

import com.letta.mobile.data.model.LettaMessage
import com.letta.mobile.data.model.ProviderTrace
import com.letta.mobile.data.model.Step
import com.letta.mobile.data.model.StepFeedbackUpdateParams
import com.letta.mobile.data.model.StepListParams
import com.letta.mobile.data.model.StepMetrics

interface StepRemoteSource {
    suspend fun listSteps(params: StepListParams = StepListParams()): List<Step>
    suspend fun retrieveStep(stepId: String): Step
    suspend fun retrieveStepMetrics(stepId: String): StepMetrics
    suspend fun retrieveStepTrace(stepId: String): ProviderTrace?
    suspend fun listStepMessages(
        stepId: String,
        before: String? = null,
        after: String? = null,
        limit: Int? = null,
        order: String? = null,
    ): List<LettaMessage>

    suspend fun updateStepFeedback(stepId: String, params: StepFeedbackUpdateParams): Step
}
