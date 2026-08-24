package com.letta.mobile.data.repository.api

import com.letta.mobile.data.model.LlmModel

/**
 * Models available to local-runtime agents: the downloaded embedded model
 * catalog entries, mapped to the same [LlmModel] shape the remote model
 * picker renders. Local mode has no embedding models.
 */
fun interface LocalRuntimeModelSource {
    suspend fun listLlmModels(): List<LlmModel>
}
