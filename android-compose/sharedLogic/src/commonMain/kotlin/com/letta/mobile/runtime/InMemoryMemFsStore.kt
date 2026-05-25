package com.letta.mobile.runtime

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class InMemoryMemFsStore(
    private val commitIdFactory: (MemFsPath, MemFsRevision, MemFsOperation) -> MemFsCommitId,
    private val clock: () -> EpochMillis,
) : MemFsStore {
    private val mutex = Mutex()
    private val files = mutableMapOf<MemFsPath, MemFsFile>()
    private val commitLog = MutableStateFlow<List<MemFsCommit>>(emptyList())

    override suspend fun read(path: MemFsPath): MemFsFile? = mutex.withLock {
        files[path]
    }

    override suspend fun write(command: MemFsWriteCommand): MemFsCommit = mutex.withLock {
        val current = files[command.path]
        requireExpectedRevision(command.path, command.expectedRevision, current?.revision)

        val revision = nextRevision()
        val file = MemFsFile(
            path = command.path,
            revision = revision,
            content = command.content,
            metadata = command.metadata,
        )
        files[command.path] = file
        appendCommit(
            path = command.path,
            revision = revision,
            operation = MemFsOperation.Write,
        )
    }

    override suspend fun delete(command: MemFsDeleteCommand): MemFsCommit = mutex.withLock {
        val current = files[command.path]
        requireExpectedRevision(command.path, command.expectedRevision, current?.revision)

        val revision = nextRevision()
        files.remove(command.path)
        appendCommit(
            path = command.path,
            revision = revision,
            operation = MemFsOperation.Delete,
        )
    }

    override fun commits(afterRevision: MemFsRevision): Flow<MemFsCommit> = flow {
        var cursor = afterRevision
        commitLog.collect { snapshot ->
            snapshot
                .asSequence()
                .filter { it.revision > cursor }
                .sortedBy { it.revision.value }
                .forEach { commit ->
                    emit(commit)
                    cursor = commit.revision
                }
        }
    }

    private fun nextRevision(): MemFsRevision =
        MemFsRevision((commitLog.value.lastOrNull()?.revision?.value ?: 0L) + 1L)

    private fun appendCommit(
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
        commitLog.value = commitLog.value + commit
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
