package com.letta.mobile.data.controller.node

import com.letta.mobile.data.controller.AppServerController
import com.letta.mobile.data.controller.AppServerControllerState
import com.letta.mobile.data.controller.CanonicalRuntime
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.transport.appserver.AppServerEndpoint
import com.letta.mobile.data.transport.appserver.AppServerInboundFrame
import com.letta.mobile.data.transport.appserver.AppServerPermissionMode
import com.letta.mobile.data.transport.appserver.AppServerRuntimeScope
import com.letta.mobile.runtime.ConversationId
import com.letta.mobile.runtime.RuntimeEventDraft
import com.letta.mobile.runtime.TurnCommand
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * Shared test utilities for node tests.
 */

/**
 * Fake node client for testing.
 * Returns a pre-configured controller for all connection attempts.
 */
class FakeNodeClient(
    private val controller: AppServerController,
) : NodeClient {
    override suspend fun connectTo(identity: NodeIdentity): RemoteNodeHandle {
        val endpoint = identity.endpoints.firstOrNull()
            ?: throw IllegalArgumentException("NodeIdentity has no endpoints")
        return connectTo(endpoint)
    }

    override suspend fun connectTo(endpoint: AppServerEndpoint): RemoteNodeHandle {
        return RemoteNodeHandle(controller)
    }
}

/**
 * Fake controller for testing.
 * Returns successful responses for all operations.
 */
class FakeAppServerController : AppServerController {
    override val state: Flow<AppServerControllerState> = flowOf(AppServerControllerState.Connected)

    override suspend fun startRuntime(
        agentId: AgentId,
        conversationId: ConversationId,
        cwd: String?,
        mode: AppServerPermissionMode?,
        recoverApprovals: Boolean,
        forceDeviceStatus: Boolean,
    ): CanonicalRuntime {
        return CanonicalRuntime(
            scope = AppServerRuntimeScope(
                agentId = agentId.value,
                conversationId = conversationId.value,
                actingUserId = "fake-user",
            ),
        )
    }

    override fun runTurn(command: TurnCommand): Flow<RuntimeEventDraft> {
        return flowOf()
    }

    override suspend fun sync(
        runtime: AppServerRuntimeScope,
        recoverApprovals: Boolean,
        forceDeviceStatus: Boolean,
    ): AppServerInboundFrame.SyncResponse {
        return AppServerInboundFrame.SyncResponse(
            requestId = "fake-sync",
            runtime = runtime,
            success = true,
        )
    }

    override suspend fun abort(
        runtime: AppServerRuntimeScope,
        runId: String?,
    ): AppServerInboundFrame.AbortMessageResponse {
        return AppServerInboundFrame.AbortMessageResponse(
            requestId = "fake-abort",
            runtime = runtime,
            aborted = true,
            success = true,
        )
    }
}
