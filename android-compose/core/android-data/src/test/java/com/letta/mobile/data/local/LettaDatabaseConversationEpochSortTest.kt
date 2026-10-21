package com.letta.mobile.data.local

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.Conversation
import com.letta.mobile.data.model.ConversationId
import com.letta.mobile.data.model.ConversationRecency
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.jupiter.api.Tag
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * letta-mobile-by4vm.19: cached conversation lists sort on epoch millis parsed once at write time,
 * not on the ISO strings. As text, `+02:00` sorts
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
        assertEquals(1_767_261_600_000L, ConversationRecency.epochMillisOrNull("2026-01-01T10:00:00Z"))
        assertEquals(1_767_261_600_000L, ConversationRecency.epochMillisOrNull("2026-01-01T10:00:00+00:00"))
        assertEquals(1_767_254_400_000L, ConversationRecency.epochMillisOrNull("2026-01-01T10:00:00+02:00"))
        assertEquals(1_767_261_600_500L, ConversationRecency.epochMillisOrNull("2026-01-01T10:00:00.5Z"))
        assertEquals(1_767_259_800_123L, ConversationRecency.epochMillisOrNull("2026-01-01T09:30:00.123456+00:00"))
        assertEquals(1_767_261_600_000L, ConversationRecency.epochMillisOrNull("2026-01-01T10:00:00"))
        assertEquals(1_767_261_600_000L, ConversationRecency.epochMillisOrNull("2026-01-01 10:00:00+00:00"))
        assertNull(ConversationRecency.epochMillisOrNull("not a timestamp"))
        assertNull(ConversationRecency.epochMillisOrNull("  "))
        assertNull(ConversationRecency.epochMillisOrNull(null))
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
        // letta-mobile-by4vm.72: the SQL order is the shared sharedLogic comparator's order.
        assertEquals(
            mixedFormatConversations().sortedWith(ConversationRecency.newestFirst).map { it.id.value },
            dao.getForAgentOnce(AGENT).map { it.id },
        )
        assertEquals(
            (mixedFormatConversations() + otherAgent).sortedWith(ConversationRecency.newestFirst).map { it.id.value },
            dao.getAllOnce().map { it.id },
        )
        assertEquals(
            listOf("b-fraction", "c-z", "other-agent", "d-updated-only", "a-plus-two", "zz-garbage", "zzz-none"),
            dao.getAllOnce().map { it.id },
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
