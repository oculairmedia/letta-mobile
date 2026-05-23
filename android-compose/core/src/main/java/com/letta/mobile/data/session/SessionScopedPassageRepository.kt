package com.letta.mobile.data.session

import com.letta.mobile.data.model.Passage
import com.letta.mobile.data.repository.api.IPassageRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

internal fun defaultSessionScopedPassageRepositoryScope(): CoroutineScope =
    CoroutineScope(SupervisorJob() + Dispatchers.IO)

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@Singleton
class SessionScopedPassageRepository internal constructor(
    private val sessionManager: SessionManager,
    private val proxyScope: CoroutineScope,
) : IPassageRepository {
    @Inject
    constructor(
        sessionManager: SessionManager,
    ) : this(
        sessionManager = sessionManager,
        proxyScope = defaultSessionScopedPassageRepositoryScope(),
    )

    private val cacheLock = Any()
    private val passageFlowsByAgent = mutableMapOf<String, MutableStateFlow<List<Passage>>>()
    private val passageJobsByAgent = mutableMapOf<String, Job>()

    init {
        sessionManager.currentGraph
            .drop(1)
            .onEach {
                synchronized(cacheLock) {
                    passageJobsByAgent.values.forEach { it.cancel() }
                    passageJobsByAgent.clear()
                    passageFlowsByAgent.values.forEach { it.value = emptyList() }
                    passageFlowsByAgent.clear()
                }
            }
            .launchIn(proxyScope)
    }

    private val current: IPassageRepository
        get() = sessionManager.current.passageRepository

    override fun getPassages(agentId: String): StateFlow<List<Passage>> = synchronized(cacheLock) {
        val flow = passageFlowsByAgent.getOrPut(agentId) {
            MutableStateFlow(sessionManager.current.passageRepository.getPassages(agentId).value)
        }
        passageJobsByAgent.getOrPut(agentId) {
            sessionManager.currentGraph
                .flatMapLatest { it.passageRepository.getPassages(agentId) }
                .onEach { flow.value = it }
                .launchIn(proxyScope)
        }
        flow.asStateFlow()
    }

    override suspend fun refreshPassages(agentId: String) = sessionManager.withCurrentSession { it.passageRepository.refreshPassages(agentId) }

    override suspend fun createPassage(agentId: String, text: String): Passage = sessionManager.withCurrentSession { it.passageRepository.createPassage(agentId, text) }

    override suspend fun deletePassage(agentId: String, passageId: String) =
        sessionManager.withCurrentSession { it.passageRepository.deletePassage(agentId, passageId) }

    override suspend fun searchArchival(agentId: String, query: String): List<Passage> =
        sessionManager.withCurrentSession { it.passageRepository.searchArchival(agentId, query) }

    fun close() { proxyScope.cancel() }
}
