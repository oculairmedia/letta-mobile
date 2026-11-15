package com.letta.mobile.data.controller.node.iroh

import com.letta.mobile.data.serialization.LettaJson
import com.letta.mobile.data.transport.appserver.AppServerClient
import com.letta.mobile.data.transport.appserver.AppServerCommand
import com.letta.mobile.data.transport.appserver.AppServerInboundFrame
import com.letta.mobile.data.transport.appserver.AppServerRuntimeScope
import com.letta.mobile.data.transport.appserver.AppServerReceivedFrame
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import java.io.File
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonArray
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MonitorStopContractTest {
    private class Client : AppServerClient {
        val calls = mutableListOf<AppServerCommand.MonitorStop>()
        var success = true
        override val events: Flow<AppServerReceivedFrame> = emptyFlow()
        override suspend fun runtimeStart(command: AppServerCommand.RuntimeStart) = error("unused")
        override suspend fun input(command: AppServerCommand.Input) = error("unused")
        override suspend fun sync(command: AppServerCommand.Sync) = error("unused")
        override suspend fun abort(command: AppServerCommand.AbortMessage) = error("unused")
        override suspend fun adminRpc(command: AppServerCommand.AdminRpc) = error("unused")
        override suspend fun sendExternalToolResponse(command: AppServerCommand.ExternalToolCallResponse) = error("unused")
        override suspend fun monitorStop(command: AppServerCommand.MonitorStop): AppServerInboundFrame.MonitorStopResponse {
            calls += command
            return AppServerInboundFrame.MonitorStopResponse(command.requestId, success, if (success) null else "cannot stop")
        }
    }

    private val fixture = LettaJson.wire.parseToJsonElement(
        File("src/commonTest/resources/contracts/monitor.stop/process-v1.json").readText(),
    ).jsonObject.getValue("cases").jsonArray.first().jsonObject

    private suspend fun call(
        client: AppServerClient?,
        params: JsonObject = fixture.getValue("params").jsonObject,
        context: AdminRpcRequestContext = AdminRpcRequestContext.Authenticated,
    ): String = AdminRpcRegistry.buildRouter(native = AdminRpcNativeSources(nativeClient = client)).dispatch(
        AdminRpcInvocation("test", "monitor.stop", params, context),
    )

    @Test
    fun stopRequiresChatSendCapability() {
        assertEquals(IrohPeerCapabilities.CHAT_SEND, IrohPeerCapabilities.forAdminMethod("monitor.stop"))
    }

    @Test
    fun fixtureForwardsExactlyOneScopedStop() = runTest {
        val client = Client()
        val result = LettaJson.wire.parseToJsonElement(call(client)).jsonObject
        assertEquals(fixture.getValue("expected_response").jsonObject.getValue("result"), result["result"])
        val command = client.calls.single()
        assertEquals("process-1", command.processId)
        assertEquals(AppServerRuntimeScope("agent-1", "conv-1"), command.runtime)
        assertTrue(command.requestId.isNotBlank())
    }

    @Test
    fun failureIsAnError() = runTest {
        val client = Client().apply { success = false }
        val result = call(client)
        assertTrue(result.contains("\"success\":false") && result.contains("cannot stop"), result)
    }

    @Test
    fun missingNativeClientFailsClearly() = runTest {
        assertTrue(call(null).contains("capability_unavailable"))
    }

    @Test
    fun missingProcessAndDeniedConversationNeverDispatch() = runTest {
        val client = Client()
        assertTrue(call(client, JsonObject(fixture.getValue("params").jsonObject - "process_id")).contains("\"success\":false"))
        assertTrue(call(client, context = AdminRpcRequestContext(true, setOf("elsewhere"))).contains("\"success\":false"))
        assertTrue(call(client, context = AdminRpcRequestContext(true, authorizedAgentIds = setOf("other")))
            .contains("\"success\":false"))
        assertTrue(client.calls.isEmpty())
    }
}
