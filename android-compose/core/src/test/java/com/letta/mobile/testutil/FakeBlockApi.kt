package com.letta.mobile.testutil

import com.letta.mobile.data.api.ApiException
import com.letta.mobile.data.api.BlockApi
import com.letta.mobile.data.model.Block
import com.letta.mobile.data.model.BlockUpdateParams

class FakeBlockApi : BlockApi(null!!) {
    var blocks = mutableMapOf<String, MutableList<Block>>()
    var shouldFail = false
    val calls = mutableListOf<String>()

    override suspend fun updateBlock(agentId: String, blockLabel: String, params: BlockUpdateParams): Block {
        calls.add("updateBlock:$agentId:$blockLabel")
        if (shouldFail) throw ApiException(500, "Server error")
        val agentBlocks = blocks.getOrPut(agentId) { mutableListOf() }
        val index = agentBlocks.indexOfFirst { it.label == blockLabel }
        val updated = TestData.block(label = blockLabel, value = params.value ?: "")
        if (index >= 0) {
            agentBlocks[index] = updated
        } else {
            agentBlocks.add(updated)
        }
        return updated
    }
}
