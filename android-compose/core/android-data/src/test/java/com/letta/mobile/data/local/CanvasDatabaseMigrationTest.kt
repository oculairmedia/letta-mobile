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
    fun migratesLegacyDatabaseToTheNotebookJournalWithoutCanvasDocuments() = runBlocking {
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

        // letta-mobile-by4vm.22: canvases live in the notebook store; canvas_documents is gone.
        val canvasTables = db.openHelper.readableDatabase
            .query("SELECT name FROM sqlite_master WHERE type = 'table' AND name LIKE 'canvas%' ORDER BY name")
            .use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.getString(0)) } }
        assertEquals(listOf("canvas_ops"), canvasTables)
    }
}
