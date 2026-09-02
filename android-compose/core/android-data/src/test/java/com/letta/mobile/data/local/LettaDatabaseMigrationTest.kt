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
import kotlinx.coroutines.runBlocking
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
    fun `migrates v12 normalized heads with explicit empty row digest marker`() = runBlocking {
        createLegacyDatabase(version = 12) { db ->
            createAgentsTable(db)
            createProjectBugReportsTable(db)
            createPendingLocalMessagesTable(db)
            createConversationTables(db)
            createRuntimeEventsTable(db)
            createMemFsTables(db)
            db.execSQL(
                "CREATE TABLE conversation_cursors (conv_id TEXT NOT NULL, highest_seen_seq INTEGER NOT NULL, " +
                    "updated_at INTEGER NOT NULL, PRIMARY KEY(conv_id))",
            )
            db.execSQL("ALTER TABLE agents ADD COLUMN metadataJson TEXT")
            db.execSQL(
                "CREATE TABLE confirmed_timeline_snapshots (backend_id TEXT NOT NULL, conversation_id TEXT NOT NULL, " +
                    "agent_id TEXT, active_manifest_id TEXT, fallback_manifest_id TEXT, " +
                    "high_water_revision INTEGER NOT NULL, written_at_millis INTEGER NOT NULL, " +
                    "PRIMARY KEY(backend_id, conversation_id))",
            )
            db.execSQL(
                "CREATE INDEX index_confirmed_timeline_snapshots_backend_id_written_at_millis " +
                    "ON confirmed_timeline_snapshots (backend_id, written_at_millis)",
            )
            db.execSQL(
                "CREATE TABLE confirmed_timeline_snapshot_manifests (manifest_id TEXT NOT NULL, backend_id TEXT NOT NULL, " +
                    "conversation_id TEXT NOT NULL, agent_id TEXT, revision INTEGER NOT NULL, schema_version INTEGER NOT NULL, " +
                    "byte_length INTEGER NOT NULL, chunk_count INTEGER NOT NULL, sha256 TEXT NOT NULL, " +
                    "written_at_millis INTEGER NOT NULL, PRIMARY KEY(manifest_id))",
            )
            db.execSQL(
                "CREATE INDEX index_confirmed_timeline_snapshot_manifests_backend_id_conversation_id_revision " +
                    "ON confirmed_timeline_snapshot_manifests (backend_id, conversation_id, revision)",
            )
            db.execSQL(
                "CREATE INDEX index_confirmed_timeline_snapshot_manifests_backend_id " +
                    "ON confirmed_timeline_snapshot_manifests (backend_id)",
            )
            db.execSQL(
                "CREATE TABLE confirmed_timeline_snapshot_chunks (manifest_id TEXT NOT NULL, chunk_index INTEGER NOT NULL, " +
                    "payload BLOB NOT NULL, PRIMARY KEY(manifest_id, chunk_index), " +
                    "FOREIGN KEY(manifest_id) REFERENCES confirmed_timeline_snapshot_manifests(manifest_id) ON DELETE CASCADE)",
            )
            db.execSQL(
                "CREATE INDEX index_confirmed_timeline_snapshot_chunks_manifest_id " +
                    "ON confirmed_timeline_snapshot_chunks (manifest_id)",
            )
            db.execSQL(
                """
                CREATE TABLE normalized_timeline_snapshot_heads (
                    backend_id TEXT NOT NULL,
                    conversation_id TEXT NOT NULL,
                    agent_id TEXT,
                    storage_layout_version INTEGER NOT NULL,
                    revision INTEGER NOT NULL,
                    envelope_schema_version INTEGER NOT NULL,
                    live_cursor TEXT,
                    backfill_cursor TEXT,
                    released_older_count INTEGER NOT NULL,
                    row_count INTEGER NOT NULL,
                    root_digest TEXT NOT NULL,
                    generation INTEGER NOT NULL,
                    written_at_millis INTEGER NOT NULL,
                    PRIMARY KEY(backend_id, conversation_id)
                )
                """.trimIndent(),
            )
            db.execSQL(
                "CREATE TABLE normalized_timeline_snapshot_rows (backend_id TEXT NOT NULL, conversation_id TEXT NOT NULL, " +
                    "identity_primary INTEGER NOT NULL, identity_secondary INTEGER NOT NULL, event_order INTEGER NOT NULL, " +
                    "payload BLOB NOT NULL, checksum TEXT NOT NULL, " +
                    "PRIMARY KEY(backend_id, conversation_id, identity_primary, identity_secondary))",
            )
            db.execSQL(
                "CREATE UNIQUE INDEX index_normalized_timeline_snapshot_rows_backend_id_conversation_id_event_order " +
                    "ON normalized_timeline_snapshot_rows (backend_id, conversation_id, event_order)",
            )
            db.execSQL(
                """
                INSERT INTO normalized_timeline_snapshot_heads VALUES
                ('backend', 'conversation', 'agent', 1, 12, 12, NULL, NULL, 0, 0, 'legacy-root', 12, 123)
                """.trimIndent(),
            )
        }

        val db = openMigratedDatabase()
        val head = requireNotNull(db.confirmedTimelineSnapshotDao().getNormalizedHead("backend", "conversation"))
        assertEquals("", head.rowDigest)
        assertEquals(12L, head.revision)
    }

    @Test
    fun `migrates reconstructed v1 database to latest and preserves agents`() = runBlocking {
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
    fun `migrates reconstructed v2 database to latest and preserves bug reports`() = runBlocking {
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
    fun `opens current v3 database and preserves pending local messages`() = runBlocking {
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
    fun `opens current v4 database and adds conversation cache tables`() = runBlocking {
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
                        id = com.letta.mobile.data.model.ConversationId("conversation-1"),
                        agentId = com.letta.mobile.data.model.AgentId("agent-1"),
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
    fun `opens current v5 database and adds runtime event outbox`() = runBlocking {
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
    fun `opens current v6 database and adds memfs tables`() = runBlocking {
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
    fun `opens current v7 database and adds conversation cursor table`() = runBlocking {
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
