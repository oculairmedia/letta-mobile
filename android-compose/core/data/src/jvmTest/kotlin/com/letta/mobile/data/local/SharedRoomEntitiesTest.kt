package com.letta.mobile.data.local

import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

// The fixture compiles every extracted DAO on JVM. Production builders remain in Android.
@Database(
    entities = [AgentEntity::class, BugReportEntity::class, ConversationEntity::class,
        ConversationRefreshEntity::class, RuntimeEventEntity::class, CanvasOpEntity::class],
    version = 25,
    exportSchema = false,
)
abstract class SharedRoomFixture : RoomDatabase() {
    abstract fun agents(): AgentDao
    abstract fun conversations(): ConversationDao
    abstract fun canvasOps(): CanvasOpDao
    abstract fun bugs(): BugReportDao
    abstract fun events(): RuntimeEventDao
}

class SharedRoomEntitiesTest {
    @Test
    fun jvmDriverMatchesAndroidSchemaAndRetainsPendingWritesAcrossRestart() = runBlocking {
        val directory = Files.createTempDirectory("shared-room-entities-")
        val path = directory.resolve("shared.db")
        try {
            val pending = CanvasOpEntity("op", "canvas", 1, "actor", "add_element", "{}", 123, false)
            withDatabase(path) { db ->
                db.agents().upsert(AgentEntity("agent", "Ada"))
                db.conversations().replaceForAgent("agent", listOf(ConversationEntity("conversation", "agent")), 123)
                db.canvasOps().insert(pending)
            }
            withDatabase(path) { db ->
                assertEquals("Ada", db.agents().getAllOnce().single().name)
                assertEquals("conversation", db.conversations().getForAgentOnce("agent").single().id)
                assertEquals(123L, db.conversations().getRefreshState("agent")?.lastRefreshAtMillis)
                assertEquals(listOf(pending), db.canvasOps().getUnsettled("canvas"))
                assertFalse(db.canvasOps().getUnsettled("canvas").single().settled)
            }
            val schema = Json.parseToJsonElement(Files.readString(Path.of(
                requireNotNull(System.getProperty("room.productionSchema")),
            ))).jsonObject.getValue("database").jsonObject
            BundledSQLiteDriver().open(path.toString()).use { connection ->
                connection.prepare("SELECT identity_hash FROM room_master_table WHERE id = 42").use { statement ->
                    check(statement.step())
                    assertEquals(schema.getValue("identityHash").jsonPrimitive.content, statement.getText(0))
                }
            }
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    private fun open(path: Path): SharedRoomFixture = Room.databaseBuilder<SharedRoomFixture>(path.toString())
        .setDriver(BundledSQLiteDriver())
        .build()

    private suspend fun <T> withDatabase(path: Path, action: suspend (SharedRoomFixture) -> T): T {
        val database = open(path)
        return try {
            action(database)
        } finally {
            database.close()
        }
    }
}
