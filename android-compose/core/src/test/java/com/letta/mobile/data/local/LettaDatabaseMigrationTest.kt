package com.letta.mobile.data.local

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.letta.mobile.runtime.BackendId
import com.letta.mobile.runtime.ConversationId
import com.letta.mobile.runtime.EpochMillis
import com.letta.mobile.runtime.MemFsCommitId
import com.letta.mobile.runtime.MemFsPath
import com.letta.mobile.runtime.MemFsWriteCommand
import com.letta.mobile.runtime.RuntimeEventDraft
import com.letta.mobile.runtime.RuntimeEventId
import com.letta.mobile.runtime.RuntimeEventPayload
import com.letta.mobile.runtime.RuntimeEventSource
import com.letta.mobile.runtime.RuntimeId
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
class LettaDatabaseMigrationTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val dbName = "letta-migration-${System.nanoTime()}.db"
    private var database: LettaDatabase? = null

    @After
    fun tearDown() {
        database?.close()
        context.deleteDatabase(dbName)
    }

    @Test
    fun `migrates reconstructed v1 database to latest and preserves agents`() = runTest {
        createLegacyDatabase(version = 1) { db ->
            createAgentsTable(db)
            db.execSQL(
                """
                INSERT INTO agents (
                    id, name, description, model, embedding, agentType, enableSleeptime,
                    createdAt, updatedAt, tagsJson, toolCount, blockCount
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
                arrayOf<Any?>(
                    "agent-1",
                    "Agent One",
                    "description",
                    "model-a",
                    "embedding-a",
                    "memgpt_agent",
                    1,
                    "2026-01-01T00:00:00Z",
                    "2026-01-02T00:00:00Z",
                    "alpha,beta",
                    2,
                    3,
                ),
            )
        }

        val db = openMigratedDatabase()

        val agents = db.agentDao().getAllOnce()
        assertEquals(1, agents.size)
        assertEquals("agent-1", agents.single().id)
        assertEquals("Agent One", agents.single().name)
        assertEquals(AgentEntity.encodeTags(listOf("alpha", "beta")), agents.single().tagsJson)
        assertEquals(listOf("alpha", "beta"), agents.single().toAgent().tags)
        assertTrue(db.bugReportDao().getRecentForProject("project-1", limit = 10).isEmpty())
        assertTrue(db.pendingLocalDao().listForConversation("conversation-1").isEmpty())
        assertTrue(db.conversationDao().getForAgentOnce("agent-1").isEmpty())
    }

    @Test
    fun `migrates reconstructed v2 database to latest and preserves bug reports`() = runTest {
        createLegacyDatabase(version = 2) { db ->
            createAgentsTable(db)
            createProjectBugReportsTable(db)
            db.execSQL(
                """
                INSERT INTO project_bug_reports (
                    id, projectIdentifier, title, description, severity, tagsJson,
                    attachmentReferencesJson, structuredPrompt, createdAt
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
                arrayOf<Any?>(
                    7L,
                    "project-1",
                    "Bug title",
                    "Bug description",
                    "high",
                    "chat,android",
                    "file://one||file://two",
                    "Structured prompt",
                    "2026-01-03T00:00:00Z",
                ),
            )
        }

        val db = openMigratedDatabase()

        val reports = db.bugReportDao().getRecentForProject("project-1", limit = 10)
        assertEquals(1, reports.size)
        assertEquals(7L, reports.single().id)
        assertEquals("Bug title", reports.single().title)
        assertTrue(db.pendingLocalDao().listForConversation("conversation-1").isEmpty())
        assertTrue(db.conversationDao().getForAgentOnce("agent-1").isEmpty())
    }

    @Test
    fun `opens current v3 database and preserves pending local messages`() = runTest {
        val row = PendingLocalEntity(
            otid = "otid-1",
            conversationId = "conversation-1",
            content = "image message",
            attachmentsJson = """[{"base64":"abc","mediaType":"image/png"}]""",
            sentAtEpochMs = 123L,
        )
        createLegacyDatabase(version = 3) { db ->
            createAgentsTable(db)
            createProjectBugReportsTable(db)
            createPendingLocalMessagesTable(db)
            db.execSQL(
                """
                INSERT INTO agents (
                    id, name, description, model, embedding, agentType, enableSleeptime,
                    createdAt, updatedAt, tagsJson, toolCount, blockCount
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
                arrayOf<Any?>(
                    "agent-legacy-tags",
                    "Legacy Tags",
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    "alpha,beta,gamma",
                    0,
                    0,
                ),
            )
            db.execSQL(
                """
                INSERT INTO pending_local_messages (
                    otid, conversationId, content, attachmentsJson, sentAtEpochMs
                ) VALUES (?, ?, ?, ?, ?)
                """.trimIndent(),
                arrayOf<Any?>(row.otid, row.conversationId, row.content, row.attachmentsJson, row.sentAtEpochMs),
            )
        }

        val db = openMigratedDatabase()

        assertEquals(listOf(row), db.pendingLocalDao().listForConversation("conversation-1"))
        val agent = db.agentDao().getAllOnce().single()
        assertEquals(AgentEntity.encodeTags(listOf("alpha", "beta", "gamma")), agent.tagsJson)
        assertEquals(listOf("alpha", "beta", "gamma"), agent.toAgent().tags)
        assertTrue(db.conversationDao().getForAgentOnce("agent-1").isEmpty())
    }

    @Test
    fun `opens current v4 database and adds conversation cache tables`() = runTest {
        createLegacyDatabase(version = 4) { db ->
            createAgentsTable(db)
            createProjectBugReportsTable(db)
            createPendingLocalMessagesTable(db)
        }

        val db = openMigratedDatabase()

        assertTrue(db.conversationDao().getForAgentOnce("agent-1").isEmpty())
        assertEquals(null, db.conversationDao().getRefreshState("agent-1"))
        db.conversationDao().replaceForAgent(
            agentId = "agent-1",
            conversations = listOf(
                ConversationEntity.fromConversation(
                    com.letta.mobile.data.model.Conversation(
                        id = "conversation-1",
                        agentId = "agent-1",
                        summary = "Cached title",
                    ),
                ),
            ),
            refreshedAtMillis = 789L,
        )
        assertEquals("Cached title", db.conversationDao().getForAgentOnce("agent-1").single().summary)
        assertEquals(789L, db.conversationDao().getRefreshState("agent-1")?.lastRefreshAtMillis)
    }

    @Test
    fun `opens current v5 database and adds runtime event outbox`() = runTest {
        createLegacyDatabase(version = 5) { db ->
            createAgentsTable(db)
            createProjectBugReportsTable(db)
            createPendingLocalMessagesTable(db)
            createConversationTables(db)
        }

        val db = openMigratedDatabase()
        val outbox = RoomRuntimeEventOutbox(
            database = db,
            eventIdFactory = { _, offset -> RuntimeEventId("migration-event-${offset.value}") },
            clock = { EpochMillis(1_000) },
        )

        assertTrue(db.runtimeEventDao().listAfterOffset(0).isEmpty())
        val event = outbox.append(
            RuntimeEventDraft(
                backendId = BackendId("backend-1"),
                runtimeId = RuntimeId("runtime-1"),
                conversationId = ConversationId("conversation-1"),
                source = RuntimeEventSource.LocalUser,
                payload = RuntimeEventPayload.LocalUserAppend(
                    localMessageId = "local-1",
                    text = "hello",
                ),
            ),
        )

        assertEquals(1L, event.offset.value)
        assertEquals(1, db.runtimeEventDao().listAfterOffset(0).size)
    }

    @Test
    fun `opens current v6 database and adds memfs tables`() = runTest {
        createLegacyDatabase(version = 6) { db ->
            createAgentsTable(db)
            createProjectBugReportsTable(db)
            createPendingLocalMessagesTable(db)
            createConversationTables(db)
            createRuntimeEventsTable(db)
        }

        val db = openMigratedDatabase()
        val store = RoomMemFsStore(
            database = db,
            commitIdFactory = { _, revision, _ -> MemFsCommitId("migration-memfs-${revision.value}") },
            clock = { EpochMillis(1_000) },
        )

        assertTrue(db.memFsDao().listCommitsAfter(0).isEmpty())
        val commit = store.write(
            MemFsWriteCommand(
                path = MemFsPath("/memory/core.md"),
                content = "name: Ada",
            ),
        )

        assertEquals(1L, commit.revision.value)
        assertEquals("name: Ada", store.read(MemFsPath("/memory/core.md"))?.content)
        assertEquals(1, db.memFsDao().listCommitsAfter(0).size)
    }

    @Test
    fun `opens current v7 database and adds conversation cursor table`() = runTest {
        createLegacyDatabase(version = 7) { db ->
            createAgentsTable(db)
            createProjectBugReportsTable(db)
            createPendingLocalMessagesTable(db)
            createConversationTables(db)
            createRuntimeEventsTable(db)
            createMemFsTables(db)
            db.execSQL(
                """
                INSERT INTO conversations (
                    id, agentId, summary, createdAt, updatedAt, lastMessageAt,
                    archived, archivedAt, inContextMessageIdsJson,
                    isolatedBlockIdsJson, cachedAtEpochMs
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
                arrayOf<Any?>(
                    "conversation-1",
                    "agent-1",
                    "Existing conversation",
                    null,
                    null,
                    null,
                    null,
                    null,
                    "[]",
                    "[]",
                    1_000L,
                ),
            )
        }

        val db = openMigratedDatabase()

        assertEquals(null, db.conversationCursorDao().getCursor("conversation-1"))
        db.conversationCursorDao().upsertCursor(
            conversationId = "conversation-1",
            highestSeenSeq = 42L,
            updatedAt = 2_000L,
        )
        assertEquals(42L, db.conversationCursorDao().getCursor("conversation-1")?.highestSeenSeq)
    }

    private fun createLegacyDatabase(version: Int, createSchema: (SQLiteDatabase) -> Unit) {
        context.deleteDatabase(dbName)
        val db = context.openOrCreateDatabase(dbName, Context.MODE_PRIVATE, null)
        try {
            createSchema(db)
            db.version = version
        } finally {
            db.close()
        }
    }

    private fun openMigratedDatabase(): LettaDatabase {
        return Room.databaseBuilder(context, LettaDatabase::class.java, dbName)
            .addMigrations(*LettaDatabaseMigrations.ALL)
            .build()
            .also { database = it }
    }

    private fun createAgentsTable(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS agents (
                id TEXT NOT NULL,
                name TEXT NOT NULL,
                description TEXT,
                model TEXT,
                embedding TEXT,
                agentType TEXT,
                enableSleeptime INTEGER,
                createdAt TEXT,
                updatedAt TEXT,
                tagsJson TEXT,
                toolCount INTEGER NOT NULL,
                blockCount INTEGER NOT NULL,
                PRIMARY KEY(id)
            )
            """.trimIndent(),
        )
    }

    private fun createProjectBugReportsTable(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS project_bug_reports (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                projectIdentifier TEXT NOT NULL,
                title TEXT NOT NULL,
                description TEXT NOT NULL,
                severity TEXT NOT NULL,
                tagsJson TEXT NOT NULL,
                attachmentReferencesJson TEXT NOT NULL,
                structuredPrompt TEXT NOT NULL,
                createdAt TEXT NOT NULL
            )
            """.trimIndent(),
        )
    }

    private fun createPendingLocalMessagesTable(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS pending_local_messages (
                otid TEXT NOT NULL,
                conversationId TEXT NOT NULL,
                content TEXT NOT NULL,
                attachmentsJson TEXT NOT NULL,
                sentAtEpochMs INTEGER NOT NULL,
                PRIMARY KEY(otid)
            )
            """.trimIndent(),
        )
    }

    private fun createConversationTables(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS conversations (
                id TEXT NOT NULL,
                agentId TEXT NOT NULL,
                summary TEXT,
                createdAt TEXT,
                updatedAt TEXT,
                lastMessageAt TEXT,
                archived INTEGER,
                archivedAt TEXT,
                inContextMessageIdsJson TEXT NOT NULL,
                isolatedBlockIdsJson TEXT NOT NULL,
                cachedAtEpochMs INTEGER NOT NULL,
                PRIMARY KEY(id)
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS index_conversations_agentId ON conversations (agentId)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_conversations_lastMessageAt ON conversations (lastMessageAt)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_conversations_createdAt ON conversations (createdAt)")
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS conversation_refresh_state (
                agentId TEXT NOT NULL,
                lastRefreshAtMillis INTEGER NOT NULL,
                PRIMARY KEY(agentId)
            )
            """.trimIndent(),
        )
    }

    private fun createRuntimeEventsTable(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS runtime_events (
                eventOffset INTEGER NOT NULL,
                eventId TEXT NOT NULL,
                backendId TEXT NOT NULL,
                runtimeId TEXT NOT NULL,
                agentId TEXT,
                conversationId TEXT,
                runId TEXT,
                createdAtEpochMs INTEGER NOT NULL,
                source TEXT NOT NULL,
                schemaVersion INTEGER NOT NULL,
                payloadJson TEXT NOT NULL,
                PRIMARY KEY(eventOffset)
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_runtime_events_eventId ON runtime_events (eventId)")
        db.execSQL(
            """
            CREATE INDEX IF NOT EXISTS index_runtime_events_backendId_runtimeId_eventOffset
            ON runtime_events (backendId, runtimeId, eventOffset)
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS index_runtime_events_conversationId ON runtime_events (conversationId)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_runtime_events_agentId ON runtime_events (agentId)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_runtime_events_runId ON runtime_events (runId)")
    }

    private fun createMemFsTables(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS memfs_files (
                path TEXT NOT NULL,
                revision INTEGER NOT NULL,
                content TEXT NOT NULL,
                metadataJson TEXT NOT NULL,
                PRIMARY KEY(path)
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS memfs_commits (
                revision INTEGER NOT NULL,
                commitId TEXT NOT NULL,
                path TEXT NOT NULL,
                operation TEXT NOT NULL,
                createdAtEpochMs INTEGER NOT NULL,
                PRIMARY KEY(revision)
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_memfs_commits_commitId ON memfs_commits (commitId)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_memfs_commits_path ON memfs_commits (path)")
    }
}
