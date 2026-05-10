package com.letta.mobile.data.local

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
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
        assertEquals("alpha,beta", agents.single().tagsJson)
        assertTrue(db.bugReportDao().getRecentForProject("project-1", limit = 10).isEmpty())
        assertTrue(db.pendingLocalDao().listForConversation("conversation-1").isEmpty())
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
                INSERT INTO pending_local_messages (
                    otid, conversationId, content, attachmentsJson, sentAtEpochMs
                ) VALUES (?, ?, ?, ?, ?)
                """.trimIndent(),
                arrayOf<Any?>(row.otid, row.conversationId, row.content, row.attachmentsJson, row.sentAtEpochMs),
            )
        }

        val db = openMigratedDatabase()

        assertEquals(listOf(row), db.pendingLocalDao().listForConversation("conversation-1"))
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
}
