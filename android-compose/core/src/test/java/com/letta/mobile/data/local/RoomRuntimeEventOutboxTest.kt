package com.letta.mobile.data.local

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.cash.turbine.test
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.runtime.BackendId
import com.letta.mobile.runtime.ConversationId
import com.letta.mobile.runtime.EpochMillis
import com.letta.mobile.runtime.RuntimeEventDraft
import com.letta.mobile.runtime.RuntimeEventId
import com.letta.mobile.runtime.RuntimeEventOffset
import com.letta.mobile.runtime.RuntimeEventPayload
import com.letta.mobile.runtime.RuntimeEventSource
import com.letta.mobile.runtime.RuntimeId
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.jupiter.api.Tag
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
@OptIn(ExperimentalCoroutinesApi::class)
@Tag("integration")
class RoomRuntimeEventOutboxTest {
    private var database: LettaDatabase? = null

    @After
    fun tearDown() {
        database?.close()
        database = null
    }

    @Test
    fun `append persists envelope and replays after offset`() = runTest {
        val outbox = outbox()

        val first = outbox.append(draft(localMessageId = "local-1"))
        val second = outbox.append(draft(localMessageId = "local-2"))

        assertEquals(RuntimeEventOffset(1), first.offset)
        assertEquals(RuntimeEventOffset(2), second.offset)
        assertEquals(
            listOf(second),
            outbox.events(first.offset).take(1).toList(),
        )
    }

    @Test
    fun `events flow observes live appends without duplicate replay`() = runTest {
        val outbox = outbox()

        outbox.events(RuntimeEventOffset(0)).test {
            val first = outbox.append(draft(localMessageId = "local-1"))
            val second = outbox.append(draft(localMessageId = "local-2"))

            assertEquals(first, awaitItem())
            assertEquals(second, awaitItem())
            expectNoEvents()
        }
    }

    private fun outbox(): RoomRuntimeEventOutbox {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, LettaDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        database = db
        return RoomRuntimeEventOutbox(
            database = db,
            eventIdFactory = { _, offset -> RuntimeEventId("test-runtime-event-${offset.value}") },
            clock = { EpochMillis(1_000) },
        )
    }

    private fun draft(localMessageId: String): RuntimeEventDraft = RuntimeEventDraft(
        backendId = BackendId("backend-1"),
        runtimeId = RuntimeId("runtime-1"),
        agentId = AgentId("agent-1"),
        conversationId = ConversationId("conversation-1"),
        source = RuntimeEventSource.LocalUser,
        payload = RuntimeEventPayload.LocalUserAppend(
            localMessageId = localMessageId,
            text = "hello",
        ),
    )
}
