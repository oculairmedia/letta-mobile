package com.letta.mobile.desktop.commands

import com.letta.mobile.desktop.chat.DesktopChatController
import com.letta.mobile.desktop.chat.DesktopChatSessionPort
import com.letta.mobile.desktop.chat.FakeDesktopChatGateway
import com.letta.mobile.desktop.chat.TEST_ROSTER
import com.letta.mobile.desktop.defaultDesktopBootstrapState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class SlashCommandComposerTest {
    @Test
    fun hostAdvertisedCommandRunsFromTheDesktopComposerThroughTheGraphAdapter() = runTest {
        val gateway = FakeDesktopChatGateway()
        val (controller, owner) = openedComposer(gateway)
        val channel = SlashCommandChannel()
        val graph = com.letta.mobile.data.session.graph.HostSessionGraphFactory {
            com.letta.mobile.data.session.graph.HostSessionBinding(
                platform = com.letta.mobile.data.backend.SessionPlatform.DESKTOP,
                config = com.letta.mobile.data.model.LettaConfig(
                    id = "commands", mode = com.letta.mobile.data.model.LettaConfig.Mode.SELF_HOSTED, serverUrl = "iroh://commands",
                ),
                appServerClients = com.letta.mobile.data.session.graph.HostAppServerClients { null },
                channelTransport = channel,
            )
        }.create()
        try {
            val port = com.letta.mobile.data.chat.routing.SlashCommandSessionPort(
                owner, backgroundScope,
                com.letta.mobile.desktop.commands.DesktopSlashCommandBinding(
                    { graph }, { controller.state.value.selectedConversation }, controller::isSelectionCurrent,
                ),
            )
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { port.liveStatus.collect {} }
            port.actions.updateComposerText("/deploy staging --check")
            runCurrent()
            port.actions.send()
            runCurrent()
            val params = channel.executions.single()
            assertEquals("deploy", params.getValue("command_id").jsonPrimitive.content)
            assertEquals("staging --check", params.getValue("args").jsonPrimitive.content)
            assertEquals("agent-0", params.getValue("agent_id").jsonPrimitive.content)
            assertEquals("conv-1", params.getValue("conversation_id").jsonPrimitive.content)
            assertTrue(gateway.sentRequests.isEmpty())
            assertEquals("", owner.composer.value.text)
            assertEquals("deployed", port.liveStatus.value.commands.single().output)
        } finally {
            graph.close()
            controller.close()
        }
    }

    private class SlashCommandChannel : com.letta.mobile.data.transport.api.NoOpChannelTransport() {
        val executions = mutableListOf<kotlinx.serialization.json.JsonObject>()
        override suspend fun adminRpc(method: String, path: String, body: String?):
            com.letta.mobile.data.transport.appserver.AppServerInboundFrame.AdminRpcResponse {
            val result = when (method) {
                "slash_command.list_agent" -> kotlinx.serialization.json.Json.parseToJsonElement(
                    """{"commands":[{"command":"/deploy","description":"Deploy safely","source":"mod","installed":true}]}""",
                )
                "slash_command.execute" -> {
                    executions += kotlinx.serialization.json.Json.parseToJsonElement(requireNotNull(body)).jsonObject
                    kotlinx.serialization.json.Json.parseToJsonElement("""{"output":"deployed"}""")
                }
                else -> error("Unexpected RPC: $method")
            }
            return com.letta.mobile.data.transport.appserver.AppServerInboundFrame.AdminRpcResponse("command", true, result = result)
        }
    }

    private fun TestScope.openedComposer(gateway: FakeDesktopChatGateway): Pair<DesktopChatController, DesktopChatSessionPort> {
        val controller = DesktopChatController(
            bootstrapState = defaultDesktopBootstrapState(), scope = this, rosterProvider = TEST_ROSTER, gatewayFactory = { gateway },
        )
        val port = DesktopChatSessionPort(controller, backgroundScope)
        backgroundScope.launch { port.uiState.collect {} }
        backgroundScope.launch { port.composer.collect {} }
        controller.start()
        runCurrent()
        controller.selectConversation("conv-1")
        runCurrent()
        return controller to port
    }
}
