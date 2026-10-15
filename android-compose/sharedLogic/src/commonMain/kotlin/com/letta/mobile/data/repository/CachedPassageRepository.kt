package com.letta.mobile.data.repository

import com.letta.mobile.data.backend.RemoteRoute
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.Passage
import com.letta.mobile.data.model.PassageCreateParams
import com.letta.mobile.data.repository.api.IPassageRepository
import com.letta.mobile.data.repository.api.PassageIrohSource
import com.letta.mobile.data.repository.api.PassageRemoteSource
import com.letta.mobile.data.session.BackendScopedCache
import com.letta.mobile.util.runCatchingCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** Phase 5k: platform-neutral cached passage / archival-memory repository. */
open class CachedPassageRepository(
    private val remote: RemoteRoute<PassageRemoteSource>,
    private val irohPassageSource: PassageIrohSource? = null,
) : IPassageRepository, BackendScopedCache {
    private val _passages = MutableStateFlow<Map<AgentId, List<Passage>>>(emptyMap())
    private val passageFlowsByAgent = mutableMapOf<AgentId, MutableStateFlow<List<Passage>>>()

    override fun getPassages(agentId: AgentId): StateFlow<List<Passage>> {
        return passageFlowsByAgent
            .getOrPut(agentId) { MutableStateFlow(_passages.value[agentId].orEmpty()) }
            .asStateFlow()
    }

    override suspend fun refreshPassages(agentId: AgentId) {
        val irohSource = irohPassageSource
        val passages = if (irohSource != null && irohSource.shouldUseIroh()) {
            irohSource.listPassages(agentId.value)
        } else {
            remote.source().listPassages(agentId.value, limit = 100)
        }
        replaceCachedPassages(agentId, passages)
    }

    override suspend fun clearForBackendSwitch() {
        _passages.value = emptyMap()
        passageFlowsByAgent.values.forEach { it.value = emptyList() }
        passageFlowsByAgent.clear()
    }

    override suspend fun createPassage(agentId: AgentId, text: String): Passage {
        val irohSource = irohPassageSource
        if (irohSource != null && irohSource.shouldUseIroh()) {
            val passage = irohSource.createPassage(agentId.value, text)
            appendCachedPassage(agentId, passage)
            return passage
        }
        val passage = remote.source().createPassage(agentId.value, PassageCreateParams(text = text))
        appendCachedPassage(agentId, passage)
        runCatchingCancellable { refreshPassages(agentId) }
        return passage
    }

    override suspend fun deletePassage(agentId: AgentId, passageId: String) {
        val irohSource = irohPassageSource
        if (irohSource != null && irohSource.shouldUseIroh()) {
            irohSource.deletePassage(agentId.value, passageId)
        } else {
            remote.source().deletePassage(agentId.value, passageId)
        }
        replaceCachedPassages(
            agentId = agentId,
            passages = _passages.value[agentId].orEmpty().filter { it.id != passageId },
        )
    }

    override suspend fun searchArchival(agentId: AgentId, query: String): List<Passage> {
        return remote.source().searchArchival(agentId.value, query, limit = 50)
    }

    private fun appendCachedPassage(agentId: AgentId, passage: Passage) {
        replaceCachedPassages(agentId, _passages.value[agentId].orEmpty() + passage)
    }

    private fun replaceCachedPassages(agentId: AgentId, passages: List<Passage>) {
        _passages.update { current -> current + (agentId to passages) }
        passageFlowsByAgent[agentId]?.value = passages
    }
}
