package com.letta.mobile.data.repository.api

import com.letta.mobile.data.model.Passage
import com.letta.mobile.data.model.PassageCreateParams

interface PassageRemoteSource {
    suspend fun listPassages(
        agentId: String,
        limit: Int? = null,
        after: String? = null,
        search: String? = null,
    ): List<Passage>

    suspend fun createPassage(agentId: String, params: PassageCreateParams): Passage
    suspend fun deletePassage(agentId: String, passageId: String)
    suspend fun searchArchival(
        agentId: String,
        query: String,
        limit: Int? = null,
    ): List<Passage>
}

interface PassageIrohSource {
    fun shouldUseIroh(): Boolean
    suspend fun listPassages(agentId: String): List<Passage>
    suspend fun createPassage(agentId: String, text: String): Passage
    suspend fun deletePassage(agentId: String, passageId: String)
}
