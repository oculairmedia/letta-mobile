package com.letta.mobile.runtime

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest

class InMemoryMemFsStoreTest {
    @Test
    fun writeReadAndCommitReplayAreRevisionOrdered() = runTest {
        val store = store()

        val first = store.write(
            MemFsWriteCommand(
                path = MemFsPath("/memory/core.md"),
                content = "name: Ada",
            )
        )
        val second = store.write(
            MemFsWriteCommand(
                path = MemFsPath("/memory/profile.md"),
                content = "likes: Kotlin",
            )
        )

        assertEquals(MemFsRevision(1), first.revision)
        assertEquals(MemFsRevision(2), second.revision)
        assertEquals("name: Ada", store.read(MemFsPath("/memory/core.md"))?.content)

        val replay = store.commits(MemFsRevision(0)).take(2).toList()
        assertEquals(listOf(first, second), replay)
    }

    @Test
    fun expectedRevisionProtectsWritesAndDeletes() = runTest {
        val store = store()
        val path = MemFsPath("/memory/core.md")

        val first = store.write(MemFsWriteCommand(path = path, content = "v1"))

        assertFailsWith<IllegalArgumentException> {
            store.write(
                MemFsWriteCommand(
                    path = path,
                    content = "v2",
                    expectedRevision = MemFsRevision(999),
                )
            )
        }

        store.delete(MemFsDeleteCommand(path = path, expectedRevision = first.revision))

        assertNull(store.read(path))
    }

    private fun store(): InMemoryMemFsStore = InMemoryMemFsStore(
        commitIdFactory = { path, revision, operation ->
            MemFsCommitId("${operation.name.lowercase()}-${path.value}-${revision.value}")
        },
        clock = { EpochMillis(1_000) },
    )
}
