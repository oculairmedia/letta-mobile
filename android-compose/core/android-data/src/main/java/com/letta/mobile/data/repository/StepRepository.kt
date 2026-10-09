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
    /** True while the active backend is iroh://, which has no step query routes beyond step.list-by-run. */
    private val isIrohBackend: () -> Boolean = { false },
) : IStepRepository {
    private val _steps = MutableStateFlow<List<Step>>(emptyList())
    override val steps: StateFlow<List<Step>> = _steps.asStateFlow()

    override val supportsStepQueries: Boolean
        get() = !isIrohBackend()

    override suspend fun refreshSteps(params: StepListParams) {
        _steps.value = listSteps(params)
    }

    /** Empty under iroh://: callers (usage analytics) degrade to run-based data. */
    override suspend fun listSteps(params: StepListParams): List<Step> {
        if (!supportsStepQueries) return emptyList()
        return stepApi.listSteps(params)
    }

    override suspend fun getStep(stepId: String): Step {
        requireStepQueries("step.get($stepId)")
        return stepApi.retrieveStep(stepId)
    }

    override suspend fun getStepMetrics(stepId: String): StepMetrics {
        requireStepQueries("step.metrics($stepId)")
        return stepApi.retrieveStepMetrics(stepId)
    }

    override suspend fun getStepTrace(stepId: String): ProviderTrace? {
        requireStepQueries("step.trace($stepId)")
        return stepApi.retrieveStepTrace(stepId)
    }

    override suspend fun getStepMessages(stepId: String): List<LettaMessage> {
        requireStepQueries("step.messages($stepId)")
        return stepApi.listStepMessages(stepId = stepId, order = "asc")
    }

    override suspend fun updateStepFeedback(stepId: String, params: StepFeedbackUpdateParams): Step {
        requireStepQueries("step.feedback($stepId)")
        val step = stepApi.updateStepFeedback(stepId, params)
        upsertStep(step)
        return step
    }

    private fun requireStepQueries(operation: String) {
        if (!supportsStepQueries) {
            throw UnsupportedOperationException("Iroh admin_rpc does not support $operation yet")
        }
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
