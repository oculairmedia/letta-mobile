package com.letta.mobile.data.repository.api

import com.letta.mobile.data.backend.AppServerRouteUnavailable
import com.letta.mobile.data.backend.SessionRoute
import com.letta.mobile.data.model.LettaMessage
import com.letta.mobile.data.model.ProviderTrace
import com.letta.mobile.data.model.Step
import com.letta.mobile.data.model.StepFeedbackUpdateParams
import com.letta.mobile.data.model.StepListParams
import com.letta.mobile.data.model.StepMetrics
import kotlin.jvm.JvmInline

/**
 * Step queries a non-App-Server backend would serve. letta-mobile-ckmub.4: this is the seam for
 * one; there is no production implementation since the Letta REST backend was removed.
 * Every call fails unless overridden.
 */
interface StepRemoteSource {
    suspend fun listSteps(params: StepListParams = StepListParams()): List<Step> = noRoute()

    suspend fun retrieveStep(stepId: StepId): Step = noRoute()

    suspend fun retrieveStepMetrics(stepId: StepId): StepMetrics = noRoute()

    suspend fun retrieveStepTrace(stepId: StepId): ProviderTrace? = noRoute()

    /** The step's messages, oldest first. */
    suspend fun listStepMessages(stepId: StepId): List<LettaMessage> = noRoute()

    suspend fun updateStepFeedback(stepId: StepId, params: StepFeedbackUpdateParams): Step = noRoute()
}

/** A run step's id. */
@JvmInline
value class StepId(val value: String)

private fun noRoute(): Nothing = throw AppServerRouteUnavailable(SessionRoute.STEPS, "StepRemoteSource")
