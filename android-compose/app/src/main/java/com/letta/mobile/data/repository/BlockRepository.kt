package com.letta.mobile.data.repository

import com.letta.mobile.data.api.BlockApi
import com.letta.mobile.data.model.Block
import com.letta.mobile.data.model.BlockUpdateParams
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BlockRepository @Inject constructor(
    private val blockApi: BlockApi,
) {
    suspend fun updateBlock(agentId: String, blockLabel: String, value: String): Block {
        val params = BlockUpdateParams(value = value)
        return blockApi.updateBlock(agentId, blockLabel, params)
    }
}
