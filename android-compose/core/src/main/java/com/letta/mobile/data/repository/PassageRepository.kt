package com.letta.mobile.data.repository

import com.letta.mobile.data.api.PassageApi
import com.letta.mobile.data.model.Passage
import com.letta.mobile.data.model.PassageCreateParams
import com.letta.mobile.data.repository.api.IPassageRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PassageRepository @Inject constructor(
    private val passageApi: PassageApi,
) : IPassageRepository {
    private val cacheLock = Any()
    private val _passages = MutableStateFlow<Map<String, List<Passage>>>(emptyMap())
    private val passageFlowsByAgent = mutableMapOf<String, MutableStateFlow<List<Passage>>>()

    override fun getPassages(agentId: String): StateFlow<List<Passage>> {
        return synchronized(cacheLock) {
            passageFlowsByAgent
                .getOrPut(agentId) { MutableStateFlow(_passages.value[agentId].orEmpty()) }
                .asStateFlow()
        }
    }

    override suspend fun refreshPassages(agentId: String) {
        val passages = passageApi.listPassages(agentId, limit = 100)
        replaceCachedPassages(agentId, passages)
    }

    override suspend fun createPassage(agentId: String, text: String): Passage {
        val passage = passageApi.createPassage(agentId, PassageCreateParams(text = text))
        refreshPassages(agentId)
        return passage
    }

    override suspend fun deletePassage(agentId: String, passageId: String) {
        passageApi.deletePassage(agentId, passageId)
        replaceCachedPassages(
            agentId = agentId,
            passages = _passages.value[agentId].orEmpty().filter { it.id != passageId },
        )
    }

    override suspend fun searchArchival(agentId: String, query: String): List<Passage> {
        return passageApi.searchArchival(agentId, query, limit = 50)
    }

    private fun replaceCachedPassages(agentId: String, passages: List<Passage>) {
        synchronized(cacheLock) {
            _passages.value = _passages.value + (agentId to passages)
            passageFlowsByAgent[agentId]?.value = passages
        }
    }
}
