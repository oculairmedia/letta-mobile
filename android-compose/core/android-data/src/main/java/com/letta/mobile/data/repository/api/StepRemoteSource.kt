package com.letta.mobile.data.repository.api

import com.letta.mobile.data.backend.NoAppServerRouteException
import com.letta.mobile.data.model.LettaMessage
import com.letta.mobile.data.model.ProviderTrace
import com.letta.mobile.data.model.Step
import com.letta.mobile.data.model.StepFeedbackUpdateParams
import com.letta.mobile.data.model.StepListParams
import com.letta.mobile.data.model.StepMetrics

/**
 * Step queries a non-App-Server backend would serve. letta-mobile-ckmub.4: this is the seam for
 * one; there is no production implementation since the Letta REST backend was removed.
 * Every call fails unless overridden.
 */
interface StepRemoteSource {
    suspend fun listSteps(params: StepListParams = StepListParams()): List<Step> = noRoute("listSteps")

    suspend fun retrieveStep(stepId: String): Step = noRoute("retrieveStep")

    suspend fun retrieveStepMetrics(stepId: String): StepMetrics = noRoute("retrieveStepMetrics")

    suspend fun retrieveStepTrace(stepId: String): ProviderTrace? = noRoute("retrieveStepTrace")

    suspend fun listStepMessages(
        stepId: String,
        before: String? = null,
        after: String? = null,
        limit: Int? = null,
        order: String? = null,
    ): List<LettaMessage> = noRoute("listStepMessages")

    suspend fun updateStepFeedback(stepId: String, params: StepFeedbackUpdateParams): Step = noRoute("updateStepFeedback")
}

private fun noRoute(operation: String): Nothing =
    throw NoAppServerRouteException("StepRemoteSource.$operation")
