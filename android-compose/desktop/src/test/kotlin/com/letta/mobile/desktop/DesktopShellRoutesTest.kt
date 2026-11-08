package com.letta.mobile.desktop

import androidx.compose.runtime.mutableStateOf
import com.letta.mobile.data.chat.runtime.ChatGatewayExtras
import com.letta.mobile.data.model.Agent
import com.letta.mobile.data.model.AgentCreateParams
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.Conversation
import com.letta.mobile.data.model.ConversationId
import com.letta.mobile.data.model.LlmModel
import com.letta.mobile.data.model.LettaMessage
import com.letta.mobile.data.model.MessageCreateRequest
import com.letta.mobile.data.timeline.TimelineStreamFrame
import com.letta.mobile.data.shell.CurrentFocus
import com.letta.mobile.data.shell.CurrentFocusChatMode
import com.letta.mobile.data.shell.ShellWorkspaceNavigator
import com.letta.mobile.desktop.chat.DesktopChatController
import com.letta.mobile.desktop.chat.DesktopChatConnectionState
import com.letta.mobile.desktop.chat.initialLiveDesktopChatSurfaceState
import com.letta.mobile.desktop.chat.projectWorkspace
import com.letta.mobile.desktop.chat.FakeDesktopChatGateway
import com.letta.mobile.desktop.chat.TEST_ROSTER
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import com.letta.mobile.data.shell.ShellDestination
import com.letta.mobile.data.shell.ShellSettingsSection
import com.letta.mobile.data.shell.ShellSkillsSection
import com.letta.mobile.data.shell.ShellWorkspaceState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * letta-mobile-ckmub.3.4 / 17.3: the desktop's destinations are a view of the shared workspace's page;
 * the state itself is covered by the shared ShellWorkspaceStateTest.
 */
