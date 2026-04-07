package com.letta.mobile.data.repository

import com.letta.mobile.data.api.PassageApi
import com.letta.mobile.data.model.Passage
import com.letta.mobile.data.model.PassageCreateParams
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PassageRepository @Inject constructor(
    private val passageApi: PassageApi,
) {
    private val _passages = MutableStateFlow<Map<String, List<Passage>>>(emptyMap())

    fun getPassages(agentId: String): StateFlow<List<Passage>> {
        return MutableStateFlow(_passages.value[agentId] ?: emptyList())
    }

    suspend fun refreshPassages(agentId: String) {
        val passages = passageApi.listPassages(agentId, limit = 100)
        _passages.value = _passages.value.toMutableMap().apply {
            put(agentId, passages)
        }
    }

    suspend fun createPassage(agentId: String, text: String): Passage {
        val passage = passageApi.createPassage(agentId, PassageCreateParams(text = text))
        refreshPassages(agentId)
        return passage
    }

    suspend fun deletePassage(agentId: String, passageId: String) {
        passageApi.deletePassage(agentId, passageId)
        _passages.value = _passages.value.toMutableMap().apply {
            val existing = get(agentId) ?: emptyList()
            put(agentId, existing.filter { it.id != passageId })
        }
    }

    suspend fun searchArchival(agentId: String, query: String): List<Passage> {
        return passageApi.searchArchival(agentId, query, limit = 50)
    }
}
