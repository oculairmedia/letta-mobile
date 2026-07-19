package com.letta.mobile.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class ImportedAgentsResponse(
    @SerialName("agent_ids") val agentIds: List<String> = emptyList(),
)

/** Multipart agent-import payload (file + optional overrides). */
data class AgentImportParams(
    val fileName: String,
    val fileBytes: ByteArray,
    val overrideName: String? = null,
    val overrideExistingTools: Boolean? = null,
    val projectId: ProjectId? = null,
    val stripMessages: Boolean? = null,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || this::class != other::class) return false
        return sameImportFields(other as AgentImportParams)
    }

    private fun sameImportFields(other: AgentImportParams): Boolean =
        fileName == other.fileName &&
            fileBytes.contentEquals(other.fileBytes) &&
            overrideName == other.overrideName &&
            overrideExistingTools == other.overrideExistingTools &&
            projectId == other.projectId &&
            stripMessages == other.stripMessages

    override fun hashCode(): Int {
        var result = fileName.hashCode()
        result = 31 * result + fileBytes.contentHashCode()
        result = 31 * result + (overrideName?.hashCode() ?: 0)
        result = 31 * result + (overrideExistingTools?.hashCode() ?: 0)
        result = 31 * result + (projectId?.hashCode() ?: 0)
        result = 31 * result + (stripMessages?.hashCode() ?: 0)
        return result
    }
}
