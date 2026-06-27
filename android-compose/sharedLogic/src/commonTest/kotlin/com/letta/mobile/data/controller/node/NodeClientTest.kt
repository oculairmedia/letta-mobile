package com.letta.mobile.data.controller.node

import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.transport.appserver.AppServerEndpoint
import com.letta.mobile.data.transport.appserver.AppServerRuntimeScope
import com.letta.mobile.runtime.BackendId
import com.letta.mobile.runtime.ConversationId
import com.letta.mobile.runtime.RuntimeId
import com.letta.mobile.runtime.TurnCommand
import com.letta.mobile.runtime.TurnInput
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class NodeClientTest {
    @Test
    fun testConnectToNodeIdentity() = runTest {
        val endpoint = AppServerEndpoint(scheme = "fake", address = "test-address")
        val identity = NodeIdentity(
            id = "remote-node",
            displayName = "Remote Node",
            endpoints = setOf(endpoint),
        )

        val fakeController = FakeAppServerController()
        val client = FakeNodeClient(fakeController)

        val handle = client.connectTo(identity)

        assertNotNull(handle)
        assertEquals(fakeController, handle.controller)
    }

    @Test
    fun testConnectToEndpoint() = runTest {
        val endpoint = AppServerEndpoint(scheme = "fake", address = "test-address")
        val fakeController = FakeAppServerController()
        val client = FakeNodeClient(fakeController)

        val handle = client.connectTo(endpoint)

        assertNotNull(handle)
        assertEquals(fakeController, handle.controller)
    }

    // Note: Can't test empty endpoints case because NodeIdentity constructor validates it

    @Test
    fun testRemoteNodeHandleStartRuntime() = runTest {
        val fakeController = FakeAppServerController()
        val handle = RemoteNodeHandle(fakeController)

        val runtime = handle.startRuntime(
            agentId = AgentId("agent-1"),
            conversationId = ConversationId("conv-1"),
        )

        assertEquals("agent-1", runtime.scope.agentId)
        assertEquals("conv-1", runtime.scope.conversationId)
    }

    @Test
    fun testRemoteNodeHandleSync() = runTest {
        val fakeController = FakeAppServerController()
        val handle = RemoteNodeHandle(fakeController)

        val runtime = AppServerRuntimeScope(
            agentId = "agent-1",
            conversationId = "conv-1",
            actingUserId = "user-1",
        )

        val response = handle.sync(runtime)

        assertEquals(runtime, response.runtime)
        assertEquals(true, response.success)
    }

    @Test
    fun testRemoteNodeHandleAbort() = runTest {
        val fakeController = FakeAppServerController()
        val handle = RemoteNodeHandle(fakeController)

        val runtime = AppServerRuntimeScope(
            agentId = "agent-1",
            conversationId = "conv-1",
            actingUserId = "user-1",
        )

        val response = handle.abort(runtime)

        assertEquals(runtime, response.runtime)
        assertEquals(true, response.success)
    }

    @Test
    fun testRemoteNodeHandleRunTurn() = runTest {
        val fakeController = FakeAppServerController()
        val handle = RemoteNodeHandle(fakeController)

        val command = TurnCommand(
            backendId = BackendId("backend-1"),
            runtimeId = RuntimeId("runtime-1"),
            agentId = AgentId("agent-1"),
            conversationId = ConversationId("conv-1"),
            input = TurnInput.UserMessage(
                localMessageId = "msg-1",
                text = "test input",
            ),
        )

        val events = handle.runTurn(command)

        // Just verify it returns without throwing
        assertNotNull(events)
    }
}
