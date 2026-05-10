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
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Ignore
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
    fun `documents current destructive migration production risk`() {
        val source = lettaDatabaseSource().readText()

        assertTrue(
            "This characterization should be inverted when letta-mobile-yoic.4.4 removes destructive migration.",
            source.contains("fallbackToDestructiveMigration"),
        )
    }

    @Ignore("Enable when letta-mobile-yoic.4.4 removes fallbackToDestructiveMigration from production builder")
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
    fun `documents AgentEntity tags currently lose comma-containing tag boundaries`() {
        val originalTags = listOf("alpha,beta", "gamma")
        val entity = AgentEntity.fromAgent(TestData.agent(tags = originalTags))

        assertEquals("alpha,beta,gamma", entity.tagsJson)
        assertNotEquals(originalTags, entity.toAgent().tags)
        assertEquals(listOf("alpha", "beta", "gamma"), entity.toAgent().tags)
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
