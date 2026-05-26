package com.letta.mobile.feature.chat

import com.letta.mobile.data.model.AssistantMessage
import com.letta.mobile.data.model.ErrorMessage
import com.letta.mobile.runtime.BackendCapabilities
import com.letta.mobile.runtime.BackendDescriptor
import com.letta.mobile.runtime.BackendId
import com.letta.mobile.runtime.BackendKind
import com.letta.mobile.runtime.EpochMillis
import com.letta.mobile.runtime.InMemoryMemFsStore
import com.letta.mobile.runtime.InMemoryRuntimeEventOutbox
import com.letta.mobile.runtime.LocalLettaBackend
import com.letta.mobile.runtime.MemFsCommitId
import com.letta.mobile.runtime.RuntimeEventDraft
import com.letta.mobile.runtime.RuntimeEventId
import com.letta.mobile.runtime.RuntimeEventPayload
import com.letta.mobile.runtime.RuntimeEventSource
import com.letta.mobile.runtime.RuntimeId
import com.letta.mobile.runtime.RuntimeRunStatus
import com.letta.mobile.runtime.TurnEngine
import com.letta.mobile.testutil.FakeTimelineExternalTransportWriter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LocalRuntimeChatSendCoordinatorTest {
    @Test
    fun `send runs local backend and projects assistant frame into timeline`() = runTest {
        val timelineRepository = FakeTimelineExternalTransportWriter()
        val uiState = MutableStateFlow(ChatUiState(agentName = "Agent"))
        var cleared = false
        var activeConversation: String? = null
        var observedConversation: String? = null
        val coordinator = coordinator(
            scope = backgroundScope,
            timelineRepository = timelineRepository,
            uiState = uiState,
            backend = backend(
                engine = TurnEngine {
                    flowOf(
                        RuntimeEventDraft(
                            backendId = BackendId("foreign"),
                            runtimeId = RuntimeId("foreign"),
                            source = RuntimeEventSource.LocalRuntime,
                            payload = RuntimeEventPayload.RemoteStreamFrame(
                                frameId = "assistant-1",
                                messageId = "assistant-1",
                                messageType = "assistant_message",
                                body = "Hello from local runtime",
                            ),
                        ),
                        RuntimeEventDraft(
                            backendId = BackendId("foreign"),
                            runtimeId = RuntimeId("foreign"),
                            source = RuntimeEventSource.LocalRuntime,
                            payload = RuntimeEventPayload.RunLifecycleChanged(RuntimeRunStatus.Completed),
                        ),
                    )
                }
            ),
            clearComposerAfterSend = { cleared = true },
            activeConversationId = { activeConversation },
            setActiveConversationId = { activeConversation = it },
            startTimelineObserver = { observedConversation = it },
        )

        coordinator.send("hello").join()

        val local = timelineRepository.externalLocals.single()
        assertEquals("hello", local.content)
        assertTrue(local.otid.startsWith("client-"))
        val resolvedConversation = requireNotNull(activeConversation)
        assertTrue(resolvedConversation.startsWith("local-conv-agent-1-"))
        assertEquals(activeConversation, observedConversation)
        assertTrue(cleared)
        assertEquals(FakeTimelineExternalTransportWriter.LocalMarker(resolvedConversation, local.otid), timelineRepository.sentLocals.single())
        val assistant = timelineRepository.ingestedMessages.single().message as AssistantMessage
        assertEquals("assistant-1", assistant.id)
        assertEquals("Hello from local runtime", assistant.content)
        assertEquals(false, uiState.value.isStreaming)
        assertEquals(false, uiState.value.isAgentTyping)
        assertEquals(null, uiState.value.error)
    }

    @Test
    fun `failed local lifecycle marks optimistic local failed and surfaces timeline error`() = runTest {
        val timelineRepository = FakeTimelineExternalTransportWriter()
        val uiState = MutableStateFlow(ChatUiState(agentName = "Agent"))
        val coordinator = coordinator(
            scope = backgroundScope,
            timelineRepository = timelineRepository,
            uiState = uiState,
            backend = backend(
                engine = TurnEngine {
                    flowOf(
                        RuntimeEventDraft(
                            backendId = BackendId("foreign"),
                            runtimeId = RuntimeId("foreign"),
                            source = RuntimeEventSource.LocalRuntime,
                            payload = RuntimeEventPayload.RunLifecycleChanged(
                                status = RuntimeRunStatus.Failed,
                                reason = "Embedded LettaCode runtime is not enabled in this build.",
                            ),
                        )
                    )
                }
            ),
        )

        coordinator.send("hello").join()

        val local = timelineRepository.externalLocals.single()
        assertEquals(FakeTimelineExternalTransportWriter.LocalMarker(local.conversationId, local.otid), timelineRepository.failedLocals.single())
        val error = timelineRepository.ingestedMessages.single().message as ErrorMessage
        assertEquals("Embedded LettaCode runtime is not enabled in this build.", error.text)
        assertEquals("Embedded LettaCode runtime is not enabled in this build.", uiState.value.error)
        assertFalse(uiState.value.isStreaming)
        assertFalse(uiState.value.isAgentTyping)
    }

    @Test
    fun `image attachments are rejected before mutating timeline`() = runTest {
        val timelineRepository = FakeTimelineExternalTransportWriter()
        val uiState = MutableStateFlow(ChatUiState(agentName = "Agent"))
        val coordinator = coordinator(
            scope = backgroundScope,
            timelineRepository = timelineRepository,
            uiState = uiState,
        )

        coordinator.send(
            text = "look",
            attachments = listOf(
                com.letta.mobile.data.model.MessageContentPart.Image(
                    base64 = "AAA=",
                    mediaType = "image/png",
                )
            ),
        ).join()

        assertTrue(timelineRepository.externalLocals.isEmpty())
        assertEquals("Local runtime does not support image attachments yet", uiState.value.error)
    }

    private fun coordinator(
        scope: CoroutineScope,
        timelineRepository: FakeTimelineExternalTransportWriter,
        uiState: MutableStateFlow<ChatUiState>,
        backend: LocalLettaBackend? = backend(),
        clearComposerAfterSend: () -> Unit = {},
        activeConversationId: () -> String? = { null },
        setActiveConversationId: (String) -> Unit = {},
        startTimelineObserver: (String) -> Unit = {},
    ): LocalRuntimeChatSendCoordinator = LocalRuntimeChatSendCoordinator(
        scope = scope,
        agentId = "agent-1",
        localBackend = { backend },
        timelineRepository = timelineRepository,
        uiState = uiState,
        clearComposerAfterSend = clearComposerAfterSend,
        activeConversationId = activeConversationId,
        setActiveConversationId = setActiveConversationId,
        startTimelineObserver = startTimelineObserver,
    )

    private fun backend(
        engine: TurnEngine = TurnEngine { flowOf() },
    ): LocalLettaBackend = LocalLettaBackend(
        descriptor = BackendDescriptor(
            backendId = BackendId("local-lettacode:test"),
            runtimeId = RuntimeId("local-lettacode:test"),
            kind = BackendKind.LocalLettaCode,
            label = "Local LettaCode",
            capabilities = BackendCapabilities(
                supportsStreaming = true,
                supportsMemFs = true,
                supportsTools = true,
                supportsApprovals = true,
                supportsAgentFileImport = true,
                supportsAgentFileExport = true,
            ),
        ),
        engine = engine,
        outbox = InMemoryRuntimeEventOutbox(
            eventIdFactory = { _, offset -> RuntimeEventId("event-${offset.value}") },
            clock = { EpochMillis(2_000) },
        ),
        memFsStore = InMemoryMemFsStore(
            commitIdFactory = { path, revision, operation ->
                MemFsCommitId("${operation.name.lowercase()}-${path.value}-${revision.value}")
            },
            clock = { EpochMillis(1_000) },
        ),
    )
}
