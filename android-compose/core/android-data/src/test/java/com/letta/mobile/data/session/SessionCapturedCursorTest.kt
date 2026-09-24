package com.letta.mobile.data.session

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.letta.mobile.data.local.BackendConversationCursorFactory
import com.letta.mobile.data.local.LettaDatabase
import com.letta.mobile.data.model.LettaConfig
import com.letta.mobile.data.repository.api.ISettingsRepository
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class SessionCapturedCursorTest {
    @Test fun sameConfigIdEditKeepsEachGraphsExactConfigAndCursorAssociation() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val db = Room.inMemoryDatabaseBuilder(context, LettaDatabase::class.java).build()
        val original = LettaConfig(
            id = "same-config",
            mode = LettaConfig.Mode.CLOUD,
            serverUrl = "https://old.example.test",
            accessToken = "old-token",
        )
        val replacement = original.copy(
            serverUrl = "https://new.example.test",
            accessToken = "new-token",
        )
        val activeConfig = MutableStateFlow(original)
        val settings = mockk<ISettingsRepository>()
        every { settings.activeConfig } returns activeConfig
        val graphs = mutableListOf<SessionGraph>()
        try {
            val factory = createTestDefaultSessionRepositoryGraphFactory {
                appContext = context
                settingsRepository = settings
                cursorFactory = BackendConversationCursorFactory(db)
            }
            val first = factory.create().also(graphs::add)
            val descriptor = first.backendDescriptor
            val cursors = checkNotNull(first.conversationCursorStore)
            cursors.recordFrame("same", 8)

            activeConfig.value = replacement
            val second = factory.create().also(graphs::add)
            // Access after another mutation must not resolve either graph from settings.
            activeConfig.value = replacement.copy(accessToken = "later-token")
            assertSame(original, first.capturedConfig)
            assertSame(replacement, second.capturedConfig)
            assertSame(descriptor, first.backendDescriptor)
            assertEquals(original.serverUrl, first.backendDescriptor.label)
            assertEquals(replacement.serverUrl, second.backendDescriptor.label)
            assertEquals(first.backendDescriptor.backendId, second.backendDescriptor.backendId)
            assertSame(cursors, first.conversationCursorStore)
            assertNotSame(cursors, second.conversationCursorStore)
            for (graph in graphs) {
                val store = checkNotNull(graph.conversationCursorStore)
                assertEquals(graph.backendDescriptor.backendId.value, store.backendId)
                assertEquals(8L, store.getCursor("same"))
            }
        } finally {
            graphs.forEach { it.close() }
            graphs.forEach { it.scope.coroutineContext[Job]!!.join() }
            db.close()
        }
    }

    @Test fun graphSharesReplayRepairStoreAndRetiresBeforeSynchronousCloseReturns() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val db = Room.inMemoryDatabaseBuilder(context, LettaDatabase::class.java).build()
        try {
            val factory = createTestDefaultSessionRepositoryGraphFactory {
                appContext = context
                cursorFactory = BackendConversationCursorFactory(db)
            }
            val first = factory.create()
            val cursors = checkNotNull(first.conversationCursorStore)
            assertEquals(first.backendDescriptor.backendId.value, cursors.backendId)
            cursors.recordFrame("same", 8)
            assertTrue(cursors.replaceExpiredWatermark("same", 8, 2))
            first.close()
            try { cursors.recordFrame("same", 9); fail("Retired graph admitted write") }
            catch (_: IllegalStateException) { }
            first.scope.coroutineContext[Job]!!.join()
            val second = factory.create()
            try {
                assertNotSame(cursors, second.conversationCursorStore)
                assertEquals(2L, second.conversationCursorStore!!.getCursor("same"))
            } finally {
                second.close()
                second.scope.coroutineContext[Job]!!.join()
            }
        } finally { db.close() }
    }
}
