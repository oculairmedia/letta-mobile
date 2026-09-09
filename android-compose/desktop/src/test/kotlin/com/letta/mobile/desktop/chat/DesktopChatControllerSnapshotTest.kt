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

    @Test
    fun gatedCanonicalOpeningNeverStartsLegacyHydrationAndCloseCancelsIt() = runTest {
        val gateway = GatedHydrationGateway(CompletableDeferred())
        val opened = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()
        val controller = DesktopChatController(
            bootstrapState = defaultDesktopBootstrapState(), scope = this,
            gatewayFactory = { gateway },
        )
        controller.canonicalEligible = { true }
        controller.canonicalOpen = { _, _, _ ->
            opened.complete(Unit)
            try { kotlinx.coroutines.awaitCancellation() }
            finally { cancelled.complete(Unit) }
        }
        controller.start()
        runCurrent()
        assertTrue(opened.isCompleted)
        kotlin.test.assertFalse(gateway.hydrationStarted.isCompleted)
        controller.close()
        runCurrent()
        assertTrue(cancelled.isCompleted)
    }

    @Test
    fun canonicalOpenFailureDoesNotFallBackToLegacyHydration() = runTest {
        val gateway = GatedHydrationGateway(CompletableDeferred())
        val controller = DesktopChatController(
            bootstrapState = defaultDesktopBootstrapState(), scope = this,
            gatewayFactory = { gateway },
        )
        controller.canonicalEligible = { true }
        controller.canonicalOpen = { _, _, _ -> error("Canonical unavailable") }
        controller.start()
        runCurrent()
        kotlin.test.assertFalse(gateway.hydrationStarted.isCompleted)
        controller.close()
    }

    @Test
    fun rapidConversationSwitchClosesOnlyTheDetachedCanonicalOpen() = runTest {
        val gateway = FakeDesktopChatGateway(conversationIds = listOf("conv-1", "conv-2"))
        val coordinator = com.letta.mobile.data.timeline.CanonicalTimelineCoordinator(
            PresentationStore(),
            object : com.letta.mobile.data.timeline.TimelineTransport {
                override suspend fun listConversationMessagePage(
                    request: com.letta.mobile.data.timeline.TimelineRemotePageRequest,
                    progress: com.letta.mobile.data.timeline.TimelinePageProgress?,
                ) = error("Presentation must not eagerly fetch")
                override suspend fun sendConversationMessage(
                    conversationId: String,
                    request: com.letta.mobile.data.model.MessageCreateRequest,
                ) = error("No send")
                override suspend fun streamConversation(conversationId: String) = error("No stream")
                override suspend fun listConversationMessages(
                    conversationId: String, limit: Int?, after: String?, order: String?,
                ) = error("No legacy hydration")
                override suspend fun listAgentMessages(
                    agentId: String, limit: Int?, order: String?, conversationId: String?,
                ) = error("No legacy hydration")
            },
        )
        val owners = mutableMapOf<String, com.letta.mobile.data.timeline.CanonicalTimelineCoordinator.Owner>()
        val controller = DesktopChatController(
            bootstrapState = defaultDesktopBootstrapState(), scope = this,
            gatewayFactory = { gateway },
        )
        controller.canonicalEligible = { true }
        controller.canonicalOpen = { agent, conversation, scope ->
            val owner = coordinator.acquire(
                TimelineScope("backend", conversation, agent),
            )
            owners[conversation] = owner
            com.letta.mobile.desktop.data.openDesktopCanonicalPresentation(coordinator, owner, scope)
        }
        controller.start()
        runCurrent()
        val first = checkNotNull(owners["conv-1"])
        kotlin.test.assertFalse(coordinator.retire(first))
        kotlin.test.assertEquals(emptyList<String>(), gateway.conversationMessageRequests)
        controller.selectConversation("conv-2")
        runCurrent()
        kotlin.test.assertTrue(coordinator.retire(first))
        val second = checkNotNull(owners["conv-2"])
        kotlin.test.assertFalse(coordinator.retire(second))
        kotlin.test.assertEquals(emptyList<String>(), gateway.conversationMessageRequests)
        controller.close()
        runCurrent()
        kotlin.test.assertTrue(coordinator.retire(second))
    }

    private class PresentationStore : com.letta.mobile.data.timeline.TimelineBoundedStore {
        private val reader = object : com.letta.mobile.data.timeline.TimelineStoreTransaction {
            override suspend fun toolCall(callId: String) = null
            override suspend fun unresolvedTools(afterCallId: String?, maxRows: Int) = emptyList<com.letta.mobile.data.timeline.TimelineToolIndexEntry>()
            override suspend fun toolSweepGeneration() = 0L
            override suspend fun putToolCall(entry: com.letta.mobile.data.timeline.TimelineToolIndexEntry) = Unit
            override suspend fun setToolSweepGeneration(next: Long) = Unit
            override suspend fun checkpoint() = com.letta.mobile.data.timeline.TimelineDurableCheckpoint(0, null, false)
            override suspend fun locate(identity: com.letta.mobile.data.timeline.TimelineMessageId) = null
            override suspend fun metadata(
                position: com.letta.mobile.data.timeline.TimelineReadPosition,
                maxRows: Int,
            ) = com.letta.mobile.data.timeline.TimelineMetadataPage(emptyList(), null, null, 0)
            override suspend fun body(
                pointer: com.letta.mobile.data.timeline.TimelineBodyPointer,
                offset: Long,
                maxBytes: Int,
            ) = error("No body")
            override suspend fun evidence(key: String, maxBytes: Int) = null
            override suspend fun put(record: com.letta.mobile.data.timeline.TimelineStoredRecord) = error("Unexpected write")
            override suspend fun putEvidence(key: String, value: ByteArray) = Unit
            override suspend fun deleteEvidence(key: String) = Unit
            override suspend fun cursor(continuation: com.letta.mobile.data.timeline.TimelineContinuation?, hasMore: Boolean) = Unit
            override suspend fun nextRevision() = 1L
            override suspend fun delete(
                identity: com.letta.mobile.data.timeline.TimelineMessageId,
                reason: com.letta.mobile.data.timeline.TimelineDurableDeleteReason,
            ) = Unit
        }
        override suspend fun <T> read(
            scope: TimelineScope,
            block: suspend com.letta.mobile.data.timeline.TimelineStoreReader.() -> T,
        ) = block(reader)
        override suspend fun <T> transaction(
            scope: TimelineScope,
            block: suspend com.letta.mobile.data.timeline.TimelineStoreTransaction.() -> T,
        ) = block(reader)
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