class DesktopShellRoutesTest {
    private val destinations: List<ShellDestination> = listOf(
        ShellDestination.Home,
        ShellDestination.Chat(agentId = "agent-1", conversationId = "conv-1"),
        ShellDestination.Canvas(canvasId = "board-1"),
        ShellDestination.Memory("agent-1"),
        ShellDestination.Schedules("agent-1"),
        ShellDestination.Channels,
        ShellDestination.Skills(section = ShellSkillsSection.Tools),
        ShellDestination.Settings(ShellSettingsSection.Pairing),
        ShellDestination.AgentEditor("agent-1"),
        ShellDestination.Providers,
        ShellDestination.Overview,
        ShellDestination.Runs,
        ShellDestination.Templates,
        ShellDestination.Projects,
        ShellDestination.Plugins,
    )

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun stagingANewAgentlessChatClearsThePreviousConversation() = runTest {
        val gateway = FakeDesktopChatGateway()
        val controller = DesktopChatController(
            defaultDesktopBootstrapState(), this, rosterProvider = TEST_ROSTER, gatewayFactory = { gateway },
        )
        controller.start()
        runCurrent()
        val workspace = ShellWorkspaceState()
        workspace.bindSource("test-source", 1)
        workspace.selectFocus(CurrentFocus("chat", AgentId("agent-0"), ConversationId("conv-1"), CurrentFocusChatMode.Exact))
        val port = DesktopShellChatPort(controller, mutableStateOf(controller.state.value), workspace)
        port.selectConversation(ConversationId("conv-1"))
        runCurrent()
        controller.updateComposerText("old draft")
        workspace.selectFocus(CurrentFocus("chat", chatMode = CurrentFocusChatMode.New))

        port.stageComposerText("new starter")

        assertNull(controller.state.value.selectedConversationId)
        assertEquals("new starter", controller.state.value.composerText)
        controller.close()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun anUnlistedConversationIsResolvedAndLoadedByItsExactTarget() = runTest {
        val resolved = Conversation(ConversationId("hidden-chat"), AgentId("agent-1"), summary = "Hidden chat")
        val lookedUp = mutableListOf<String>()
        val gateway = object : FakeDesktopChatGateway() {
            override suspend fun getConversation(conversationId: String): Conversation {
                lookedUp += conversationId
                require(conversationId == resolved.id.value)
                return resolved
            }
            override suspend fun listConversationMessages(
                conversationId: ConversationId, limit: Int?, after: String?, order: String?,
            ): List<LettaMessage> {
                require(conversationId == resolved.id)
                return super.listConversationMessages(conversationId, limit, after, order)
            }
            override suspend fun streamConversation(conversationId: ConversationId): Flow<TimelineStreamFrame> {
                require(conversationId == resolved.id)
                return super.streamConversation(conversationId)
            }
        }
        val controller = DesktopChatController(
            defaultDesktopBootstrapState(), this, rosterProvider = TEST_ROSTER, gatewayFactory = { gateway },
        )
        controller.start()
        runCurrent()
        val workspace = ShellWorkspaceState()
        workspace.bindSource("test-source", 1)
        workspace.selectFocus(CurrentFocus("chat", conversationId = resolved.id, chatMode = CurrentFocusChatMode.Exact))
        val port = DesktopShellChatPort(controller, mutableStateOf(controller.state.value), workspace)

        port.selectConversation(resolved.id)
        runCurrent()

        assertEquals(listOf(resolved.id.value), lookedUp)
        assertEquals(resolved.agentId, workspace.currentFocus.agentId)
        assertEquals(resolved.id.value, controller.state.value.selectedConversationId)
        assertEquals(resolved.id.value, gateway.conversationMessageRequests.last())
        assertEquals(resolved.id.value, controller.state.value.selectedConversation?.id)
        controller.close()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun aLateUnlistedLookupCannotReplaceTheNewRailTarget() = runTest {
        val gate = CompletableDeferred<Unit>()
        val resolved = Conversation(ConversationId("hidden-chat"), AgentId("agent-1"), summary = "Hidden chat")
        val lookedUp = mutableListOf<String>()
        val gateway = object : FakeDesktopChatGateway() {
            override suspend fun getConversation(conversationId: String): Conversation {
                lookedUp += conversationId
                require(conversationId == resolved.id.value)
                gate.await()
                return resolved
            }
            override suspend fun listConversationMessages(
                conversationId: ConversationId, limit: Int?, after: String?, order: String?,
            ): List<LettaMessage> {
                require(conversationId.value == "conv-1" || conversationId == resolved.id)
                return super.listConversationMessages(conversationId, limit, after, order)
            }
            override suspend fun streamConversation(conversationId: ConversationId): Flow<TimelineStreamFrame> {
                require(conversationId.value == "conv-1" || conversationId == resolved.id)
                return super.streamConversation(conversationId)
            }
        }
        val controller = DesktopChatController(
            defaultDesktopBootstrapState(), this, rosterProvider = TEST_ROSTER, gatewayFactory = { gateway },
        )
        controller.start()
        runCurrent()
        val workspace = ShellWorkspaceState()
        workspace.bindSource("test-source", 1)
        workspace.selectFocus(CurrentFocus("chat", AgentId("agent-1"), resolved.id, CurrentFocusChatMode.Exact))
        val port = DesktopShellChatPort(controller, mutableStateOf(controller.state.value), workspace)
        port.selectConversation(resolved.id)
        runCurrent()
        assertEquals(listOf(resolved.id.value), lookedUp)

        workspace.selectFocus(CurrentFocus("chat", AgentId("agent-2")))
        gate.complete(Unit)
        runCurrent()

        assertEquals(AgentId("agent-2"), workspace.currentFocus.agentId)
        assertNull(workspace.currentFocus.conversationId)
        assertFalse(controller.state.value.conversations.any { it.id == resolved.id.value })
        assertFalse(gateway.conversationMessageRequests.contains(resolved.id.value))
        controller.close()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun anUnlistedLookupTransportFailureDoesNotMeanDeleted() = runTest {
        val lookedUp = mutableListOf<String>()
        val gateway = object : FakeDesktopChatGateway() {
            override suspend fun getConversation(conversationId: String): Conversation {
                lookedUp += conversationId
                require(conversationId == "hidden-chat")
                throw com.letta.mobile.data.timeline.TimelineTransportHttpException(502, "backend unavailable")
            }
        }
        val controller = DesktopChatController(
            defaultDesktopBootstrapState(), this, rosterProvider = TEST_ROSTER, gatewayFactory = { gateway },
        )
        controller.start()
        runCurrent()
        val workspace = ShellWorkspaceState()
        workspace.bindSource("test-source", 1)
        workspace.selectFocus(CurrentFocus("chat", AgentId("agent-1"), ConversationId("hidden-chat"), CurrentFocusChatMode.Exact))
        val port = DesktopShellChatPort(controller, mutableStateOf(controller.state.value), workspace)

        port.selectConversation(ConversationId("hidden-chat"))
        runCurrent()

        assertEquals(listOf("hidden-chat"), lookedUp)
        assertEquals(ConversationId("hidden-chat"), workspace.currentFocus.conversationId)
        assertEquals(com.letta.mobile.data.shell.FocusAvailability.Unknown, workspace.conversationAvailability)
        assertEquals("Could not load this conversation. Try again.", controller.state.value.errorMessage)
        assertFalse(controller.state.value.canSend)
        val session = com.letta.mobile.desktop.chat.DesktopChatSessionPort(controller, backgroundScope)
        assertEquals("Could not load this conversation. Try again.", session.uiState.value.error)
        assertFalse(session.uiState.value.isLoadingMessages)
        workspace.selectFocus(CurrentFocus("chat", AgentId("agent-2"), ConversationId("hidden-chat"), CurrentFocusChatMode.Exact))
        assertNull(session.uiState.value.error)
        assertTrue(session.uiState.value.isLoadingMessages)
        controller.close()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun anUnlistedDefaultAliasDoesNotUseAnAgentlessLookup() = runTest {
        val lookedUp = mutableListOf<String>()
        val gateway = object : FakeDesktopChatGateway() {
            override suspend fun getConversation(conversationId: String): Conversation {
                lookedUp += conversationId
                error("A default alias requires an agent-scoped lookup")
            }
        }
        val controller = DesktopChatController(
            defaultDesktopBootstrapState(), this, rosterProvider = TEST_ROSTER, gatewayFactory = { gateway },
        )
        controller.start()
        runCurrent()
        val workspace = ShellWorkspaceState()
        workspace.bindSource("test-source", 1)
        val alias = ConversationId(com.letta.mobile.data.model.IdFormats.DEFAULT_CONVERSATION_ID)
        workspace.selectFocus(CurrentFocus("chat", AgentId("agent-1"), alias, CurrentFocusChatMode.Exact))
        val port = DesktopShellChatPort(controller, mutableStateOf(controller.state.value), workspace)

        port.selectConversation(alias)
        runCurrent()

        assertEquals(emptyList(), lookedUp)
        assertEquals(alias, workspace.currentFocus.conversationId)
        assertEquals(AgentId("agent-1"), workspace.currentFocus.agentId)
        assertFalse(controller.state.value.canSend)
        controller.close()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun anExplicitNotFoundKeepsTheExactTargetUnavailable() = runTest {
        val lookedUp = mutableListOf<String>()
        val gateway = object : FakeDesktopChatGateway() {
            override suspend fun getConversation(conversationId: String): Conversation {
                lookedUp += conversationId
                require(conversationId == "deleted-chat")
                throw com.letta.mobile.data.timeline.TimelineTransportHttpException(404, "not found")
            }
        }
        val controller = DesktopChatController(
            defaultDesktopBootstrapState(), this, rosterProvider = TEST_ROSTER, gatewayFactory = { gateway },
        )
        controller.start()
        runCurrent()
        val workspace = ShellWorkspaceState()
        workspace.bindSource("test-source", 1)
        val target = ConversationId("deleted-chat")
        workspace.selectFocus(CurrentFocus("chat", AgentId("agent-1"), target, CurrentFocusChatMode.Exact))
        val port = DesktopShellChatPort(controller, mutableStateOf(controller.state.value), workspace)

        port.selectConversation(target)
        runCurrent()

        assertEquals(listOf(target.value), lookedUp)
        assertEquals(target, workspace.currentFocus.conversationId)
        assertEquals(com.letta.mobile.data.shell.FocusAvailability.Unavailable, workspace.conversationAvailability)
        assertEquals(
            "This conversation is unavailable.",
            controller.state.value.projectWorkspace(workspace, controller.isTargetCurrent()).errorMessage,
        )
        assertFalse(controller.state.value.canSend)
        controller.close()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun anUnlistedLookupCannotReplaceTheRequestedAgent() = runTest {
        val lookedUp = mutableListOf<String>()
        val gateway = object : FakeDesktopChatGateway() {
            override suspend fun getConversation(conversationId: String): Conversation {
                lookedUp += conversationId
                require(conversationId == "hidden-chat")
                return Conversation(ConversationId(conversationId), AgentId("agent-1"))
            }
        }
        val controller = DesktopChatController(
            defaultDesktopBootstrapState(), this, rosterProvider = TEST_ROSTER, gatewayFactory = { gateway },
        )
        controller.start()
        runCurrent()
        val workspace = ShellWorkspaceState()
        workspace.bindSource("test-source", 1)
        val target = ConversationId("hidden-chat")
        workspace.selectFocus(CurrentFocus("chat", AgentId("agent-2"), target, CurrentFocusChatMode.Exact))
        val port = DesktopShellChatPort(controller, mutableStateOf(controller.state.value), workspace)

        port.selectConversation(target)
        runCurrent()

        assertEquals(listOf(target.value), lookedUp)
        assertEquals(AgentId("agent-2"), workspace.currentFocus.agentId)
        assertEquals(target, workspace.currentFocus.conversationId)
        assertFalse(controller.state.value.conversations.any { it.id == target.value })
        assertFalse(gateway.conversationMessageRequests.contains(target.value))
        assertFalse(controller.state.value.canSend)
        controller.close()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun anUnlistedLookupDoesNotStartAfterTheOwnerChanges() = runTest {
        val lookedUp = mutableListOf<String>()
        val gateway = object : FakeDesktopChatGateway() {
            override suspend fun getConversation(conversationId: String): Conversation {
                lookedUp += conversationId
                require(conversationId == "hidden-chat")
                return Conversation(ConversationId(conversationId), AgentId("agent-1"))
            }
        }
        val controller = DesktopChatController(
            defaultDesktopBootstrapState(), this, rosterProvider = TEST_ROSTER, gatewayFactory = { gateway },
        )
        controller.start()
        runCurrent()
        val workspace = ShellWorkspaceState()
        workspace.bindSource("test-source", 1)
        workspace.selectFocus(CurrentFocus("chat", AgentId("agent-1"), ConversationId("hidden-chat"), CurrentFocusChatMode.Exact))
        val port = DesktopShellChatPort(controller, mutableStateOf(controller.state.value), workspace)

        port.selectConversation(ConversationId("hidden-chat"))
        workspace.selectFocus(CurrentFocus("chat", AgentId("agent-2")))
        runCurrent()

        assertEquals(emptyList(), lookedUp)
        assertEquals(AgentId("agent-2"), workspace.currentFocus.agentId)
        controller.close()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun lateCreateCannotLoadIntoAConversationAfterRailSwitch() = runTest {
        val createGate = CompletableDeferred<Unit>()
        var created: Conversation? = null
        var createCount = 0
        val gateway = object : FakeDesktopChatGateway(conversationIds = listOf("conv-1", "conv-2")), ChatGatewayExtras {
            override suspend fun createConversation(agentId: String, summary: String?): Conversation {
                require(agentId == "agent-0")
                createGate.await()
                createCount++
                return Conversation(ConversationId("created-$createCount"), AgentId(agentId)).also { created = it }
            }
            override suspend fun listConversations(limit: Int, archiveStatus: String?): List<Conversation> =
                super.listConversations(limit, archiveStatus) + listOfNotNull(created)
            override suspend fun deleteConversation(conversationId: String) {
                require(created?.id?.value == conversationId)
                created = null
            }
            override suspend fun listConversationMessages(
                conversationId: ConversationId, limit: Int?, after: String?, order: String?,
            ): List<LettaMessage> {
                require(listConversations().any { it.id == conversationId })
                return super.listConversationMessages(conversationId, limit, after, order)
            }
            override suspend fun streamConversation(conversationId: ConversationId): Flow<TimelineStreamFrame> {
                require(listConversations().any { it.id == conversationId })
                return super.streamConversation(conversationId)
            }
            override suspend fun sendConversationMessage(
                conversationId: ConversationId, request: MessageCreateRequest,
            ): Flow<LettaMessage> {
                require(listConversations().any { it.id == conversationId })
                return super.sendConversationMessage(conversationId, request)
            }
            override suspend fun createAgent(params: AgentCreateParams): Agent = error("not used")
            override suspend fun listLlmModels(): List<LlmModel> = emptyList()
            override suspend fun setConversationModel(conversationId: String, model: String): Conversation = error("not used")
            override suspend fun setConversationArchived(conversationId: String, archived: Boolean): Conversation = error("not used")
        }
        val controller = DesktopChatController(
            defaultDesktopBootstrapState(), this, rosterProvider = TEST_ROSTER, gatewayFactory = { gateway },
        )
        controller.start()
        runCurrent()
        val workspace = ShellWorkspaceState()
        workspace.bindSource("test-source", 1)
        val port = DesktopShellChatPort(controller, mutableStateOf(controller.state.value), workspace)
        val router = ShellWorkspaceNavigator(workspace, port)
        workspace.selectFocus(CurrentFocus("chat", AgentId("agent-0"), chatMode = CurrentFocusChatMode.New))
        val token = workspace.requestToken()
        port.createConversationForAgent(AgentId("agent-0")) { id ->
            if (workspace.completeFocus(token, id, AgentId("agent-0"))) port.selectConversation(id)
        }
        runCurrent()
        router.openAgent(AgentId("agent-1"))
        runCurrent()
        createGate.complete(Unit)
        runCurrent()
        assertEquals(AgentId("agent-1"), workspace.currentFocus.agentId)
        assertEquals(ConversationId("conv-2"), workspace.currentFocus.conversationId)
        assertEquals("conv-2", controller.state.value.selectedConversationId)
        assertFalse("created-1" in gateway.conversationMessageRequests)

        workspace.selectFocus(CurrentFocus("chat", AgentId("agent-0"), chatMode = CurrentFocusChatMode.New))
        val nextToken = workspace.requestToken()
        port.createConversationForAgent(AgentId("agent-0")) { id ->
            if (workspace.completeFocus(nextToken, id, AgentId("agent-0"))) port.selectConversation(id)
        }
        runCurrent()
        assertEquals(ConversationId("created-2"), workspace.currentFocus.conversationId)
        assertEquals("created-2", controller.state.value.selectedConversationId)
        assertEquals("created-2", gateway.conversationMessageRequests.last())
        controller.close()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun lateCreateFailureDoesNotOverwriteTheNewFocus() = runTest {
        val createGate = CompletableDeferred<Unit>()
        val gateway = object : FakeDesktopChatGateway(conversationIds = listOf("conv-1", "conv-2")), ChatGatewayExtras {
            override suspend fun createConversation(agentId: String, summary: String?): Conversation {
                require(agentId == "agent-0")
                createGate.await()
                error("stale create failed")
            }
            override suspend fun listConversationMessages(
                conversationId: ConversationId, limit: Int?, after: String?, order: String?,
            ): List<LettaMessage> {
                require(listConversations().any { it.id == conversationId })
                return super.listConversationMessages(conversationId, limit, after, order)
            }
            override suspend fun streamConversation(conversationId: ConversationId): Flow<TimelineStreamFrame> {
                require(listConversations().any { it.id == conversationId })
                return super.streamConversation(conversationId)
            }
            override suspend fun sendConversationMessage(
                conversationId: ConversationId, request: MessageCreateRequest,
            ): Flow<LettaMessage> {
                require(listConversations().any { it.id == conversationId })
                return super.sendConversationMessage(conversationId, request)
            }
            override suspend fun createAgent(params: AgentCreateParams): Agent = error("not used")
            override suspend fun listLlmModels(): List<LlmModel> = emptyList()
            override suspend fun setConversationModel(conversationId: String, model: String): Conversation = error("not used")
            override suspend fun setConversationArchived(conversationId: String, archived: Boolean): Conversation = error("not used")
        }
        val controller = DesktopChatController(
            defaultDesktopBootstrapState(), this, rosterProvider = TEST_ROSTER, gatewayFactory = { gateway },
        )
        controller.start()
        runCurrent()
        val workspace = ShellWorkspaceState()
        workspace.bindSource("test-source", 1)
        val port = DesktopShellChatPort(controller, mutableStateOf(controller.state.value), workspace)
        val router = ShellWorkspaceNavigator(workspace, port)
        workspace.selectFocus(CurrentFocus("chat", AgentId("agent-0"), chatMode = CurrentFocusChatMode.New))
        val token = workspace.requestToken()
        port.createConversationForAgent(AgentId("agent-0")) { id ->
            if (workspace.completeFocus(token, id, AgentId("agent-0"))) port.selectConversation(id)
        }
        runCurrent()
        router.openAgent(AgentId("agent-1"))
        runCurrent()
        createGate.complete(Unit)
        runCurrent()

        assertEquals(AgentId("agent-1"), workspace.currentFocus.agentId)
        assertEquals(ConversationId("conv-2"), workspace.currentFocus.conversationId)
        assertNull(controller.state.value.errorMessage)
        controller.close()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun queuedReplyDoesNotSendAfterOwnerSwitchesWithoutAControllerSelection() = runTest {
        val gateway = object : FakeDesktopChatGateway(conversationIds = listOf("conv-1")) {
            override suspend fun sendConversationMessage(
                conversationId: ConversationId, request: MessageCreateRequest,
            ): Flow<LettaMessage> {
                require(conversationId == ConversationId("conv-1"))
                return super.sendConversationMessage(conversationId, request)
            }
        }
        val controller = DesktopChatController(
            defaultDesktopBootstrapState(), this, rosterProvider = TEST_ROSTER, gatewayFactory = { gateway },
        )
        controller.start()
        runCurrent()
        controller.selectConversation("conv-1")
        runCurrent()
        val workspace = ShellWorkspaceState()
        workspace.bindSource("test-source", 1)
        workspace.selectFocus(CurrentFocus("chat", AgentId("agent-0"), ConversationId("conv-1"), CurrentFocusChatMode.Exact))
        val port = DesktopShellChatPort(controller, mutableStateOf(controller.state.value), workspace)

        port.send(ConversationId("conv-1"), "queued reply")
        workspace.selectFocus(CurrentFocus("chat", AgentId("agent-1")))
        runCurrent()

        assertEquals(AgentId("agent-1"), workspace.currentFocus.agentId)
        assertEquals("conv-1", controller.state.value.selectedConversationId)
        assertEquals(emptyList(), gateway.sentRequests)
        controller.close()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun composerDoesNotSendAfterOwnerSwitchesWithoutControllerReselection() = runTest {
        val gateway = FakeDesktopChatGateway()
        val controller = DesktopChatController(
            defaultDesktopBootstrapState(), this, rosterProvider = TEST_ROSTER, gatewayFactory = { gateway },
        )
        controller.start()
        runCurrent()
        controller.selectConversation("conv-1")
        runCurrent()
        val workspace = ShellWorkspaceState()
        workspace.bindSource("test-source", 1)
        workspace.selectFocus(CurrentFocus("chat", AgentId("agent-0"), ConversationId("conv-1"), CurrentFocusChatMode.Exact))
        val port = DesktopShellChatPort(controller, mutableStateOf(controller.state.value), workspace)
        port.selectConversation(ConversationId("conv-1"))
        workspace.selectFocus(CurrentFocus("chat", AgentId("agent-1")))

        controller.updateComposerText("queued reply")
        controller.send()
        controller.sendText("second reply")
        runCurrent()

        assertEquals(AgentId("agent-1"), workspace.currentFocus.agentId)
        assertEquals(emptyList(), gateway.sentRequests)
        assertEquals("queued reply", controller.state.value.composerText)
        controller.close()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun deletingTheOpenChatReportsUnavailableToItsOwner() = runTest {
        val gateway = object : FakeDesktopChatGateway() {
            override suspend fun deleteConversation(conversationId: String) { require(conversationId == "conv-1") }
        }
        val controller = DesktopChatController(
            defaultDesktopBootstrapState(), this, rosterProvider = TEST_ROSTER, gatewayFactory = { gateway },
        )
        controller.start()
        runCurrent()
        val workspace = ShellWorkspaceState()
        workspace.bindSource("test-source", 1)
        workspace.selectFocus(CurrentFocus("chat", AgentId("agent-0"), ConversationId("conv-1"), CurrentFocusChatMode.Exact))
        workspace.completeFocus(workspace.requestToken(), ConversationId("conv-1"), AgentId("agent-0"))
        val port = DesktopShellChatPort(controller, mutableStateOf(controller.state.value), workspace)
        port.selectConversation(ConversationId("conv-1"))
        runCurrent()

        controller.deleteConversation("conv-1")
        runCurrent()

        assertEquals(ConversationId("conv-1"), workspace.currentFocus.conversationId)
        assertEquals(com.letta.mobile.data.shell.FocusAvailability.Unavailable, workspace.conversationAvailability)
        assertFalse(controller.state.value.canSend)
        assertEquals(
            "This conversation is unavailable.",
            controller.state.value.projectWorkspace(workspace, controller.isTargetCurrent()).errorMessage,
        )
        assertFalse(controller.state.value.projectWorkspace(workspace, controller.isTargetCurrent()).isLoading)
        controller.close()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun aLateDeleteDoesNotMarkTheNewOwnerUnavailable() = runTest {
        val gate = CompletableDeferred<Unit>()
        val gateway = object : FakeDesktopChatGateway() {
            override suspend fun deleteConversation(conversationId: String) {
                require(conversationId == "conv-1")
                gate.await()
            }
        }
        val controller = DesktopChatController(
            defaultDesktopBootstrapState(), this, rosterProvider = TEST_ROSTER, gatewayFactory = { gateway },
        )
        controller.start()
        runCurrent()
        val workspace = ShellWorkspaceState()
        workspace.bindSource("test-source", 1)
        workspace.selectFocus(CurrentFocus("chat", AgentId("agent-0"), ConversationId("conv-1"), CurrentFocusChatMode.Exact))
        val port = DesktopShellChatPort(controller, mutableStateOf(controller.state.value), workspace)
        port.selectConversation(ConversationId("conv-1"))
        runCurrent()
        controller.deleteConversation("conv-1")
        runCurrent()
        workspace.selectFocus(CurrentFocus("chat", AgentId("agent-1")))
        gate.complete(Unit)
        runCurrent()

        assertEquals(AgentId("agent-1"), workspace.currentFocus.agentId)
        assertEquals(com.letta.mobile.data.shell.FocusAvailability.Unknown, workspace.conversationAvailability)
        controller.close()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun openingNewChatClearsThePreviousConversationWhileCreationIsPending() = runTest {
        val gateway = FakeDesktopChatGateway()
        val controller = DesktopChatController(
            defaultDesktopBootstrapState(), this, rosterProvider = TEST_ROSTER, gatewayFactory = { gateway },
        )
        controller.start()
        runCurrent()
        val workspace = ShellWorkspaceState()
        workspace.bindSource("test-source", 1)
        workspace.selectFocus(CurrentFocus("chat", AgentId("agent-0"), ConversationId("conv-1"), CurrentFocusChatMode.Exact))
        val port = DesktopShellChatPort(controller, mutableStateOf(controller.state.value), workspace)
        port.selectConversation(ConversationId("conv-1"))
        runCurrent()
        val router = ShellWorkspaceNavigator(workspace, port)

        router.openNewChat(AgentId("agent-1"))
        runCurrent()

        assertEquals(CurrentFocusChatMode.New, workspace.currentFocus.chatMode)
        assertEquals(AgentId("agent-1"), workspace.currentFocus.agentId)
        assertNull(controller.state.value.selectedConversationId)
        assertEquals(emptyList(), controller.state.value.selectedMessages)
        assertFalse(controller.state.value.canSend)
        controller.close()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun anUnlistedExactTargetDoesNotKeepTheOldTimelineOrLoadAnotherChat() = runTest {
        val gateway = object : FakeDesktopChatGateway() {
            override suspend fun listConversationMessages(
                conversationId: ConversationId, limit: Int?, after: String?, order: String?,
            ): List<LettaMessage> {
                require(conversationId == ConversationId("conv-1"))
                return super.listConversationMessages(conversationId, limit, after, order)
            }
            override suspend fun streamConversation(conversationId: ConversationId): Flow<TimelineStreamFrame> {
                require(conversationId == ConversationId("conv-1"))
                return super.streamConversation(conversationId)
            }
        }
        val controller = DesktopChatController(
            defaultDesktopBootstrapState(), this, rosterProvider = TEST_ROSTER, gatewayFactory = { gateway },
        )
        controller.start()
        runCurrent()
        controller.selectConversation("conv-1")
        runCurrent()
        val workspace = ShellWorkspaceState()
        workspace.bindSource("test-source", 1)
        workspace.selectFocus(CurrentFocus("chat", conversationId = ConversationId("missing"), chatMode = CurrentFocusChatMode.Exact))
        val port = DesktopShellChatPort(controller, mutableStateOf(controller.state.value), workspace)

        port.selectConversation(ConversationId("missing"))
        runCurrent()

        assertEquals(ConversationId("missing"), workspace.currentFocus.conversationId)
        assertEquals("missing", controller.state.value.selectedConversationId)
        assertEquals(emptyList(), controller.state.value.selectedMessages)
        assertFalse(controller.state.value.canSend)
        assertEquals(listOf("conv-1"), gateway.conversationMessageRequests)
        controller.close()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun directControllerSelectionCannotSendAgainstAnotherOwnerTarget() = runTest {
        val gateway = FakeDesktopChatGateway()
        val controller = DesktopChatController(
            defaultDesktopBootstrapState(), this, rosterProvider = TEST_ROSTER, gatewayFactory = { gateway },
        )
        controller.start()
        runCurrent()
        val workspace = ShellWorkspaceState()
        workspace.bindSource("test-source", 1)
        workspace.selectFocus(CurrentFocus("chat", AgentId("agent-1")))
        DesktopShellChatPort(controller, mutableStateOf(controller.state.value), workspace)
        controller.selectConversation("conv-1")
        runCurrent()

        controller.updateComposerText("wrong target")
        controller.send()
        runCurrent()

        assertEquals(AgentId("agent-1"), workspace.currentFocus.agentId)
        assertEquals(emptyList(), gateway.sentRequests)
        assertEquals("wrong target", controller.state.value.composerText)
        controller.close()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun selectingTheSameChatAgainAfterAnOwnerSwitchAllowsSending() = runTest {
        val gateway = FakeDesktopChatGateway()
        val controller = DesktopChatController(
            defaultDesktopBootstrapState(), this, rosterProvider = TEST_ROSTER, gatewayFactory = { gateway },
        )
        controller.start()
        runCurrent()
        val workspace = ShellWorkspaceState()
        workspace.bindSource("test-source", 1)
        val port = DesktopShellChatPort(controller, mutableStateOf(controller.state.value), workspace)
        val target = CurrentFocus("chat", AgentId("agent-0"), ConversationId("conv-1"), CurrentFocusChatMode.Exact)
        workspace.selectFocus(target)
        port.selectConversation(ConversationId("conv-1"))
        runCurrent()
        controller.updateComposerText("draft to keep")
        workspace.selectFocus(CurrentFocus("chat", AgentId("agent-1")))
        workspace.selectFocus(target)
        port.selectConversation(ConversationId("conv-1"))
        runCurrent()
        assertEquals("draft to keep", controller.state.value.composerText)

        controller.updateComposerText("valid reply")
        controller.send()
        runCurrent()

        assertEquals(ConversationId("conv-1"), workspace.currentFocus.conversationId)
        assertEquals(1, gateway.sentRequests.size)
        controller.close()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun portReportsOwnerTargetWhileControllerStillShowsAnotherConversation() = runTest {
        val controller = DesktopChatController(defaultDesktopBootstrapState(), this, rosterProvider = TEST_ROSTER,
            gatewayFactory = { FakeDesktopChatGateway() })
        controller.start()
        runCurrent()
        controller.selectConversation("conv-1")
        runCurrent()
        val workspace = ShellWorkspaceState()
        workspace.bindSource("test-source", 1)
        workspace.selectFocus(CurrentFocus("chat", AgentId("agent-1"), ConversationId("missing"), CurrentFocusChatMode.Exact))
        val port = DesktopShellChatPort(controller, mutableStateOf(controller.state.value), workspace)

        assertEquals(ConversationId("missing"), port.selectedConversation)
        assertEquals("conv-1", controller.state.value.selectedConversationId)
        controller.close()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun lateCreateFailureDoesNotOverwriteAnOwnerOnlySwitch() = runTest {
        val gate = CompletableDeferred<Unit>()
        val gateway = object : FakeDesktopChatGateway(), ChatGatewayExtras {
            override suspend fun createConversation(agentId: String, summary: String?): Conversation {
                require(agentId == "agent-0")
                gate.await()
                error("stale create failed")
            }
            override suspend fun createAgent(params: AgentCreateParams): Agent = error("not used")
            override suspend fun listLlmModels(): List<LlmModel> = emptyList()
            override suspend fun setConversationModel(conversationId: String, model: String): Conversation = error("not used")
            override suspend fun setConversationArchived(conversationId: String, archived: Boolean): Conversation = error("not used")
        }
        val controller = DesktopChatController(
            defaultDesktopBootstrapState(), this, rosterProvider = TEST_ROSTER, gatewayFactory = { gateway },
        )
        controller.start()
        runCurrent()
        val workspace = ShellWorkspaceState()
        workspace.bindSource("test-source", 1)
        workspace.selectFocus(CurrentFocus("chat", AgentId("agent-0"), chatMode = CurrentFocusChatMode.New))
        val port = DesktopShellChatPort(controller, mutableStateOf(controller.state.value), workspace)
        port.createConversationForAgent(AgentId("agent-0")) {}
        runCurrent()
        workspace.selectFocus(CurrentFocus("chat", AgentId("agent-1")))
        gate.complete(Unit)
        runCurrent()

        assertEquals(AgentId("agent-1"), workspace.currentFocus.agentId)
        assertNull(controller.state.value.errorMessage)
        controller.close()
    }

    @Test
    fun aLiveCatalogDoesNotReportAnAuthoritativeGlobalConversationList() {
        val chat = initialLiveDesktopChatSurfaceState(defaultDesktopBootstrapState()).copy(
            connectionState = DesktopChatConnectionState.Live,
            isLoading = false,
        )

        assertNull(chat.shellChatSelection().reported)
    }

    @Test
    fun everyDesktopDestinationIsASharedPageAndBack() {
        for (desktop in DesktopDestination.entries) {
            assertEquals(desktop, desktop.shellPage.desktopDestination, "$desktop -> ${desktop.shellPage}")
        }
    }

    @Test
    fun everySharedDestinationShowsTheDesktopDestinationOfItsPage() {
        for (destination in destinations) {
            val workspace = ShellWorkspaceState()
            workspace.navigate(destination)
            assertEquals(destination.desktopDestination, workspace.selectedDestination, "$destination")
        }
        assertEquals(DesktopDestination.Conversations, ShellDestination.AgentEditor().desktopDestination)
    }

    @Test
    fun theRailsNavigationLeavesTheEditorAndResetsTheAnchors() {
        val workspace = ShellWorkspaceState()
        workspace.navigate(ShellDestination.Skills(section = ShellSkillsSection.Tools))
        workspace.focusedAgentId = AgentId("agent-1")
        assertEquals(DesktopDestination.Agents, workspace.selectedDestination)
        assertEquals(
            ShellDestination.Skills("agent-1", ShellSkillsSection.Tools),
            workspace.pageFor(AgentId("agent-1"), null),
        )
        workspace.navigate(ShellDestination.AgentEditor("agent-2"))
        assertEquals(AgentId("agent-2"), workspace.editAgentId)
        assertEquals(DesktopDestination.Conversations, workspace.selectedDestination)

        workspace.navigate(DesktopDestination.Agents)
        assertNull(workspace.editAgentId)
        assertEquals(ShellDestination.Skills(), workspace.page)
    }

    @Test
    fun selectingADestinationKeepsTheEditorBesideTheChat() {
        val workspace = ShellWorkspaceState()
        workspace.openNewAgent()
        workspace.selectedDestination = DesktopDestination.Conversations
        assertEquals(ShellDestination.AgentEditor(), workspace.agentEditor)
        workspace.selectedDestination = DesktopDestination.Home
        assertEquals(ShellDestination.Home, workspace.page)
    }
}
