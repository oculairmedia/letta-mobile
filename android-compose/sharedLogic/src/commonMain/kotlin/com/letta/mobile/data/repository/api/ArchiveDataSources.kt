package com.letta.mobile.data.repository.api

import com.letta.mobile.data.model.Agent
import com.letta.mobile.data.model.Archive
import com.letta.mobile.data.model.ArchiveCreateParams
import com.letta.mobile.data.model.ArchiveUpdateParams

/**
 * Remote HTTP (or equivalent) archive admin surface used by
 * [com.letta.mobile.data.repository.CachedArchiveRepository].
 * Platform modules supply Ktor/[ArchiveApi] bindings; Iroh list traffic goes
 * through [ArchiveIrohSource].
 */
interface ArchiveRemoteSource {
    suspend fun listArchives(
        before: String? = null,
        after: String? = null,
        limit: Int? = null,
        order: String? = null,
        name: String? = null,
        agentId: String? = null,
    ): List<Archive>

    suspend fun retrieveArchive(archiveId: String): Archive
    suspend fun createArchive(params: ArchiveCreateParams): Archive
    suspend fun updateArchive(archiveId: String, params: ArchiveUpdateParams): Archive
    suspend fun deleteArchive(archiveId: String): Archive
    suspend fun listAgentsForArchive(
        archiveId: String,
        limit: Int? = null,
        before: String? = null,
        after: String? = null,
        order: String? = null,
    ): List<Agent>

    suspend fun deletePassageFromArchive(archiveId: String, passageId: String)
}

/**
 * Iroh admin_rpc archive list surface. Implemented by
 * [com.letta.mobile.data.repository.IrohAdminRpcArchiveSource].
 */
interface ArchiveIrohSource {
    fun shouldUseIroh(): Boolean
    suspend fun listArchives(): List<Archive>
}
