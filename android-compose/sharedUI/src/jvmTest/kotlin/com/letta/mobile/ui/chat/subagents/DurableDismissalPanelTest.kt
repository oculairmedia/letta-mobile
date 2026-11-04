@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.letta.mobile.ui.chat.subagents

import com.letta.mobile.data.repository.api.dismissAction

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import com.letta.mobile.data.controller.node.iroh.AdminRpcHostServices
import com.letta.mobile.data.controller.node.iroh.AdminRpcInvocation
import com.letta.mobile.data.controller.node.iroh.AdminRpcPairing
import com.letta.mobile.data.controller.node.iroh.AdminRpcRegistry
import com.letta.mobile.data.controller.node.iroh.AdminRpcRequestContext
import com.letta.mobile.data.controller.node.iroh.AdminRpcRouter
import com.letta.mobile.data.controller.node.iroh.ControllerSubagentRegistrySource
import com.letta.mobile.data.controller.node.iroh.InMemoryPairedPeerStore
import com.letta.mobile.data.controller.node.iroh.IrohPairingService
import com.letta.mobile.data.controller.node.iroh.SubagentRegistryPublisher
import com.letta.mobile.data.model.SubagentEntry
import com.letta.mobile.data.model.SubagentStatus
import com.letta.mobile.data.presence.SubagentActivity
import com.letta.mobile.data.repository.SubagentRepository
import com.letta.mobile.data.repository.api.SubagentParentScope
import com.letta.mobile.data.subagents.DurableSubagentRegistry
import com.letta.mobile.data.subagents.FileSubagentRegistryStore
import com.letta.mobile.data.subagents.SubagentChipSource
import com.letta.mobile.data.transport.ServerFrame
import com.letta.mobile.data.transport.ServerFrameSerializer
import com.letta.mobile.data.transport.api.NoOpChannelTransport
import com.letta.mobile.data.transport.api.SubagentScopeAwareChannelTransport
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals

class DurableDismissalPanelTest {
    @Test
    fun hostDismissalPersistsPublishesAndRemovesOnlyTheSelectedRenderedTask() = runComposeUiTest {
        val dir = Files.createTempDirectory("subagent-panel-test")
        val path = dir.resolve("registry.json")
        val owner = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val source = ControllerSubagentRegistrySource(DurableSubagentRegistry(FileSubagentRegistryStore(path)))
        val scope = SubagentParentScope("agent-a", "conv-a")
        val eventBus = MutableSharedFlow<ServerFrame>(extraBufferCapacity = 16)
        val json = Json { ignoreUnknownKeys = true }
        val publisher = SubagentRegistryPublisher(owner, source)
        publisher.attach { conversationId, frame ->
            require(conversationId == scope.parentConversationId)
            eventBus.emit(json.decodeFromString(ServerFrameSerializer, frame))
            1
        }
        source.changeListener = publisher
        val router = AdminRpcRegistry.buildRouter(
            services = AdminRpcHostServices(subagentRegistrySource = source),
            pairing = AdminRpcPairing(pairingService = IrohPairingService(InMemoryPairedPeerStore())),
        )
        val context = AdminRpcRequestContext(
            authenticated = true,
            authorizedConversationIds = setOf(scope.parentConversationId),
            authorizedAgentIds = setOf(scope.parentAgentId),
        )
        val transport = testTransport(source, router, context, eventBus, json)
        val repository = SubagentRepository(transport, owner)
        val entries = listOf("tool-a", "tool-b").map { id ->
            SubagentEntry(
                toolCallId = id,
                description = "same title",
                status = SubagentStatus.RUNNING,
                parentAgentId = scope.parentAgentId,
                parentConversationId = scope.parentConversationId,
            )
        }
        try {
            source.ingestFromSource(scope.parentConversationId, scope.parentAgentId, entries, SubagentChipSource.CONTROLLER_NATIVE)
            setContent {
                val flow = remember { repository.activeSubagentsFlow(scope) }
                val current by flow.collectAsState(emptyList())
                MaterialTheme {
                    BackgroundTasksPanel(subagents = current, dismissTask = repository.dismissAction(scope))
                }
            }
            assertDismissal(repository, scope, source, entries, path)
        } finally {
            repository.close()
            publisher.close()
            owner.cancel()
            Files.deleteIfExists(path)
            Files.deleteIfExists(dir)
        }
    }

