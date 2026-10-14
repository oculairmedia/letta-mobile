package com.letta.mobile.data.local

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
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
 * letta-mobile-by4vm.17: v19 -> v20 gives pending (unsent) messages an owning backend and
 * agent. Existing rows survive with NULL owners (legacy) and stay readable from any scope
 * until the next backend switch purges them; new rows are isolated per backend and agent.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
@Tag("integration")
class LettaDatabasePendingLocalScopeMigrationTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val dbName = "letta-pending-scope-migration-${System.nanoTime()}.db"
    private var database: LettaDatabase? = null

    @After
    fun tearDown() {
        database?.close()
        context.deleteDatabase(dbName)
    }

    @Test
    fun migratesV3PendingLocalsToV20WithLegacyOwnersAndScopedIndex() = runBlocking {
        createV3DatabaseWithLegacyPendingRow()

        val db = Room.databaseBuilder(context, LettaDatabase::class.java, dbName)
            .addMigrations(*LettaDatabaseMigrations.ALL)
            .build()
            .also { database = it }
        val dao = db.pendingLocalDao()

        val legacy = checkNotNull(dao.findByOtid("otid-legacy"))
        assertNull(legacy.backendId)
        assertNull(legacy.agentId)
        assertEquals("SENT", legacy.deliveryState)
        val indexNames = db.openHelper.readableDatabase.query("PRAGMA index_list(`pending_local_messages`)").use { cursor ->
            buildList { while (cursor.moveToNext()) add(cursor.getString(cursor.getColumnIndexOrThrow("name"))) }
        }
        assertTrue(indexNames.contains("index_pending_local_messages_backendId_agentId_conversationId"))

        dao.upsert(legacy.copy(otid = "otid-a", backendId = "backend-a", agentId = "agent-a"))
        dao.upsert(legacy.copy(otid = "otid-b", backendId = "backend-a", agentId = "agent-b"))
        assertEquals(listOf("otid-legacy", "otid-a"), dao.listForScope("backend-a", "agent-a", SHARED).map { it.otid })
        assertEquals(listOf("otid-legacy"), dao.listForScope("backend-z", "agent-a", SHARED).map { it.otid })

        dao.deleteForBackend("backend-z")
        assertEquals(emptyList<String>(), dao.listForScope("backend-z", "agent-a", SHARED).map { it.otid })
        assertEquals(listOf("otid-b"), dao.listForScope("backend-a", "agent-b", SHARED).map { it.otid })
    }

    private fun createV3DatabaseWithLegacyPendingRow() {
        context.deleteDatabase(dbName)
        val db = context.openOrCreateDatabase(dbName, Context.MODE_PRIVATE, null)
        try {
            db.execSQL(
                "CREATE TABLE agents (id TEXT NOT NULL, name TEXT NOT NULL, description TEXT, model TEXT, " +
                    "embedding TEXT, agentType TEXT, enableSleeptime INTEGER, createdAt TEXT, updatedAt TEXT, " +
                    "tagsJson TEXT, toolCount INTEGER NOT NULL, blockCount INTEGER NOT NULL, PRIMARY KEY(id))",
            )
            db.execSQL(
                "CREATE TABLE project_bug_reports (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "projectIdentifier TEXT NOT NULL, title TEXT NOT NULL, description TEXT NOT NULL, " +
                    "severity TEXT NOT NULL, tagsJson TEXT NOT NULL, attachmentReferencesJson TEXT NOT NULL, " +
                    "structuredPrompt TEXT NOT NULL, createdAt TEXT NOT NULL)",
            )
            db.execSQL(
                "CREATE TABLE pending_local_messages (otid TEXT NOT NULL, conversationId TEXT NOT NULL, " +
                    "content TEXT NOT NULL, attachmentsJson TEXT NOT NULL, sentAtEpochMs INTEGER NOT NULL, PRIMARY KEY(otid))",
            )
            db.execSQL(
                "INSERT INTO pending_local_messages (otid, conversationId, content, attachmentsJson, sentAtEpochMs) " +
                    "VALUES ('otid-legacy', '$SHARED', 'unsent image', '[]', 7)",
            )
            db.version = 3
        } finally {
            db.close()
        }
    }

    private companion object {
        const val SHARED = "conv-default-shared"
    }
}
