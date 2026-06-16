package com.letta.mobile.runtime

import kotlin.jvm.JvmInline
import kotlinx.serialization.Serializable

@JvmInline
@Serializable
value class AgentFileId(val value: String) {
    init {
        require(value.isNotBlank()) { "AgentFileId cannot be blank." }
    }

    override fun toString(): String = value
}

@Serializable
data class AgentFile(
    val id: AgentFileId,
    val path: MemFsPath,
    val displayName: String,
    val mimeType: String? = null,
    val sizeBytes: Long? = null,
    val metadata: Map<String, String> = emptyMap(),
)

@Serializable
data class AgentFileImportCommand(
    val sourceUri: String,
    val destinationPath: MemFsPath,
    val displayName: String,
    val mimeType: String? = null,
)

@Serializable
data class AgentFileExportCommand(
    val fileId: AgentFileId,
    val destinationUri: String,
)
