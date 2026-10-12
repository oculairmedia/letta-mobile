package com.letta.mobile.data.repository

import com.letta.mobile.data.backend.NoAppServerRouteException
import com.letta.mobile.data.backend.noAppServerRoute
import com.letta.mobile.data.model.LettaMessage
import com.letta.mobile.data.model.ProviderTrace
import com.letta.mobile.data.model.Step
import com.letta.mobile.data.model.StepFeedbackUpdateParams
import com.letta.mobile.data.model.StepListParams
import com.letta.mobile.data.model.StepMetrics
import com.letta.mobile.data.repository.api.IStepRepository
import com.letta.mobile.data.repository.api.StepId
import com.letta.mobile.data.repository.api.StepRemoteSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Step queries. letta-mobile-ckmub.4: no App Server or Iroh route serves them (an Iroh host lists a
 * run's steps through the run repository), so production binds no [remote] and reports
 * [supportsStepQueries] false; [remote] is the seam a non-App-Server backend would fill.
 */
class StepRepository(
    private val remote: StepRemoteSource? = null,
) : IStepRepository {
    private val _steps = MutableStateFlow<List<Step>>(emptyList())
    override val steps: StateFlow<List<Step>> = _steps.asStateFlow()

    override val supportsStepQueries: Boolean
        get() = remote != null

    override suspend fun refreshSteps(params: StepListParams) {
        _steps.value = listSteps(params)
    }

    /** Empty under iroh://: callers (usage analytics) degrade to run-based data. */
    override suspend fun listSteps(params: StepListParams): List<Step> {
        if (!supportsStepQueries) return emptyList()
        return requireRemote().listSteps(params)
    }

    override suspend fun getStep(stepId: String): Step {
        requireStepQueries("step.get($stepId)")
        return requireRemote().retrieveStep(StepId(stepId))
    }

    override suspend fun getStepMetrics(stepId: String): StepMetrics {
        requireStepQueries("step.metrics($stepId)")
        return requireRemote().retrieveStepMetrics(StepId(stepId))
    }

    override suspend fun getStepTrace(stepId: String): ProviderTrace? {
        requireStepQueries("step.trace($stepId)")
        return requireRemote().retrieveStepTrace(StepId(stepId))
    }

    override suspend fun getStepMessages(stepId: String): List<LettaMessage> {
        requireStepQueries("step.messages($stepId)")
        return requireRemote().listStepMessages(StepId(stepId))
    }

    override suspend fun updateStepFeedback(stepId: String, params: StepFeedbackUpdateParams): Step {
        requireStepQueries("step.feedback($stepId)")
        val step = requireRemote().updateStepFeedback(StepId(stepId), params)
        upsertStep(step)
        return step
    }

    private fun requireStepQueries(operation: String) {
        if (!supportsStepQueries) throw NoAppServerRouteException(operation)
    }

    private fun requireRemote(): StepRemoteSource = remote ?: noAppServerRoute()

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
