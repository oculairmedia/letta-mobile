package com.letta.mobile.data.local

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.jupiter.api.Tag
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
@OptIn(ExperimentalCoroutinesApi::class)
@Tag("integration")
class RoomConversationCursorStoreTest {
    private var database: LettaDatabase? = null

    @After
    fun tearDown() {
        database?.close()
        database = null
    }

    @Test
    fun `getCursor returns null before frames are ingested`() = runTest {
        val db = inMemoryDatabase()

        assertEquals(null, db.conversationCursorDao().getCursor("conversation-1"))
    }

    @Test
    fun `rapid concurrent upserts keep the highest cursor`() = runTest {
        val db = inMemoryDatabase()
        val store = RoomConversationCursorStore(db.conversationCursorDao())

        coroutineScope {
            (1L..200L)
                .flatMap { seq -> listOf(seq, 200L - seq) }
                .map { seq ->
                    async(Dispatchers.Default) {
                        store.recordFrame("conversation-1", seq)
                    }
                }
                .awaitAll()
        }

        val cursor = db.conversationCursorDao().getCursor("conversation-1")
        assertEquals(200L, cursor?.highestSeenSeq)
    }

    @Test
    fun `lower late upsert does not roll back cursor`() = runTest {
        val db = inMemoryDatabase()
        val store = RoomConversationCursorStore(db.conversationCursorDao())

        store.recordFrame("conversation-1", 10L)
        store.recordFrame("conversation-1", 4L)

        val cursor = db.conversationCursorDao().getCursor("conversation-1")
        assertEquals(10L, cursor?.highestSeenSeq)
    }

    private fun inMemoryDatabase(): LettaDatabase {
        val context = ApplicationProvider.getApplicationContext<Context>()
        return Room.inMemoryDatabaseBuilder(context, LettaDatabase::class.java)
            .allowMainThreadQueries()
            .build()
            .also { database = it }
    }
}
