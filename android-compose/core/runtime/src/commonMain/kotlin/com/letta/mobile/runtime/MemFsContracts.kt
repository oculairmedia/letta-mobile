package com.letta.mobile.runtime

import kotlin.jvm.JvmInline
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable

@JvmInline
@Serializable
value class MemFsPath(val value: String) {
    init {
        require(value.startsWith("/")) { "MemFsPath must be absolute." }
        require("//" !in value) { "MemFsPath cannot contain empty segments." }
    }

    override fun toString(): String = value
}

@JvmInline
@Serializable
value class MemFsRevision(val value: Long) : Comparable<MemFsRevision> {
    init {
        require(value >= 0L) { "MemFsRevision cannot be negative." }
    }

    override fun compareTo(other: MemFsRevision): Int = value.compareTo(other.value)
    override fun toString(): String = value.toString()
}

@JvmInline
@Serializable
value class MemFsCommitId(val value: String) {
    init {
        require(value.isNotBlank()) { "MemFsCommitId cannot be blank." }
    }

    override fun toString(): String = value
}

@Serializable
data class MemFsMetadata(
    val mimeType: String? = null,
    val description: String? = null,
    val tags: Set<String> = emptySet(),
)

@Serializable
data class MemFsFile(
    val path: MemFsPath,
    val revision: MemFsRevision,
    val content: String,
    val metadata: MemFsMetadata = MemFsMetadata(),
)

@Serializable
data class MemFsWriteCommand(
    val path: MemFsPath,
    val content: String,
    val metadata: MemFsMetadata = MemFsMetadata(),
    val expectedRevision: MemFsRevision? = null,
)

@Serializable
data class MemFsDeleteCommand(
    val path: MemFsPath,
    val expectedRevision: MemFsRevision? = null,
)

@Serializable
data class MemFsCommit(
    val id: MemFsCommitId,
    val revision: MemFsRevision,
    val path: MemFsPath,
    val operation: MemFsOperation,
    val createdAt: EpochMillis,
)

@Serializable
enum class MemFsOperation {
    Write,
    Delete,
}

interface MemFsStore {
    suspend fun read(path: MemFsPath): MemFsFile?

    suspend fun write(command: MemFsWriteCommand): MemFsCommit

    suspend fun delete(command: MemFsDeleteCommand): MemFsCommit

    fun commits(afterRevision: MemFsRevision): Flow<MemFsCommit>
}
