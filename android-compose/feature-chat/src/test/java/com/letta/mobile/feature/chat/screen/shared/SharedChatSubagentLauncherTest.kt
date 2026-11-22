package com.letta.mobile.feature.chat.screen.shared

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.performClick
import com.letta.mobile.data.controller.node.iroh.AdminRpcHostServices
import com.letta.mobile.data.controller.node.iroh.AdminRpcInvocation
import com.letta.mobile.data.controller.node.iroh.AdminRpcPairing
import com.letta.mobile.data.controller.node.iroh.AdminRpcRegistry
import com.letta.mobile.data.controller.node.iroh.AdminRpcRequestContext
import com.letta.mobile.data.controller.node.iroh.AdminRpcRouter
import com.letta.mobile.data.controller.node.iroh.ControllerSubagentRegistrySource
import com.letta.mobile.data.controller.node.iroh.InMemoryPairedPeerStore
import com.letta.mobile.data.controller.node.iroh.IrohPairingService
import com.letta.mobile.data.model.SubagentEntry
import com.letta.mobile.data.model.SubagentStatus
import com.letta.mobile.data.model.SubagentTodo
import com.letta.mobile.data.repository.SubagentRepository
import com.letta.mobile.data.repository.api.SubagentParentScope
import com.letta.mobile.data.subagents.SubagentChipSource
import com.letta.mobile.data.transport.ChannelTransportState
import com.letta.mobile.data.transport.ServerFrame
import com.letta.mobile.data.transport.api.NoOpChannelTransport
import com.letta.mobile.data.transport.api.SubagentScopeAwareChannelTransport
import com.letta.mobile.feature.chat.screen.ChatScreenNavigationCallbacks
import com.letta.mobile.feature.chat.screen.rememberChatScreenSubagentBarState
import com.letta.mobile.feature.chat.subagent.SelfTodoSource
import com.letta.mobile.feature.chat.subagent.WsActiveSubagentSource
import com.letta.mobile.ui.chat.subagents.ActiveSubagent
import com.letta.mobile.ui.chat.subagents.ActiveSubagentRingsTags
import com.letta.mobile.ui.chat.subagents.BackgroundTasksTags
import com.letta.mobile.ui.theme.LettaChatTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.jupiter.api.Tag
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Subagents the agent dispatches show on the phone's chat page. The host's registry holds them
 * RUNNING (source CONTROLLER_NATIVE) without a TodoWrite plan, which is how a General-purpose
 * worker runs most of its life. The page drew only rings, and a ring needs a plan, so the phone
 * received the subagents and drew nothing. The page must show them and open the Background tasks
 * pane, as desktop's header toggle does.
 *
 * Real path end to end: the host's [ControllerSubagentRegistrySource] behind the real
 * `subagent.list` handler ([AdminRpcRegistry.buildRouter], with its scope and authorization
 * checks), the real [SubagentRepository], [WsActiveSubagentSource], the bar state and
 * [SharedChatSubagentRings]. The transport sends exactly the params the Iroh client
 * (IrohSubagentRpcClient.callScopedSubagentRpc) sends: conversation_id, agent_id, all.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
@Tag("integration")
class SharedChatSubagentLauncherTest {
    @get:Rule val compose = createComposeRule()

    private val owner = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val hostSource = ControllerSubagentRegistrySource()
    private val router = AdminRpcRegistry.buildRouter(
        services = AdminRpcHostServices(subagentRegistrySource = hostSource),
        pairing = AdminRpcPairing(pairingService = IrohPairingService(InMemoryPairedPeerStore())),
    )
    private val peer = AdminRpcRequestContext(
        authenticated = true,
        authorizedConversationIds = setOf(PARENT_CONVERSATION),
        authorizedAgentIds = setOf(PARENT_AGENT),
    )
    private val transport = RouterTransport(router, peer)
    private val repository = SubagentRepository(transport, owner)

    @After
    fun tearDown() {
        repository.close()
        owner.cancel()
    }

    @Test
    fun runningPlanlessSubagentsShowOnThePageAndOpenBackgroundTasks() {
        hostSource.ingestFromSource(PARENT_CONVERSATION, PARENT_AGENT, dispatches, SubagentChipSource.CONTROLLER_NATIVE)
        var opened = 0
        val navigation = ChatScreenNavigationCallbacks(onOpenBackgroundTasks = { opened++ })
        compose.setContent { LettaChatTheme { Page(navigation) } }

        compose.waitUntil(WAIT_MS) { repository.currentActiveSubagents(scope).size == dispatches.size }
        compose.waitForIdle()
        // No plan, so no ring: the cause, and why the launcher must stand in for it.
        compose.onNode(hasTestTag(ActiveSubagentRingsTags.RINGS)).assertDoesNotExist()

        val launcher = compose.onNode(
            hasTestTag(BackgroundTasksTags.TOGGLE) and hasContentDescription("Background tasks · 3 running"),
        )
        launcher.assertIsDisplayed()
        launcher.performClick()
        compose.runOnIdle { assertEquals(1, opened) }
    }

