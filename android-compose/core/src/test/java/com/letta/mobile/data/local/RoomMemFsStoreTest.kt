package com.letta.mobile.data.local

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.cash.turbine.test
import com.letta.mobile.runtime.EpochMillis
import com.letta.mobile.runtime.MemFsCommitId
import com.letta.mobile.runtime.MemFsDeleteCommand
import com.letta.mobile.runtime.MemFsMetadata
import com.letta.mobile.runtime.MemFsOperation
import com.letta.mobile.runtime.MemFsPath
import com.letta.mobile.runtime.MemFsRevision
import com.letta.mobile.runtime.MemFsWriteCommand
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.jupiter.api.Tag
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
@OptIn(ExperimentalCoroutinesApi::class)
@Tag("integration")
class RoomMemFsStoreTest {
    private var database: LettaDatabase? = null

    @After
    fun tearDown() {
        database?.close()
        database = null
    }

    @Test
    fun `write read and commit replay are durable`() = runTest {
        val store = store()
        val path = MemFsPath("/memory/core.md")

        val first = store.write(
            MemFsWriteCommand(
                path = path,
                content = "name: Ada",
                metadata = MemFsMetadata(
                    mimeType = "text/markdown",
                    tags = setOf("profile", "seed"),
                ),
            ),
        )
        val second = store.write(
            MemFsWriteCommand(
                path = MemFsPath("/memory/profile.md"),
                content = "likes: Kotlin",
            ),
        )

        assertEquals(MemFsRevision(1), first.revision)
        assertEquals(MemFsRevision(2), second.revision)
        assertEquals("name: Ada", store.read(path)?.content)
        assertEquals(setOf("profile", "seed"), store.read(path)?.metadata?.tags)
        assertEquals(listOf(first, second), store.commits(MemFsRevision(0)).take(2).toList())
    }

    @Test
    fun `delete removes file and appends revision`() = runTest {
        val store = store()
        val path = MemFsPath("/memory/core.md")
        val first = store.write(MemFsWriteCommand(path = path, content = "v1"))

        val delete = store.delete(MemFsDeleteCommand(path = path, expectedRevision = first.revision))

        assertEquals(MemFsRevision(2), delete.revision)
        assertEquals(MemFsOperation.Delete, delete.operation)
        assertNull(store.read(path))
    }

    @Test
    fun `commits flow observes live writes without duplicate replay`() = runTest {
        val store = store()

        store.commits(MemFsRevision(0)).test {
            val first = store.write(MemFsWriteCommand(path = MemFsPath("/memory/core.md"), content = "v1"))
            val second = store.delete(
                MemFsDeleteCommand(
                    path = MemFsPath("/memory/core.md"),
                    expectedRevision = first.revision,
                ),
            )

            assertEquals(first, awaitItem())
            assertEquals(second, awaitItem())
            expectNoEvents()
        }
    }

    private fun store(): RoomMemFsStore {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, LettaDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        database = db
        return RoomMemFsStore(
            database = db,
            commitIdFactory = { path, revision, operation ->
                MemFsCommitId("${operation.name.lowercase()}-${path.value}-${revision.value}")
            },
            clock = { EpochMillis(1_000) },
        )
    }
}
