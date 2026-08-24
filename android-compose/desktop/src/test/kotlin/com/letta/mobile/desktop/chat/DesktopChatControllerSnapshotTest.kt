package com.letta.mobile.desktop.chat

import com.letta.mobile.data.model.LettaMessage
import com.letta.mobile.data.model.UserMessage
import com.letta.mobile.data.timeline.snapshot.InMemoryConfirmedTimelineStore
import com.letta.mobile.data.timeline.snapshot.StoredTimelineEnvelope
import com.letta.mobile.data.timeline.snapshot.StoredTimelineEvent
import com.letta.mobile.data.timeline.snapshot.TimelineScope
import com.letta.mobile.desktop.defaultDesktopBootstrapState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class DesktopChatControllerSnapshotTest {

    @Test
    fun productionLoopPublishesPersistedSnapshotBeforeRemoteHydrationCompletes() = runTest {
        val store = InMemoryConfirmedTimelineStore()
        val persistence = DesktopTimelinePersistence(store = store, backendId = "production-hydration-proof")
        store.writeSnapshot(
            StoredTimelineEnvelope(
                schemaVersion = StoredTimelineEnvelope.CURRENT_SCHEMA_VERSION,
                scope = TimelineScope(
                    backendId = persistence.backendId,
                    conversationId = "conv-1",
                    agentId = "agent-0",
                ),
                revision = 7,
                events = listOf(
                    StoredTimelineEvent(
                        position = 1.0,
                        otid = "persisted-user",
                        content = "Visible before the network",
                        serverId = "persisted-user-id",
                        messageType = "USER",
                        dateIso = "2026-08-23T10:00:00Z",
                    ),
                ),
                writtenAtMillis = 5_000,
            ),
        )
        val remoteGate = CompletableDeferred<Unit>()
        val gateway = GatedHydrationGateway(remoteGate)
        val controller = DesktopChatController(
            bootstrapState = defaultDesktopBootstrapState(),
            scope = this,
            gatewayFactory = { gateway },
            timelinePersistence = persistence,
        )

        controller.start()
        runCurrent()

        assertTrue(gateway.hydrationStarted.isCompleted)
        assertTrue(
            controller.state.value.selectedMessages.any { it.content == "Visible before the network" },
            "the production controller/loop path must publish the durable snapshot while remote hydration is pending",
        )

        remoteGate.complete(Unit)
        runCurrent()
        assertTrue(controller.state.value.selectedMessages.any { it.content == "Fresh remote history" })
        controller.close()
    }

    private class GatedHydrationGateway(
        private val remoteGate: CompletableDeferred<Unit>,
    ) : FakeDesktopChatGateway() {
        val hydrationStarted = CompletableDeferred<Unit>()

        override suspend fun listConversationMessages(
            conversationId: String,
            limit: Int?,
            after: String?,
            order: String?,
        ): List<LettaMessage> {
            hydrationStarted.complete(Unit)
            remoteGate.await()
            return listOf(
                UserMessage(
                    id = "fresh-remote-user",
                    contentRaw = JsonPrimitive("Fresh remote history"),
                    date = "2026-08-24T10:00:00Z",
                ),
            )
        }
    }
}
