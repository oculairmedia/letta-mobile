package com.letta.mobile.data.local

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.letta.mobile.data.canvas.CanvasDocument
import com.letta.mobile.data.canvas.CanvasId
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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
}
