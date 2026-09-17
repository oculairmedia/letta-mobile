package com.letta.mobile.data.local

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.letta.mobile.data.canvas.CanvasAcl
import com.letta.mobile.data.canvas.CanvasDocument
import com.letta.mobile.data.canvas.CanvasId
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class RoomCanvasDocumentStoreTest {

    private lateinit var database: LettaDatabase
    private lateinit var store: RoomCanvasDocumentStore

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, LettaDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        store = RoomCanvasDocumentStore(database.canvasDocumentDao())
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun upsertAndRetrieveCanvasDocument() = runBlocking {
        val doc = CanvasDocument(
            id = CanvasId("canvas-room-1"),
            agentId = "agent-room-42",
            conversationId = "conv-room-99",
            title = "Room Canvas",
            revision = 3L,
            sceneJson = """{"bgColor":-1,"elements":[]}""",
            updatedAtEpochMs = 1716000000000L,
        )

        store.upsert(doc)

        val retrieved = store.get(CanvasId("canvas-room-1"))
        assertNotNull(retrieved)
        assertEquals(doc, retrieved)

        val byConv = store.getForConversation("conv-room-99")
        assertEquals(doc, byConv)

        val byAgent = store.listForAgent("agent-room-42")
        assertEquals(listOf(doc), byAgent)

        assertNull(store.get(CanvasId("non-existent")))
        assertNull(store.getForConversation("non-existent-conv"))
    }

    @Test
    fun upsertOverwritesExistingDocument() = runBlocking {
        val docV1 = CanvasDocument(
            id = CanvasId("canvas-room-2"),
            agentId = "agent-1",
            conversationId = "conv-1",
            title = "V1",
            revision = 1L,
            sceneJson = "{}",
            updatedAtEpochMs = 1000L,
        )
        val docV2 = docV1.copy(
            title = "V2",
            revision = 2L,
            sceneJson = """{"elements":[{"id":"item1"}]}""",
            updatedAtEpochMs = 2000L,
        )

        store.upsert(docV1)
        assertEquals(1L, store.get(CanvasId("canvas-room-2"))?.revision)

        store.upsert(docV2)
        val afterUpdate = store.get(CanvasId("canvas-room-2"))
        assertEquals(docV2, afterUpdate)
        assertEquals("V2", afterUpdate?.title)
        assertEquals(2L, afterUpdate?.revision)
    }

    @Test
    fun upsertIfRevisionWritesOnlyWhileTheStoredRevisionMatches() = runBlocking {
        val id = CanvasId("canvas-room-cas")
        val v1 = CanvasDocument(id = id, title = "V1", revision = 1L, sceneJson = "{}", updatedAtEpochMs = 1000L)
        assertFalse("nothing to compare against before the row exists", store.upsertIfRevision(v1, expectedRevision = 0L))
        store.upsert(v1)

        val v2 = v1.copy(revision = 2L, sceneJson = """{"elements":[{"id":"a"}]}""", updatedAtEpochMs = 2000L)
        assertTrue(store.upsertIfRevision(v2, expectedRevision = 1L))
        assertEquals(v2, store.get(id))

        val stale = v1.copy(revision = 2L, sceneJson = """{"stale":true}""", updatedAtEpochMs = 3000L)
        assertFalse(store.upsertIfRevision(stale, expectedRevision = 1L))
        assertEquals(v2, store.get(id))
    }

    @Test
    fun createForConversationIfAbsentReturnsTheExistingCanvasForTheConversation() = runBlocking {
        val first = CanvasDocument(id = CanvasId("c-1"), conversationId = "conv-x", title = "First", revision = 1L, sceneJson = "{}", updatedAtEpochMs = 1L)
        val second = CanvasDocument(id = CanvasId("c-2"), conversationId = "conv-x", title = "Second", revision = 1L, sceneJson = "{}", updatedAtEpochMs = 2L)

        assertEquals(first, store.createForConversationIfAbsent(first))
        assertEquals(first, store.createForConversationIfAbsent(second))
        assertNull(store.get(CanvasId("c-2")))
        assertEquals(first, store.getForConversation("conv-x"))
    }

    @Test
    fun aRowWithAMalformedAclFailsToLoadInsteadOfLoadingUnrestricted() = runBlocking {
        val id = CanvasId("canvas-room-bad-acl")
        val acl = CanvasAcl(ownerUserId = "alice", writerAgentIds = setOf("agent-1"))
        store.upsert(CanvasDocument(id = id, title = "Guarded", revision = 1L, sceneJson = "{}", updatedAtEpochMs = 1L, acl = acl))
        assertEquals(acl, store.get(id)?.acl)

        // Corrupt the persisted ACL in place, as a partial write or a schema drift would.
        database.canvasDocumentDao().updateIfRevision(
            id = id.value, expectedRevision = 1L, agentId = null, conversationId = null, title = "Guarded",
            revision = 1L, sceneJson = "{}", updatedAtEpochMs = 1L, aclJson = "{not json",
        )

        val error = assertThrows(IllegalStateException::class.java) { runBlocking { store.get(id) } }
        assertTrue(error.message!!.contains("malformed ACL"))

        // A row with no ACL at all still loads as unrestricted, as before.
        store.upsert(CanvasDocument(id = CanvasId("canvas-room-no-acl"), title = "Open", revision = 1L, sceneJson = "{}", updatedAtEpochMs = 1L))
        assertNull(store.get(CanvasId("canvas-room-no-acl"))?.acl)
    }
}
