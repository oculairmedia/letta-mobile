package com.letta.mobile.data.repository.api

import com.letta.mobile.data.model.Agent
import com.letta.mobile.data.model.Archive
import com.letta.mobile.data.model.ArchiveCreateParams
import com.letta.mobile.data.model.ArchiveUpdateParams
import kotlinx.coroutines.flow.StateFlow

interface IArchiveRepository {
    val archives: StateFlow<List<Archive>>
    suspend fun refreshArchives(name: String? = null, agentId: String? = null)
    suspend fun getArchive(archiveId: String): Archive
    suspend fun createArchive(params: ArchiveCreateParams): Archive
    suspend fun updateArchive(archiveId: String, params: ArchiveUpdateParams): Archive
    suspend fun deleteArchive(archiveId: String): Archive
    suspend fun listAgentsForArchive(archiveId: String): List<Agent>
    suspend fun deletePassageFromArchive(archiveId: String, passageId: String)
}
