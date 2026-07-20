package com.letta.mobile.cli.commands

import com.letta.mobile.data.controller.DefaultAppServerController
import com.letta.mobile.data.controller.node.iroh.AdminRpcRegistry
import com.letta.mobile.data.controller.node.iroh.AppServerSubagentRegistrySource
import com.letta.mobile.data.transport.appserver.AppServerClient
import com.letta.mobile.data.transport.appserver.AppServerCommand
import com.letta.mobile.data.transport.appserver.AppServerInboundFrame
import com.letta.mobile.data.transport.appserver.AppServerReceivedFrame
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.TestScope
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AppServerServeIrohProductionWiringTest {
    @Test
    fun `production router advertises subagent capability only with live source`() {
        val client = EmptyClient
        val controller = DefaultAppServerController(client)
        val source = AppServerSubagentRegistrySource(client, TestScope())

        val router = buildProductionAdminRouter("http://127.0.0.1:8291", controller, source)

        assertTrue(router.registeredMethods.containsAll(AdminRpcRegistry.subagentMethods))
    }

    private object EmptyClient : AppServerClient {
        override val events: Flow<AppServerReceivedFrame> = emptyFlow()
        override suspend fun runtimeStart(command: AppServerCommand.RuntimeStart) = error("unused")
        override suspend fun input(command: AppServerCommand.Input) = error("unused")
        override suspend fun sync(command: AppServerCommand.Sync) = error("unused")
        override suspend fun abort(command: AppServerCommand.AbortMessage) = error("unused")
        override suspend fun adminRpc(command: AppServerCommand.AdminRpc) = error("unused")
        override suspend fun sendExternalToolResponse(command: AppServerCommand.ExternalToolCallResponse) = error("unused")
    }
}
