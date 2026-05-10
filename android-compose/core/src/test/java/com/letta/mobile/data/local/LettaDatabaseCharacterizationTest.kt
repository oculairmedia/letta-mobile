package com.letta.mobile.data.local

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.letta.mobile.testutil.TestData
import java.io.File
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

    @Test
    fun `production database builder must not use destructive migration`() {
        val source = lettaDatabaseSource().readText()

        assertTrue(
            "Production Room builder must preserve local data instead of using fallbackToDestructiveMigration.",
            !source.contains("fallbackToDestructiveMigration"),
        )
    }

    @Test
    fun `pending local messages round trip through Room`() = runTest {
        val db = inMemoryDatabase()
        val row = PendingLocalEntity(
            otid = "otid-1",
            conversationId = "conversation-1",
            content = "message with local image",
            attachmentsJson = """[{"base64":"abc123","mediaType":"image/png"}]""",
            sentAtEpochMs = 123_456L,
        )

        db.pendingLocalDao().upsert(row)

        assertEquals(listOf(row), db.pendingLocalDao().listForConversation("conversation-1"))
        assertEquals(emptyList<PendingLocalEntity>(), db.pendingLocalDao().listForConversation("conversation-2"))
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

    private fun lettaDatabaseSource(): File {
        val relativePaths = listOf(
            "src/main/java/com/letta/mobile/data/local/LettaDatabase.kt",
            "android-compose/core/src/main/java/com/letta/mobile/data/local/LettaDatabase.kt",
        )
        val start = File(requireNotNull(System.getProperty("user.dir"))).canonicalFile
        return generateSequence(start) { it.parentFile }
            .flatMap { parent -> relativePaths.asSequence().map { relative -> File(parent, relative) } }
            .firstOrNull { it.exists() }
            ?: error("Could not locate LettaDatabase.kt from ${start.path}")
    }
}
