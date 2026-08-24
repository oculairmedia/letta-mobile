package com.letta.mobile.testutil

import com.letta.mobile.data.api.ApiException
import com.letta.mobile.data.api.PassageApi
import com.letta.mobile.data.model.Passage
import com.letta.mobile.data.model.PassageCreateParams
import io.mockk.mockk

class FakePassageApi : PassageApi(mockk(relaxed = true)) {
    private val passagesByAgent = mutableMapOf<String, MutableList<Passage>>()
    var shouldFail = false
    val calls = mutableListOf<String>()

    fun setPassages(agentId: String, passages: List<Passage>) {
        passagesByAgent[agentId] = passages.toMutableList()
    }

    override suspend fun listPassages(
        agentId: String,
        limit: Int?,
        after: String?,
        search: String?,
    ): List<Passage> {
        calls.add("listPassages:$agentId")
        if (shouldFail) throw ApiException(500, "Server error")
        return passagesByAgent[agentId]
            .orEmpty()
            .filter { passage -> search == null || passage.text.contains(search, ignoreCase = true) }
            .take(limit ?: Int.MAX_VALUE)
    }

    override suspend fun createPassage(agentId: String, params: PassageCreateParams): Passage {
        calls.add("createPassage:$agentId")
        if (shouldFail) throw ApiException(500, "Server error")
        val passages = passagesByAgent.getOrPut(agentId) { mutableListOf() }
        val passage = Passage(
            id = "p${passages.size + 1}",
            text = params.text,
            agentId = agentId,
        )
        passages += passage
        return passage
    }

    override suspend fun deletePassage(agentId: String, passageId: String) {
        calls.add("deletePassage:$agentId:$passageId")
        if (shouldFail) throw ApiException(500, "Server error")
        passagesByAgent[agentId]?.removeAll { it.id == passageId }
    }

    override suspend fun searchArchival(agentId: String, query: String, limit: Int?): List<Passage> {
        calls.add("searchArchival:$agentId")
        return listPassages(agentId = agentId, limit = limit, after = null, search = query)
    }
}
