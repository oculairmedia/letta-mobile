package com.letta.mobile.data.local

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.letta.mobile.testutil.TestData
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.jupiter.api.Tag
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
@OptIn(ExperimentalCoroutinesApi::class)
@Tag("integration")
class LettaDatabaseCharacterizationTest {

    private var database: LettaDatabase? = null

    @After
    fun tearDown() {
        database?.close()
        database = null
    }

    /**
     * letta-mobile-by4vm.15: v24 squashed the history. A device on an older schema (here v23 with a
     * dropped legacy chat table) opens on a fresh v24 database instead of failing on a missing path.
     */
    @Test
    fun `an older schema resets to the current one instead of failing to open`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "letta-squash-${System.nanoTime()}.db"
        context.openOrCreateDatabase(name, Context.MODE_PRIVATE, null).use { db ->
            db.execSQL("CREATE TABLE pending_local_messages (otid TEXT NOT NULL PRIMARY KEY)")
            db.execSQL("INSERT INTO pending_local_messages (otid) VALUES ('stale')")
            db.version = 23
        }

        val db = LettaDatabase.builder(context, name).allowMainThreadQueries().build().also { database = it }

        assertEquals(emptyList<ConversationEntity>(), db.conversationDao().getForAgentOnce("agent-1"))
        val tables = db.openHelper.readableDatabase.query("SELECT name FROM sqlite_master WHERE type = 'table'").use { cursor ->
            buildList { while (cursor.moveToNext()) add(cursor.getString(0)) }
        }
        assertTrue("legacy table survived the reset: $tables", "pending_local_messages" !in tables)
        assertEquals(25, db.openHelper.readableDatabase.version)
    }

    @Test
    fun `AgentEntity tags preserve commas quotes unicode and empty lists`() {
        val originalTags = listOf("alpha,beta", "quote \" tag", "emoji 🚀", "spaced tag")
        val entity = AgentEntity.fromAgent(TestData.agent(tags = originalTags))

        assertEquals(originalTags, entity.toAgent().tags)
        assertEquals(emptyList<String>(), AgentEntity.fromAgent(TestData.agent(tags = emptyList())).toAgent().tags)
    }

    @Test
    fun `AgentEntity decodes legacy comma-joined tags as best-effort fallback`() {
        assertEquals(
            listOf("alpha", "beta", "gamma"),
            AgentEntity(
                id = "agent-legacy",
                name = "Legacy Agent",
                tagsJson = "alpha,beta,gamma",
            ).toAgent().tags,
        )
    }

    @Test
    fun `ConversationEntity preserves title archive state and id lists`() {
        val conversation = TestData.conversation(
            id = "conversation-1",
            agentId = "agent-1",
            summary = "Cached title",
        ).copy(
            archived = true,
            archivedAt = "2026-05-10T10:00:00Z",
            inContextMessageIds = listOf("message,with,commas", "message-2"),
            isolatedBlockIds = listOf("block-1", "block-2"),
        )

        assertEquals(conversation, ConversationEntity.fromConversation(conversation).toConversation())
    }

    @Test
    fun `conversations round trip through Room`() = runTest {
        val db = inMemoryDatabase()
        val conversation = TestData.conversation(
            id = "conversation-1",
            agentId = "agent-1",
            summary = "Cached title",
        )

        db.conversationDao().replaceForAgent(
            agentId = "agent-1",
            conversations = listOf(ConversationEntity.fromConversation(conversation, cachedAtEpochMs = 123L)),
            refreshedAtMillis = 456L,
        )

        assertEquals(listOf(conversation), db.conversationDao().getForAgentOnce("agent-1").map { it.toConversation() })
        assertEquals(456L, db.conversationDao().getRefreshState("agent-1")?.lastRefreshAtMillis)
    }

    private fun inMemoryDatabase(): LettaDatabase {
        val context = ApplicationProvider.getApplicationContext<Context>()
        return Room.inMemoryDatabaseBuilder(context, LettaDatabase::class.java)
            .allowMainThreadQueries()
            .build()
            .also { database = it }
    }
}
