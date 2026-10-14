package com.letta.mobile.data.local

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.Conversation
import com.letta.mobile.data.model.ConversationId
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.jupiter.api.Tag
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * letta-mobile-by4vm.19: cached conversation lists sort on epoch millis parsed once at write time
 * (and backfilled by the v21 -> v22 migration), not on the ISO strings. As text, `+02:00` sorts
 * above `Z`, `10:00:00Z` above `10:00:00.5Z`, and a row with no timestamp sorted by its id.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
@Tag("integration")
class LettaDatabaseConversationEpochSortTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val dbName = "letta-conversation-epoch-${System.nanoTime()}.db"
    private var database: LettaDatabase? = null

    @After
    fun tearDown() {
        database?.close()
        context.deleteDatabase(dbName)
    }

    @Test
    fun `parser accepts Z and offset forms any fraction precision naive and space separated values`() {
        assertEquals(1_767_261_600_000L, ConversationTimestamps.epochMillisOrNull("2026-01-01T10:00:00Z"))
        assertEquals(1_767_261_600_000L, ConversationTimestamps.epochMillisOrNull("2026-01-01T10:00:00+00:00"))
        assertEquals(1_767_254_400_000L, ConversationTimestamps.epochMillisOrNull("2026-01-01T10:00:00+02:00"))
        assertEquals(1_767_261_600_500L, ConversationTimestamps.epochMillisOrNull("2026-01-01T10:00:00.5Z"))
        assertEquals(1_767_259_800_123L, ConversationTimestamps.epochMillisOrNull("2026-01-01T09:30:00.123456+00:00"))
        assertEquals(1_767_261_600_000L, ConversationTimestamps.epochMillisOrNull("2026-01-01T10:00:00"))
        assertEquals(1_767_261_600_000L, ConversationTimestamps.epochMillisOrNull("2026-01-01 10:00:00+00:00"))
        assertNull(ConversationTimestamps.epochMillisOrNull("not a timestamp"))
        assertNull(ConversationTimestamps.epochMillisOrNull("  "))
        assertNull(ConversationTimestamps.epochMillisOrNull(null))
    }

    @Test
    fun `fromConversation normalises timestamps to epoch millis and keeps the strings`() {
        val entity = ConversationEntity.fromConversation(
            conversation(id = "c", lastMessageAt = "2026-01-01T10:00:00+02:00", updatedAt = "2026-01-01T10:00:00.5Z"),
        )

        assertEquals("2026-01-01T10:00:00+02:00", entity.lastMessageAt)
        assertEquals(1_767_254_400_000L, entity.lastMessageAtEpochMs)
        assertEquals(1_767_261_600_500L, entity.updatedAtEpochMs)
        assertNull(entity.createdAtEpochMs)
    }

    @Test
    fun `mixed format timestamps sort chronologically and missing ones sort last not by id`() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, LettaDatabase::class.java)
            .allowMainThreadQueries()
            .build()
            .also { database = it }
        val dao = db.conversationDao()

        dao.replaceForAgent(
            agentId = AGENT,
            conversations = mixedFormatConversations().map { ConversationEntity.fromConversation(it) },
            refreshedAtMillis = 1L,
        )
        val otherAgent = conversation(id = "other-agent", lastMessageAt = "2026-01-01T09:45:00Z")
            .copy(agentId = AgentId("agent-2"))
        dao.upsert(ConversationEntity.fromConversation(otherAgent))

        assertEquals(EXPECTED_ORDER, dao.getForAgentOnce(AGENT).map { it.id })
        assertEquals(
            listOf("b-fraction", "c-z", "other-agent", "d-updated-only", "a-plus-two", "zz-garbage", "zzz-none"),
            dao.getAllOnce().map { it.id },
        )
    }

    @Test
    fun `migration v21 to v22 backfills epoch columns of existing rows and adds the agent recency index`() = runBlocking {
        createDatabaseAtVersion21 { db ->
            mixedFormatConversations().forEach { conversation ->
                db.execSQL(
                    "INSERT INTO conversations (id, agentId, createdAt, updatedAt, lastMessageAt, inContextMessageIdsJson, " +
                        "isolatedBlockIdsJson, cachedAtEpochMs) VALUES (?, ?, ?, ?, ?, '[]', '[]', 1)",
                    arrayOf<Any?>(
                        conversation.id.value,
                        conversation.agentId.value,
                        conversation.createdAt,
                        conversation.updatedAt,
                        conversation.lastMessageAt,
                    ),
                )
            }
        }

        val db = Room.databaseBuilder(context, LettaDatabase::class.java, dbName)
            .addMigrations(*LettaDatabaseMigrations.ALL)
            .build()
            .also { database = it }
        val dao = db.conversationDao()

        val migrated = dao.getForAgentOnce(AGENT)
        assertEquals(EXPECTED_ORDER, migrated.map { it.id })
        val byId = migrated.associateBy { it.id }
        assertEquals(1_767_261_600_500L, byId.getValue("b-fraction").lastMessageAtEpochMs)
        assertEquals(1_767_254_400_000L, byId.getValue("a-plus-two").lastMessageAtEpochMs)
        assertEquals(1_767_259_800_123L, byId.getValue("d-updated-only").updatedAtEpochMs)
        assertNull(byId.getValue("d-updated-only").lastMessageAtEpochMs)
        assertNull(byId.getValue("zz-garbage").lastMessageAtEpochMs)
        assertEquals("2026-01-01T10:00:00+02:00", byId.getValue("a-plus-two").lastMessageAt)

        val indexNames = db.openHelper.readableDatabase.query("PRAGMA index_list(`conversations`)").use { cursor ->
            buildList { while (cursor.moveToNext()) add(cursor.getString(cursor.getColumnIndexOrThrow("name"))) }
        }
        assertTrue(
            "missing recency index in $indexNames",
            indexNames.contains("index_conversations_agentId_lastMessageAtEpochMs"),
        )
    }

    /** A database migrated from v1 to v21 by the real migrations, [seed]ed, and left at v21. */
    private fun createDatabaseAtVersion21(seed: (SupportSQLiteDatabase) -> Unit) {
        context.deleteDatabase(dbName)
        context.openOrCreateDatabase(dbName, Context.MODE_PRIVATE, null).use { db ->
            createAgentsTable(db)
            db.version = 1
        }
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(dbName)
                .callback(object : SupportSQLiteOpenHelper.Callback(1) {
                    override fun onCreate(db: SupportSQLiteDatabase) = Unit
                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                })
                .build(),
        )
        try {
            val db = helper.writableDatabase
            LettaDatabaseMigrations.ALL.filter { it.endVersion <= 21 }.forEach { it.migrate(db) }
            seed(db)
            db.version = 21
        } finally {
            helper.close()
        }
    }

    private fun createAgentsTable(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS agents (id TEXT NOT NULL, name TEXT NOT NULL, description TEXT, model TEXT, " +
                "embedding TEXT, agentType TEXT, enableSleeptime INTEGER, createdAt TEXT, updatedAt TEXT, " +
                "tagsJson TEXT, toolCount INTEGER NOT NULL, blockCount INTEGER NOT NULL, PRIMARY KEY(id))",
        )
    }

    private companion object {
        const val AGENT = "agent-1"

        /** Newest first; the last two have no parseable timestamp and tie, so `id` orders them. */
        val EXPECTED_ORDER = listOf("b-fraction", "c-z", "d-updated-only", "a-plus-two", "zz-garbage", "zzz-none")

        fun mixedFormatConversations(): List<Conversation> = listOf(
            // 08:00Z, though as text it sorts above every `Z` value below.
            conversation(id = "a-plus-two", lastMessageAt = "2026-01-01T10:00:00+02:00"),
            // 10:00:00.500Z, the newest; as text `.5Z` sorts below `Z`.
            conversation(id = "b-fraction", lastMessageAt = "2026-01-01T10:00:00.5Z"),
            conversation(id = "c-z", lastMessageAt = "2026-01-01T10:00:00Z"),
            // No lastMessageAt: falls back to updatedAt (09:30).
            conversation(id = "d-updated-only", updatedAt = "2026-01-01T09:30:00.123456+00:00", createdAt = "2025-01-01T00:00:00Z"),
            conversation(id = "zz-garbage", lastMessageAt = "not a timestamp"),
            // No timestamps: the old COALESCE(..., id) sorted this first, since "zzz" > "2026".
            conversation(id = "zzz-none"),
        )

        fun conversation(
            id: String,
            lastMessageAt: String? = null,
            updatedAt: String? = null,
            createdAt: String? = null,
        ) = Conversation(
            id = ConversationId(id),
            agentId = AgentId(AGENT),
            lastMessageAt = lastMessageAt,
            updatedAt = updatedAt,
            createdAt = createdAt,
        )
    }
}
