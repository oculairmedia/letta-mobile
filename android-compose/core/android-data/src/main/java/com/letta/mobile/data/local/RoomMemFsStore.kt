package com.letta.mobile.data.local

import androidx.room.withTransaction
import com.letta.mobile.runtime.EpochMillis
import com.letta.mobile.runtime.MemFsCommit
import com.letta.mobile.runtime.MemFsCommitId
import com.letta.mobile.runtime.MemFsDeleteCommand
import com.letta.mobile.runtime.MemFsFile
import com.letta.mobile.runtime.MemFsOperation
import com.letta.mobile.runtime.MemFsPath
import com.letta.mobile.runtime.MemFsRevision
import com.letta.mobile.runtime.MemFsStore
import com.letta.mobile.runtime.MemFsWriteCommand
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.Json

class RoomMemFsStore(
    private val database: LettaDatabase,
    private val commitIdFactory: (MemFsPath, MemFsRevision, MemFsOperation) -> MemFsCommitId = { _, revision, _ ->
        MemFsCommitId("memfs-commit-${revision.value}")
    },
    private val clock: () -> EpochMillis = { EpochMillis(System.currentTimeMillis()) },
) : MemFsStore {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    override suspend fun read(path: MemFsPath): MemFsFile? =
        database.memFsDao().file(path.value)?.toFile(json)

    override suspend fun write(command: MemFsWriteCommand): MemFsCommit =
        database.withTransaction {
            val current = database.memFsDao().file(command.path.value)?.toFile(json)
            requireExpectedRevision(command.path, command.expectedRevision, current?.revision)

            val revision = nextRevision()
            val file = MemFsFile(
                path = command.path,
                revision = revision,
                content = command.content,
                metadata = command.metadata,
            )
            database.memFsDao().upsertFile(MemFsFileEntity.fromFile(file, json))
            appendCommit(
                path = command.path,
                revision = revision,
                operation = MemFsOperation.Write,
            )
        }

    override suspend fun delete(command: MemFsDeleteCommand): MemFsCommit =
        database.withTransaction {
            val current = database.memFsDao().file(command.path.value)?.toFile(json)
            requireExpectedRevision(command.path, command.expectedRevision, current?.revision)

            val revision = nextRevision()
            database.memFsDao().deleteFile(command.path.value)
            appendCommit(
                path = command.path,
                revision = revision,
                operation = MemFsOperation.Delete,
            )
        }

    override fun commits(afterRevision: MemFsRevision): Flow<MemFsCommit> = flow {
        var cursor = afterRevision
        database.memFsDao().observeMaxRevision().collect { maxRevision ->
            if (maxRevision == null || maxRevision <= cursor.value) {
                return@collect
            }

            database.memFsDao()
                .listCommitsAfter(cursor.value)
                .forEach { row ->
                    val commit = row.toCommit()
                    if (commit.revision > cursor) {
                        emit(commit)
                        cursor = commit.revision
                    }
                }
        }
    }

    private suspend fun nextRevision(): MemFsRevision =
        MemFsRevision(database.memFsDao().maxRevision() + 1L)

    private suspend fun appendCommit(
        path: MemFsPath,
        revision: MemFsRevision,
        operation: MemFsOperation,
    ): MemFsCommit {
        val commit = MemFsCommit(
            id = commitIdFactory(path, revision, operation),
            revision = revision,
            path = path,
            operation = operation,
            createdAt = clock(),
        )
        database.memFsDao().insertCommit(MemFsCommitEntity.fromCommit(commit))
        return commit
    }

    private fun requireExpectedRevision(
        path: MemFsPath,
        expected: MemFsRevision?,
        actual: MemFsRevision?,
    ) {
        if (expected == null) return
        require(expected == actual) {
            "Expected $path at revision ${expected.value}, but was ${actual?.value ?: "missing"}."
        }
    }
}
