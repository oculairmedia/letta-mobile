package com.letta.mobile.data.repository.api

import com.letta.mobile.data.model.Passage
import kotlinx.coroutines.flow.StateFlow

interface IPassageRepository {
    fun getPassages(agentId: String): StateFlow<List<Passage>>
    suspend fun refreshPassages(agentId: String)
    suspend fun createPassage(agentId: String, text: String): Passage
    suspend fun deletePassage(agentId: String, passageId: String)
    suspend fun searchArchival(agentId: String, query: String): List<Passage>
}
