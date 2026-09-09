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
                val coordinatorField = graph.channelTransport.javaClass
                    .getDeclaredField("cursorCoordinator").apply { isAccessible = true }
                val coordinator = coordinatorField.get(graph.channelTransport)
                val storeField = coordinator.javaClass
                    .getDeclaredField("conversationCursorStore").apply { isAccessible = true }
                assertSame(store, storeField.get(coordinator))
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
            // Verify the transport's actual replay coordinator, not a second test-only adapter.
            val coordinatorField = first.channelTransport.javaClass.getDeclaredField("cursorCoordinator").apply { isAccessible = true }
            val coordinator = coordinatorField.get(first.channelTransport)
            val storeField = coordinator.javaClass.getDeclaredField("conversationCursorStore").apply { isAccessible = true }
            assertSame(cursors, storeField.get(coordinator))
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

    @Test fun capturedExpiryRetainsExpectedWatermarkForCommitSpanningReplacement() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val db = Room.inMemoryDatabaseBuilder(context, LettaDatabase::class.java).build()
        try {
            val factory = createTestDefaultSessionRepositoryGraphFactory {
                appContext = context
                cursorFactory = BackendConversationCursorFactory(db)
            }
            val graph = factory.create()
            val cursors = checkNotNull(graph.conversationCursorStore)
            cursors.recordFrame("same", 100)
            val coordinatorField = graph.channelTransport.javaClass.getDeclaredField("cursorCoordinator").apply { isAccessible = true }
            val coordinator = coordinatorField.get(graph.channelTransport) as com.letta.mobile.data.transport.CursorResumeCoordinator
            val runStoreField = coordinator.javaClass.getDeclaredField("cursorStore").apply { isAccessible = true }
            val runStore = runStoreField.get(coordinator) as com.letta.mobile.data.transport.RunCursorStore
            runStore.record("same", "run-1", 10L)
            coordinator.registerResumedRun("run-1", "same")
            coordinator.clearExpiredCursor(
                com.letta.mobile.data.transport.ServerFrame.Error(
                    id = "err",
                    ts = "ts",
                    code = "cursor_expired",
                    conversationId = "same",
                    runId = "run-1",
                    afterSeq = 100L,
                ),
            ) { "SimpleState" }
            kotlinx.coroutines.yield()
            assertEquals(100L, cursors.getCursor("same"))
            assertTrue(cursors.replaceExpiredWatermark("same", 100, 7))
            assertEquals(7L, cursors.getCursor("same"))
            graph.close()
            graph.scope.coroutineContext[Job]!!.join()
        } finally { db.close() }
    }
}
