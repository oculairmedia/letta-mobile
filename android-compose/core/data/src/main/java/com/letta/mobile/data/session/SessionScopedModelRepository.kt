package com.letta.mobile.data.session

import com.letta.mobile.data.model.EmbeddingModel
import com.letta.mobile.data.model.LlmModel
import com.letta.mobile.data.repository.api.IModelRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

internal fun defaultSessionScopedModelRepositoryScope(): CoroutineScope =
    CoroutineScope(SupervisorJob() + Dispatchers.IO)

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@Singleton
class SessionScopedModelRepository internal constructor(
    private val sessionManager: SessionManager,
    private val proxyScope: CoroutineScope,
) : IModelRepository {
    @Inject
    constructor(
        sessionManager: SessionManager,
    ) : this(
        sessionManager = sessionManager,
        proxyScope = defaultSessionScopedModelRepositoryScope(),
    )

    private val _llmModels = MutableStateFlow(sessionManager.current.modelRepository.llmModels.value)
    override val llmModels: StateFlow<List<LlmModel>> = _llmModels

    private val _embeddingModels = MutableStateFlow(sessionManager.current.modelRepository.embeddingModels.value)
    override val embeddingModels: StateFlow<List<EmbeddingModel>> = _embeddingModels

    init {
        sessionManager.currentGraph
            .flatMapLatest { it.modelRepository.llmModels }
            .onEach { _llmModels.value = it }
            .launchIn(proxyScope)
        sessionManager.currentGraph
            .flatMapLatest { it.modelRepository.embeddingModels }
            .onEach { _embeddingModels.value = it }
            .launchIn(proxyScope)
    }

    private val current: IModelRepository
        get() = sessionManager.current.modelRepository

    override suspend fun refreshLlmModels() = sessionManager.withCurrentSession { it.modelRepository.refreshLlmModels() }

    override suspend fun refreshEmbeddingModels() = sessionManager.withCurrentSession { it.modelRepository.refreshEmbeddingModels() }

    fun close() { proxyScope.cancel() }
}
