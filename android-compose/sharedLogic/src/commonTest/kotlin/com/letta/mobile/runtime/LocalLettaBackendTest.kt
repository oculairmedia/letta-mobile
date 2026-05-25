package com.letta.mobile.runtime

import com.letta.mobile.data.model.AgentId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest

class LocalLettaBackendTest {
    @Test
    fun runTurnScopesEngineDraftsAndAppendsThroughOutbox() = runTest {
        val backend = backend(
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
            },
        )

        val emitted = backend.runTurn(command()).toList()

        assertEquals(4, emitted.size)
        assertEquals(listOf(1L, 2L, 3L, 4L), emitted.map { it.offset.value })
        assertEquals(BackendId("local-koog:test"), emitted[2].backendId)
        assertEquals(RuntimeId("local-koog:test"), emitted[2].runtimeId)
        assertEquals(AgentId("agent-1"), emitted[2].agentId)
        assertEquals(ConversationId("conv-1"), emitted[2].conversationId)

        val localAppend = assertIs<RuntimeEventPayload.LocalUserAppend>(emitted[0].payload)
        assertEquals("local-1", localAppend.localMessageId)
        assertEquals("hello", localAppend.text)

        val started = assertIs<RuntimeEventPayload.RunLifecycleChanged>(emitted[1].payload)
        assertEquals(RuntimeRunStatus.Started, started.status)

        val frame = assertIs<RuntimeEventPayload.RemoteStreamFrame>(emitted[2].payload)
        assertEquals("Hello from local runtime", frame.body)

        val replay = backend.events(RuntimeEventOffset(0)).take(4).toList()
        assertEquals(emitted, replay)
    }

    @Test
    fun initializeMemFsWritesStoreAndOutboxCommitEvents() = runTest {
        val memFs = memFsStore()
        val backend = backend(memFsStore = memFs)

        val events = backend.initializeMemFs(
            agentId = AgentId("agent-1"),
            writes = listOf(
                MemFsWriteCommand(
                    path = MemFsPath("/memory/core.md"),
                    content = "name: local-agent",
                ),
            ),
        )

        assertEquals("name: local-agent", memFs.read(MemFsPath("/memory/core.md"))?.content)
        val payload = assertIs<RuntimeEventPayload.MemFsCommitObserved>(events.single().payload)
        assertEquals(MemFsRevision(1), payload.commit.revision)
        assertEquals(MemFsPath("/memory/core.md"), payload.commit.path)
    }

    private fun backend(
        engine: TurnEngine = TurnEngine { flowOf() },
        memFsStore: MemFsStore = memFsStore(),
    ): LocalLettaBackend = LocalLettaBackend(
        descriptor = BackendDescriptor(
            backendId = BackendId("local-koog:test"),
            runtimeId = RuntimeId("local-koog:test"),
            kind = BackendKind.LocalKoog,
            label = "Local Koog",
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
        memFsStore = memFsStore,
    )

    private fun command(): TurnCommand = TurnCommand(
        backendId = BackendId("local-koog:test"),
        runtimeId = RuntimeId("local-koog:test"),
        agentId = AgentId("agent-1"),
        conversationId = ConversationId("conv-1"),
        input = TurnInput.UserMessage(
            localMessageId = "local-1",
            text = "hello",
        ),
    )

    private fun memFsStore(): InMemoryMemFsStore = InMemoryMemFsStore(
        commitIdFactory = { path, revision, operation ->
            MemFsCommitId("${operation.name.lowercase()}-${path.value}-${revision.value}")
        },
        clock = { EpochMillis(1_000) },
    )
}
