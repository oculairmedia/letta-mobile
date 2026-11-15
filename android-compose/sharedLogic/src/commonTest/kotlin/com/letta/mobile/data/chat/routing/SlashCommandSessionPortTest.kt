package com.letta.mobile.data.chat.routing

import com.letta.mobile.data.commands.api.SlashCommandQuery
import com.letta.mobile.data.session.graph.SharedSessionGraph
import com.letta.mobile.data.transport.api.NoOpChannelTransport
import com.letta.mobile.data.transport.appserver.AppServerClient
import com.letta.mobile.data.transport.appserver.AppServerCommand
import com.letta.mobile.data.transport.appserver.AppServerProtocol
import com.letta.mobile.data.transport.appserver.AppServerReceivedFrame
import com.letta.mobile.data.transport.appserver.AppServerInboundFrame
import com.letta.mobile.data.transport.appserver.AppServerRuntimeScope
import com.letta.mobile.ui.chat.session.FakeChatSessionPort
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertNotEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class SlashCommandSessionPortTest {
    @Test
    fun everyPlatformRunsAnAdvertisedCommandThroughTheGraphInsteadOfSendingAnAgentPrompt() = runTest(UnconfinedTestDispatcher()) {
        for (platform in listOf("android", "desktop", "web")) {
            val channel = CommandChannel()
            val api = SharedSessionGraph.transportBound(channel, backgroundScope).slashCommands
            val owner = FakeChatSessionPort()
            val target = AppServerRuntimeScope("agent-$platform", "conversation-$platform")
            val port = SlashCommandSessionPort(owner, backgroundScope) { api to target }
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { port.liveStatus.collect {} }
            owner.actions.updateComposerText("/deploy staging --check")
            port.actions.send()
            runCurrent()
            assertTrue(owner.sent.isEmpty())
            assertEquals("", owner.composer.value.text)
            val params = channel.executions.single()
            assertEquals("deploy", params.getValue("command_id").jsonPrimitive.content)
            assertEquals("staging --check", params.getValue("args").jsonPrimitive.content)
            assertEquals(target.agentId, params.getValue("agent_id").jsonPrimitive.content)
            assertEquals(target.conversationId, params.getValue("conversation_id").jsonPrimitive.content)
            assertEquals("deployed", port.liveStatus.value.commands.single().output)
        }
    }

    @Test
    fun localCommandsUnknownCommandsAndPlainTextKeepTheirExistingRoutes() = runTest(UnconfinedTestDispatcher()) {
        val channel = CommandChannel()
        val api = SharedSessionGraph.transportBound(channel, backgroundScope).slashCommands
        val owner = FakeChatSessionPort()
        val port = SlashCommandSessionPort(owner, backgroundScope) { api to AppServerRuntimeScope("a", "c") }
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { port.liveStatus.collect {} }
        for (text in listOf("hello", "/recompile", "/fork new branch", "/bug project", "/goal status", "/unknown args")) {
            owner.actions.updateComposerText(text)
            port.actions.send()
            runCurrent()
        }
        assertEquals(listOf("hello", "/recompile", "/fork new branch", "/bug project", "/goal status", "/unknown args"), owner.sent)
        assertTrue(channel.executions.isEmpty())
    }

    @Test
    fun failureKeepsTheDraftAndShowsTheHostError() = runTest(UnconfinedTestDispatcher()) {
        val channel = CommandChannel().apply { fails = true }
        val api = SharedSessionGraph.transportBound(channel, backgroundScope).slashCommands
        val owner = FakeChatSessionPort()
        val port = SlashCommandSessionPort(owner, backgroundScope) { api to AppServerRuntimeScope("a", "c") }
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { port.liveStatus.collect {} }
        owner.actions.updateComposerText("/deploy")
        port.actions.send()
        runCurrent()
        assertEquals("/deploy", owner.composer.value.text)
        assertEquals("deployment denied", owner.composer.value.error)
        assertTrue(owner.sent.isEmpty())
    }

    @Test
    fun switchingTheSessionDuringCatalogLookupCannotRunACommandAgainstTheNewHost() = runTest(UnconfinedTestDispatcher()) {
        val channel = CommandChannel().apply { listingGate = CompletableDeferred() }
        val api = SharedSessionGraph.transportBound(channel, backgroundScope).slashCommands
        val owner = FakeChatSessionPort()
        var target = AppServerRuntimeScope("a", "old")
        val port = SlashCommandSessionPort(owner, backgroundScope) { api to target }
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { port.liveStatus.collect {} }
        owner.actions.updateComposerText("/deploy")
        port.actions.send()
        target = AppServerRuntimeScope("a", "new")
        channel.listingGate?.complete(Unit)
        runCurrent()
        assertTrue(channel.executions.isEmpty())
        assertEquals("/deploy", owner.composer.value.text)
    }

    @Test
    fun repeatedSendDoesNotDuplicateACommandOrEraseAnEditedDraft() = runTest(UnconfinedTestDispatcher()) {
        val channel = CommandChannel().apply { executionGate = CompletableDeferred() }
        val api = SharedSessionGraph.transportBound(channel, backgroundScope).slashCommands
        val owner = FakeChatSessionPort()
        val port = SlashCommandSessionPort(owner, backgroundScope) { api to AppServerRuntimeScope("a", "c") }
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { port.liveStatus.collect {} }
        owner.actions.updateComposerText("/deploy")
        port.actions.send()
        port.actions.send()
        runCurrent()
        assertEquals(1, channel.executions.size)
        owner.actions.updateComposerText("new draft")
        channel.executionGate?.complete(Unit)
        runCurrent()
        assertEquals("new draft", owner.composer.value.text)
        assertEquals(1, channel.executions.size)
        val firstActivity = port.liveStatus.value.commands.single().commandId
        owner.actions.updateComposerText("/deploy")
        port.actions.send()
        runCurrent()
        assertEquals(2, channel.executions.size)
        assertNotEquals(
            firstActivity, port.liveStatus.value.commands.single().commandId, "a dismissed result must not hide the next result",
        )
    }

    @Test
    fun aLateFailureFromTheOldGraphCannotChangeTheNewGraphsDraftOrStatus() = runTest(UnconfinedTestDispatcher()) {
        val channel = CommandChannel().apply {
            executionGate = CompletableDeferred()
            fails = true
        }
        var api = SharedSessionGraph.transportBound(channel, backgroundScope).slashCommands
        val owner = FakeChatSessionPort()
        val port = SlashCommandSessionPort(owner, backgroundScope) { api to AppServerRuntimeScope("a", "c") }
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { port.liveStatus.collect {} }
        owner.actions.updateComposerText("/deploy")
        port.actions.send()
        runCurrent()
        api = SharedSessionGraph.transportBound(CommandChannel(), backgroundScope).slashCommands
        owner.actions.updateComposerText("new draft")
        channel.executionGate?.complete(Unit)
        runCurrent()
        assertEquals("new draft", owner.composer.value.text)
        assertEquals(null, owner.composer.value.error)
        assertTrue(port.liveStatus.value.commands.isEmpty())
    }

    @Test
    fun directAppServerGraphsObserveTheAdvertisedCatalogAndUseTheNativeCommand() = runTest(UnconfinedTestDispatcher()) {
        val client = DirectClient()
        val api = SharedSessionGraph.transportBound(NoOpChannelTransport(), backgroundScope, clientProvider = { client }).slashCommands
        client.events.emit(AppServerProtocol.decodeFrame(
            """{"type":"update_device_status","runtime":{"agent_id":"a","conversation_id":"c"},
                "event_seq":1,"emitted_at":"2026-11-15T00:00:00Z","idempotency_key":"status",
                "device_status":{"supported_commands":["doctor"],"mod_commands":[{"id":"deploy","description":"Deploy safely"}]}}""",
        ))
        runCurrent()
        val commands = api.listAgentSlashCommands(SlashCommandQuery("a"))
        assertEquals(listOf("doctor", "deploy"), commands.map { it.command })
        assertEquals("Deploy safely", commands.last().description)
        assertEquals(listOf("doctor"), api.listAgentSlashCommands(SlashCommandQuery("other")).map { it.command })
        val runtime = AppServerRuntimeScope("a", "c")
        api.executeCommand("deploy", "staging", runtime)
        assertEquals(runtime, client.executed?.runtime)
        assertEquals("staging", client.executed?.args)
    }

    private class DirectClient : AppServerClient {
        override val events = MutableSharedFlow<AppServerReceivedFrame>(replay = 1)
        var executed: AppServerCommand.ExecuteCommand? = null
        override suspend fun executeCommand(
            command: AppServerCommand.ExecuteCommand,
        ): AppServerInboundFrame.ExecuteCommandResponse {
            executed = command
            return AppServerInboundFrame.ExecuteCommandResponse(command.requestId, true)
        }
        override suspend fun runtimeStart(command: AppServerCommand.RuntimeStart) = error("Unexpected runtime start")
        override suspend fun input(command: AppServerCommand.Input): Unit = error("Unexpected input")
        override suspend fun sync(command: AppServerCommand.Sync) = error("Unexpected sync")
        override suspend fun abort(command: AppServerCommand.AbortMessage) = error("Unexpected abort")
        override suspend fun adminRpc(command: AppServerCommand.AdminRpc) = error("Unexpected admin RPC")
        override suspend fun sendExternalToolResponse(
            command: AppServerCommand.ExternalToolCallResponse,
        ): Unit = error("Unexpected tool response")
    }

    private class CommandChannel : NoOpChannelTransport() {
        val executions = mutableListOf<JsonObject>()
        var fails = false
        var listingGate: CompletableDeferred<Unit>? = null
        var executionGate: CompletableDeferred<Unit>? = null
        override suspend fun adminRpc(method: String, path: String, body: String?): AppServerInboundFrame.AdminRpcResponse {
            val result = when (method) {
                "slash_command.list_agent" -> {
                    listingGate?.await()
                    buildJsonObject {
                        put("commands", buildJsonArray { add(buildJsonObject { put("command", "/deploy") }) })
                    }
                }
                "slash_command.execute" -> {
                    executions += Json.parseToJsonElement(requireNotNull(body)) as JsonObject
                    executionGate?.await()
                    buildJsonObject { put("output", "deployed") }
                }
                else -> error("Unexpected RPC: $method")
            }
            val denied = fails && method == "slash_command.execute"
            return AppServerInboundFrame.AdminRpcResponse(
                "request", !denied, result = result, error = if (denied) "deployment denied" else null,
            )
        }
    }
}
