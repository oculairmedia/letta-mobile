package com.letta.mobile.data.repository.api

import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.Passage
import kotlinx.coroutines.flow.StateFlow

interface IPassageRepository {
    fun getPassages(agentId: AgentId): StateFlow<List<Passage>>
    suspend fun refreshPassages(agentId: AgentId)
    suspend fun createPassage(agentId: AgentId, text: String): Passage
    suspend fun deletePassage(agentId: AgentId, passageId: String)
    suspend fun searchArchival(agentId: AgentId, query: String): List<Passage>
}