    private fun ComposeUiTest.assertDismissal(
        repository: SubagentRepository,
        scope: SubagentParentScope,
        source: ControllerSubagentRegistrySource,
        entries: List<SubagentEntry>,
        path: Path,
    ) {
        waitUntil(timeoutMillis = 10_000) { repository.currentActiveSubagents(scope).size == 2 }
        onNodeWithTag(SubagentRosterTag.RUNNING.of("tool-a")).assertExists()
        onNodeWithTag(SubagentRosterTag.RUNNING.of("tool-b")).assertExists()
        onNode(
            hasText("Dismiss") and hasAnyAncestor(hasTestTag(SubagentRosterTag.RUNNING.of("tool-a"))),
            useUnmergedTree = true,
        ).performClick()
        waitUntil(timeoutMillis = 10_000) {
            repository.currentActiveSubagents(scope).map { it.toolCallId } == listOf("tool-b")
        }
        onNodeWithTag(SubagentRosterTag.RUNNING.of("tool-a")).assertDoesNotExist()
        onNodeWithTag(SubagentRosterTag.RUNNING.of("tool-b")).assertExists()
        assertEquals(1, SubagentActivity.of(repository.currentActiveSubagents(scope)).running)
        val reloaded = DurableSubagentRegistry(FileSubagentRegistryStore(path))
        assertEquals("user_dismissed", reloaded.record("conv-a", "agent-a", "tool-a")?.terminalReason)
        source.ingestFromSource(scope.parentConversationId, scope.parentAgentId, entries, SubagentChipSource.CONTROLLER_NATIVE)
        assertEquals("user_dismissed", source.registry.record("conv-a", "agent-a", "tool-a")?.terminalReason)
    }

    private fun testTransport(
        source: ControllerSubagentRegistrySource,
        router: AdminRpcRouter,
        context: AdminRpcRequestContext,
        eventBus: MutableSharedFlow<ServerFrame>,
        json: Json,
    ): NoOpChannelTransport =
        object : NoOpChannelTransport(), SubagentScopeAwareChannelTransport {
            override val events = eventBus

            override suspend fun sendSubagentListForScope(
                scope: SubagentParentScope,
                all: Boolean,
                timeoutMs: Long,
            ): ServerFrame.SubagentListResponse {
                require(scope.parentAgentId == "agent-a" && scope.parentConversationId == "conv-a")
                val entries = source.list(scope.parentConversationId, all).filter { it.parentAgentId == scope.parentAgentId }
                return ServerFrame.SubagentListResponse(id = "list", ts = "t", success = true, subagents = entries)
            }

            override suspend fun dismissSubagent(scope: SubagentParentScope, toolCallId: String): Result<List<SubagentEntry>> =
                runCatching {
                    require(scope.parentAgentId == "agent-a" && scope.parentConversationId == "conv-a")
                    require(toolCallId == "tool-a" || toolCallId == "tool-b")
                    val params = buildJsonObject {
                        put("agent_id", scope.parentAgentId)
                        put("conversation_id", scope.parentConversationId)
                        put("tool_call_id", toolCallId)
                    }
                    val response = router.dispatch(AdminRpcInvocation("dismiss", "subagent.dismiss", params, context))
                    val envelope = json.parseToJsonElement(response).jsonObject
                    check(envelope.getValue("success").jsonPrimitive.content == "true")
                    json.decodeFromJsonElement(envelope.getValue("result").jsonObject.getValue("subagents"))
                }
        }

}
