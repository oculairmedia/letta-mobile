package com.letta.mobile.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.letta.mobile.runtime.EpochMillis
import com.letta.mobile.runtime.MemFsCommit
import com.letta.mobile.runtime.MemFsCommitId
import com.letta.mobile.runtime.MemFsFile
import com.letta.mobile.runtime.MemFsMetadata
import com.letta.mobile.runtime.MemFsOperation
import com.letta.mobile.runtime.MemFsPath
import com.letta.mobile.runtime.MemFsRevision
import kotlinx.serialization.json.Json

@Entity(tableName = "memfs_files")
data class MemFsFileEntity(
    @PrimaryKey val path: String,
    val revision: Long,
    val content: String,
    val metadataJson: String,
) {
    fun toFile(json: Json): MemFsFile = MemFsFile(
        path = MemFsPath(path),
        revision = MemFsRevision(revision),
        content = content,
        metadata = json.decodeFromString(MemFsMetadata.serializer(), metadataJson),
    )

    companion object {
        fun fromFile(file: MemFsFile, json: Json): MemFsFileEntity = MemFsFileEntity(
            path = file.path.value,
            revision = file.revision.value,
            content = file.content,
            metadataJson = json.encodeToString(MemFsMetadata.serializer(), file.metadata),
        )
    }
}

@Entity(
    tableName = "memfs_commits",
    indices = [
        Index(value = ["commitId"], unique = true),
        Index(value = ["path"]),
    ],
)
data class MemFsCommitEntity(
    @PrimaryKey val revision: Long,
    val commitId: String,
    val path: String,
    val operation: String,
    val createdAtEpochMs: Long,
) {
    fun toCommit(): MemFsCommit = MemFsCommit(
        id = MemFsCommitId(commitId),
        revision = MemFsRevision(revision),
        path = MemFsPath(path),
        operation = MemFsOperation.valueOf(operation),
        createdAt = EpochMillis(createdAtEpochMs),
    )

    companion object {
        fun fromCommit(commit: MemFsCommit): MemFsCommitEntity = MemFsCommitEntity(
            revision = commit.revision.value,
            commitId = commit.id.value,
            path = commit.path.value,
            operation = commit.operation.name,
            createdAtEpochMs = commit.createdAt.value,
        )
    }
}
