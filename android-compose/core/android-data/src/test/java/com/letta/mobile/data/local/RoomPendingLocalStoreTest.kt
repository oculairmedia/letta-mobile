package com.letta.mobile.data.local

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.letta.mobile.data.model.MessageContentPart
import com.letta.mobile.data.timeline.DeliveryState
import com.letta.mobile.data.timeline.PendingLocalRecord
import com.letta.mobile.data.timeline.PendingLocalScope
import java.time.Instant
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class RoomPendingLocalStoreTest {
    private val database = Room.inMemoryDatabaseBuilder(
        ApplicationProvider.getApplicationContext(),
        LettaDatabase::class.java,
    ).allowMainThreadQueries().build()
    private var activeBackend = BACKEND_A
    private val store = RoomPendingLocalStore(database.pendingLocalDao(), activeBackendId = { activeBackend })

    @After fun close() = database.close()

    @Test fun newestFailedImageSendSupersedesThePreviousFailure() = runTest {
        val first = imageRecord("first", 1L)
        val second = imageRecord("second", 2L)
        store.save(first)
        store.markFailed(first.otid)
        store.save(second)
        store.markFailed(second.otid)

        val loaded = store.load(PendingLocalScope(BACKEND_A, CONVERSATION, AGENT_A))

        assertEquals(1, loaded.size)
        assertEquals("second", loaded.single().otid)
        assertEquals(DeliveryState.FAILED, loaded.single().deliveryState)
        assertEquals(1, loaded.single().attachments.size)
    }

    @Test fun sameConversationIdUnderTwoAgentsStaysSeparate() = runTest {
        store.save(imageRecord("from-a", 1L, agentId = AGENT_A))
        store.save(imageRecord("from-b", 2L, agentId = AGENT_B))

        assertEquals(listOf("from-a"), store.load(PendingLocalScope(BACKEND_A, CONVERSATION, AGENT_A)).map { it.otid })
        assertEquals(listOf("from-b"), store.load(PendingLocalScope(BACKEND_A, CONVERSATION, AGENT_B)).map { it.otid })
    }

    @Test fun sameConversationIdUnderTwoBackendsStaysSeparate() = runTest {
        store.save(imageRecord("on-a", 1L, backendId = BACKEND_A))
        store.save(imageRecord("on-b", 2L, backendId = BACKEND_B))

        assertEquals(listOf("on-a"), store.load(PendingLocalScope(BACKEND_A, CONVERSATION, AGENT_A)).map { it.otid })
        assertEquals(listOf("on-b"), store.load(PendingLocalScope(BACKEND_B, CONVERSATION, AGENT_A)).map { it.otid })
    }

    @Test fun failureSupersessionStaysInsideItsOwnAgent() = runTest {
        store.save(imageRecord("agent-a-failed", 1L, agentId = AGENT_A))
        store.markFailed("agent-a-failed")
        store.save(imageRecord("agent-b-failed", 2L, agentId = AGENT_B))
        store.markFailed("agent-b-failed")

        assertEquals(listOf("agent-a-failed"), store.load(PendingLocalScope(BACKEND_A, CONVERSATION, AGENT_A)).map { it.otid })
        assertEquals(listOf("agent-b-failed"), store.load(PendingLocalScope(BACKEND_A, CONVERSATION, AGENT_B)).map { it.otid })
    }

    @Test fun legacyRowWithoutOwnerIsReadableUntilTheNextBackendSwitch() = runTest {
        store.save(imageRecord("legacy", 1L, backendId = null, agentId = null))

        assertEquals(listOf("legacy"), store.load(PendingLocalScope(BACKEND_A, CONVERSATION, AGENT_A)).map { it.otid })
        store.clearForBackendSwitch()
        assertEquals(emptyList<String>(), store.load(PendingLocalScope(BACKEND_B, CONVERSATION, AGENT_A)).map { it.otid })
    }

    @Test fun backendSwitchClearsTheOutgoingBackendOnly() = runTest {
        store.save(imageRecord("on-a", 1L, backendId = BACKEND_A))
        store.save(imageRecord("on-b", 2L, backendId = BACKEND_B))

        activeBackend = BACKEND_A
        store.clearForBackendSwitch()

        assertEquals(emptyList<String>(), store.load(PendingLocalScope(BACKEND_A, CONVERSATION, AGENT_A)).map { it.otid })
        assertEquals(listOf("on-b"), store.load(PendingLocalScope(BACKEND_B, CONVERSATION, AGENT_A)).map { it.otid })
    }

    @Test fun recordRoundTripKeepsItsOwner() = runTest {
        store.save(imageRecord("owned", 1L))

        val loaded = store.load(PendingLocalScope(BACKEND_A, CONVERSATION, AGENT_A)).single()

        assertEquals(BACKEND_A, loaded.backendId)
        assertEquals(AGENT_A, loaded.agentId)
    }

    private fun imageRecord(
        otid: String,
        millis: Long,
        backendId: String? = BACKEND_A,
        agentId: String? = AGENT_A,
    ) = PendingLocalRecord(
        otid = otid,
        conversationId = CONVERSATION,
        content = "caption",
        attachments = listOf(MessageContentPart.Image("aGVsbG8=", "image/png")),
        sentAt = Instant.ofEpochMilli(millis),
        deliveryState = DeliveryState.SENT,
        backendId = backendId,
        agentId = agentId,
    )

    private companion object {
        const val BACKEND_A = "backend-a"
        const val BACKEND_B = "backend-b"
        const val AGENT_A = "agent-a"
        const val AGENT_B = "agent-b"
        const val CONVERSATION = "conv-default-shared"
    }
}
