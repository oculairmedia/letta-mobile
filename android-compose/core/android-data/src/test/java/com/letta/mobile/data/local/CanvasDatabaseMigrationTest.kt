package com.letta.mobile.data.local

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.jupiter.api.Tag
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
@Tag("integration")
class CanvasDatabaseMigrationTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val dbName = "canvas-migration-${System.nanoTime()}.db"
    private var database: LettaDatabase? = null

    @After
    fun tearDown() {
        database?.close()
        context.deleteDatabase(dbName)
    }

    @Test
    fun migratesLegacyDatabaseAndCreatesCanvasDocumentsTable() = runBlocking {
        context.deleteDatabase(dbName)
        val legacyDb = context.openOrCreateDatabase(dbName, Context.MODE_PRIVATE, null)
        try {
            legacyDb.execSQL(
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
            legacyDb.version = 1
        } finally {
            legacyDb.close()
        }

        val db = Room.databaseBuilder(context, LettaDatabase::class.java, dbName)
            .addMigrations(*LettaDatabaseMigrations.ALL)
            .build()
            .also { database = it }

        val dao = db.canvasDocumentDao()
        val doc = CanvasDocumentEntity(
            id = "canvas-1",
            agentId = "agent-1",
            conversationId = "conv-1",
            title = "Test Canvas",
            revision = 1L,
            sceneJson = """{"bgColor":-1,"elements":[]}""",
            updatedAtEpochMs = 12345L,
        )
        dao.upsert(doc)
        val loaded = dao.getById("canvas-1")
        assertEquals(doc, loaded)
        assertEquals(doc, dao.getForConversation("conv-1"))
        assertEquals(listOf(doc), dao.listForAgent("agent-1"))
    }
}
