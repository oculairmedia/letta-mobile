package com.letta.mobile.data.session

import com.letta.mobile.data.repository.api.IAgentBlockRepository
import com.letta.mobile.data.repository.api.IAgentRepository
import com.letta.mobile.data.repository.api.IArchiveRepository
import com.letta.mobile.data.repository.api.IConversationRepository
import com.letta.mobile.data.repository.api.ICronRepository
import com.letta.mobile.data.repository.api.IFolderRepository
import com.letta.mobile.data.repository.api.IGroupRepository
import com.letta.mobile.data.repository.api.IIdentityRepository
import com.letta.mobile.data.repository.api.IJobRepository
import com.letta.mobile.data.repository.api.IMcpServerRepository
import com.letta.mobile.data.repository.api.IModelRepository
import com.letta.mobile.data.repository.api.IPassageRepository
import com.letta.mobile.data.repository.api.IProjectRepository
import com.letta.mobile.data.repository.api.IProjectWorkRepository
import com.letta.mobile.data.repository.api.IProviderRepository
import com.letta.mobile.data.repository.api.IRunRepository
import com.letta.mobile.data.repository.api.IScheduleRepository
import com.letta.mobile.data.repository.api.ISelfTodoRepository
import com.letta.mobile.data.repository.api.IStepRepository
import com.letta.mobile.data.repository.api.ISubagentRepository
import com.letta.mobile.data.repository.api.IToolRepository
import com.letta.mobile.data.repository.api.IVibesyncEventStreamRepository
import com.letta.mobile.data.transport.api.IChannelTransport
import com.letta.mobile.data.transport.api.NoOpChannelTransport
import com.letta.mobile.runtime.BackendCapabilities
import com.letta.mobile.runtime.BackendDescriptor
import com.letta.mobile.runtime.BackendId
import com.letta.mobile.runtime.BackendKind
import com.letta.mobile.runtime.LettaBackend
import com.letta.mobile.runtime.RuntimeId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest

class DefaultSessionRepositoryGraphProviderTest {

    @Test
    fun rebuildClosesPreviousGraphAndPublishesNext() {
        val factory = CountingStubGraphFactory()
        val provider = DefaultSessionRepositoryGraphProvider(factory)
        val first = provider.current

        val second = provider.rebuild()

        assertTrue(first.closed)
        assertFalse(second.closed)
        assertEquals(2L, second.id)
        assertEquals(second, provider.currentGraph.value)
        assertNull(provider.sessionError.value)
        assertEquals(2, factory.createCount)
    }

    @Test
    fun rebuildSurfacesCreateFailureOnSessionErrorWithoutClosingCurrent() {
        val factory = CountingStubGraphFactory(failOnCreate = 2)
        val provider = DefaultSessionRepositoryGraphProvider(factory)
        val first = provider.current

        val error = assertFailsWith<IllegalStateException> { provider.rebuild() }

        assertEquals("create failed", error.message)
        assertEquals(error, provider.sessionError.value)
        assertFalse(first.closed)
        assertEquals(first, provider.current)
    }

    @Test
    fun withCurrentSessionCancelsWhenGraphSwitched() = runTest {
        val provider = DefaultSessionRepositoryGraphProvider(
            factory = CountingStubGraphFactory(),
            sessionSwitchMessage = "session switched",
        )
        val held = provider.current

        val error = assertFailsWith<CancellationException> {
            provider.withCurrentSession { graph ->
                assertEquals(held, graph)
                provider.rebuild()
                "ok"
            }
        }

        assertEquals("session switched", error.message)
    }
}

private class CountingStubGraphFactory(
    private val failOnCreate: Int? = null,
) : SessionRepositoryGraphFactory<StubSessionGraph> {
    var createCount: Int = 0
        private set

    override fun create(): StubSessionGraph {
        createCount += 1
        if (failOnCreate != null && createCount == failOnCreate) {
            error("create failed")
        }
        return StubSessionGraph(id = createCount.toLong())
    }
}

private class StubSessionGraph(
    override val id: Long,
) : SessionRepositoryGraph {
    var closed: Boolean = false
        private set

    override val backendDescriptor: BackendDescriptor = BackendDescriptor(
        backendId = BackendId("stub:$id"),
        runtimeId = RuntimeId("stub:$id"),
        kind = BackendKind.RemoteLetta,
        label = "stub",
        capabilities = BackendCapabilities(
            supportsStreaming = false,
            supportsMemFs = false,
            supportsToolEvents = false,
            supportsToolExecution = false,
            supportsApprovals = false,
            supportsAgentFileImport = false,
            supportsAgentFileExport = false,
        ),
    )
    override val localRuntimeBackend: LettaBackend? = null
    override val channelTransport: IChannelTransport = NoOpChannelTransport()

    override val agentRepository: IAgentRepository get() = unused()
    override val conversationRepository: IConversationRepository get() = unused()
    override val cronRepository: ICronRepository get() = unused()
    override val archiveRepository: IArchiveRepository get() = unused()
    override val folderRepository: IFolderRepository get() = unused()
    override val groupRepository: IGroupRepository get() = unused()
    override val identityRepository: IIdentityRepository get() = unused()
    override val mcpServerRepository: IMcpServerRepository get() = unused()
    override val modelRepository: IModelRepository get() = unused()
    override val passageRepository: IPassageRepository get() = unused()
    override val projectRepository: IProjectRepository get() = unused()
    override val projectWorkRepository: IProjectWorkRepository get() = unused()
    override val runRepository: IRunRepository get() = unused()
    override val jobRepository: IJobRepository get() = unused()
    override val providerRepository: IProviderRepository get() = unused()
    override val scheduleRepository: IScheduleRepository get() = unused()
    override val selfTodoRepository: ISelfTodoRepository get() = unused()
    override val stepRepository: IStepRepository get() = unused()
    override val subagentRepository: ISubagentRepository get() = unused()
    override val toolRepository: IToolRepository get() = unused()
    override val vibesyncEventStreamRepository: IVibesyncEventStreamRepository get() = unused()
    override val blockRepository: IAgentBlockRepository? get() = null

    override fun close() {
        closed = true
    }

    private fun unused(): Nothing = error("stub session graph does not bind repositories")
}
