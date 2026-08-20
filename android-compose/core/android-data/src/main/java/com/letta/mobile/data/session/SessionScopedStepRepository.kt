package com.letta.mobile.data.session

import com.letta.mobile.data.model.LettaMessage
import com.letta.mobile.data.model.ProviderTrace
import com.letta.mobile.data.model.Step
import com.letta.mobile.data.model.StepFeedbackUpdateParams
import com.letta.mobile.data.model.StepListParams
import com.letta.mobile.data.model.StepMetrics
import com.letta.mobile.data.repository.api.IStepRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.cancel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

internal fun defaultSessionScopedStepRepositoryScope(): CoroutineScope =
    CoroutineScope(SupervisorJob() + Dispatchers.IO)

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@Singleton
class SessionScopedStepRepository internal constructor(
    private val sessionManager: SessionManager,
    private val proxyScope: CoroutineScope,
) : IStepRepository {
    @Inject
    constructor(
        sessionManager: SessionManager,
    ) : this(
        sessionManager = sessionManager,
        proxyScope = defaultSessionScopedStepRepositoryScope(),
    )

    private val _steps = MutableStateFlow(sessionManager.current.stepRepository.steps.value)
    override val steps: StateFlow<List<Step>> = _steps

    init {
        sessionManager.currentGraph
            .flatMapLatest { it.stepRepository.steps }
            .onEach { _steps.value = it }
            .launchIn(proxyScope)
    }

    private val current: IStepRepository
        get() = sessionManager.current.stepRepository

    override suspend fun refreshSteps(params: StepListParams) = sessionManager.withCurrentSession { it.stepRepository.refreshSteps(params) }

    override suspend fun listSteps(params: StepListParams): List<Step> = sessionManager.withCurrentSession { it.stepRepository.listSteps(params) }

    override suspend fun getStep(stepId: String): Step = sessionManager.withCurrentSession { it.stepRepository.getStep(stepId) }

    override suspend fun getStepMetrics(stepId: String): StepMetrics = sessionManager.withCurrentSession { it.stepRepository.getStepMetrics(stepId) }

    override suspend fun getStepTrace(stepId: String): ProviderTrace? = sessionManager.withCurrentSession { it.stepRepository.getStepTrace(stepId) }

    override suspend fun getStepMessages(stepId: String): List<LettaMessage> = sessionManager.withCurrentSession { it.stepRepository.getStepMessages(stepId) }

    override suspend fun updateStepFeedback(stepId: String, params: StepFeedbackUpdateParams): Step =
        sessionManager.withCurrentSession { it.stepRepository.updateStepFeedback(stepId, params) }

    override fun upsertStep(step: Step) = current.upsertStep(step)

    fun close() { proxyScope.cancel() }
}