    @Test
    fun aConversationWithNothingRunningShowsNoLauncher() {
        val ended = dispatches.map { it.copy(status = SubagentStatus.COMPLETED) }
        hostSource.ingestFromSource(PARENT_CONVERSATION, PARENT_AGENT, ended, SubagentChipSource.CONTROLLER_NATIVE)
        compose.setContent { LettaChatTheme { Page(ChatScreenNavigationCallbacks(onOpenBackgroundTasks = {})) } }

        // The page's scoped fetch answered (the host lists no ended work without all=true).
        compose.waitUntil(WAIT_MS) { transport.answered > 0 }
        compose.waitForIdle()
        compose.onNode(hasTestTag(BackgroundTasksTags.TOGGLE)).assertDoesNotExist()
    }

    @Composable
    private fun Page(navigation: ChatScreenNavigationCallbacks) {
        val source = remember {
            WsActiveSubagentSource(repository, owner, PARENT_AGENT, MutableStateFlow(PARENT_CONVERSATION))
        }
        val bar = rememberChatScreenSubagentBarState(source, NoSelfPlan, PARENT_CONVERSATION)
        val inputs = SharedChatSubagentInputs(source = source, selfTodoSource = NoSelfPlan, barState = bar)
        SharedChatSubagentRings(rememberSharedChatSubagentSheetState(source), inputs, navigation)
    }

    /** The phone's Iroh transport, reduced to the scoped `subagent.list` call through the host's router. */
    private class RouterTransport(
        private val router: AdminRpcRouter,
        private val peer: AdminRpcRequestContext,
    ) : NoOpChannelTransport(), SubagentScopeAwareChannelTransport {
        override val state = MutableStateFlow<ChannelTransportState>(
            ChannelTransportState.Connected(serverId = "host", sessionId = "session", deviceId = "phone"),
        )

        /** Scoped lists the host answered successfully. */
        @Volatile var answered = 0

        override suspend fun sendSubagentListForScope(
            scope: SubagentParentScope,
            all: Boolean,
            timeoutMs: Long,
        ): ServerFrame.SubagentListResponse {
            val params = buildJsonObject {
                put("conversation_id", scope.parentConversationId)
                put("agent_id", scope.parentAgentId)
                put("all", all)
            }
            val envelope = json.parseToJsonElement(router.dispatch(AdminRpcInvocation("list", "subagent.list", params, peer))).jsonObject
            if (envelope.getValue("success").jsonPrimitive.content != "true") {
                return ServerFrame.SubagentListResponse(id = "list", ts = "t", success = false, error = envelope.toString())
            }
            val subagents: List<SubagentEntry> =
                json.decodeFromJsonElement(envelope.getValue("result").jsonObject.getValue("subagents"))
            answered++
            return ServerFrame.SubagentListResponse(id = "list", ts = "t", success = true, subagents = subagents)
        }

        private companion object {
            val json = Json { ignoreUnknownKeys = true }
        }
    }

    private object NoSelfPlan : SelfTodoSource {
        override fun selfEntry(conversationId: String) = flowOf<ActiveSubagent?>(null)
        override fun todos(conversationId: String) = emptyList<SubagentTodo>()
    }

    private companion object {
        const val PARENT_AGENT = "agent-c356b54a-8b37-4d53-b9d0-b43164749b6f"
        const val PARENT_CONVERSATION = "local-conv-95806527"
        const val WAIT_MS = 10_000L
        val scope = SubagentParentScope(PARENT_AGENT, PARENT_CONVERSATION)

        /** Shaped like the live registry's records: RUNNING, General-purpose, a task id, no plan. */
        val dispatches = listOf(60, 62, 64).map { task ->
            SubagentEntry(
                toolCallId = "toolu_$task",
                description = "Worker $task",
                subagentType = "General-purpose",
                status = SubagentStatus.RUNNING,
                taskId = "task_$task",
                subagentAgentId = "agent-local-$task",
                parentAgentId = PARENT_AGENT,
                parentConversationId = PARENT_CONVERSATION,
            )
        }
    }
}
