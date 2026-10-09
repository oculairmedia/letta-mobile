package com.letta.mobile.data.workspace.relay

import com.letta.mobile.data.transport.api.IChannelTransport
import com.letta.mobile.data.transport.appserver.AppServerClient
import com.letta.mobile.data.transport.appserver.AppServerCommand
import com.letta.mobile.data.transport.appserver.AppServerInboundFrame
import com.letta.mobile.data.transport.appserver.AppServerProtocol
import com.letta.mobile.data.transport.appserver.AppServerReceivedFrame
import com.letta.mobile.data.transport.appserver.AppServerWorkspaceCommand
import com.letta.mobile.data.transport.appserver.WorkspaceRelay
import com.letta.mobile.data.transport.appserver.WorkspaceRelayAccess
import com.letta.mobile.data.transport.appserver.WorkspaceRelayMethod
import com.letta.mobile.data.transport.iroh.AdminRpcErrors
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.serialization.json.JsonObject

/** One admin_rpc call on the Iroh connection: [method] with [params], answered by the host. */
fun interface WorkspaceRelayCall {
    suspend fun call(method: String, params: JsonObject): AppServerInboundFrame.AdminRpcResponse

    companion object {
        /** Over the session's channel transport; [transport] is read per call so a swapped transport is followed. */
        fun overTransport(transport: () -> IChannelTransport?): WorkspaceRelayCall = WorkspaceRelayCall { method, params ->
            val channel = transport() ?: throw IllegalStateException(WorkspaceRelayClient.NOT_CONNECTED)
            channel.adminRpc(method = method, path = "", body = params.toString())
        }
    }
}

/**
 * letta-mobile-bzvro.37: the agent-workspace request path of [AppServerClient] over an Iroh host's
 * relay (see [WorkspaceRelay]). The MemFS, secrets and workspace-file sources keep calling
 * [workspaceRequest] / [writeMemoryFile] exactly as they do on a direct App Server session; this
 * client carries those two calls, and only those, as admin_rpc to the host.
 *
 * Nothing here logs. The host's answer can hold secret values (`secret_list_response`), so a
 * failure message is the host's error text or a fixed sentence, never a frame.
 */
class WorkspaceRelayClient(private val relay: WorkspaceRelayCall) : AppServerClient {
    override val events: Flow<AppServerReceivedFrame> = emptyFlow()

    override suspend fun workspaceRequest(command: AppServerWorkspaceCommand): List<JsonObject> =
        relayed(command, command.requestId)

    override suspend fun writeMemoryFile(command: AppServerCommand.WriteMemoryFile): AppServerInboundFrame.WriteMemoryFileResponse {
        val frame = relayed(command, command.requestId).singleOrNull()
            ?: throw IllegalStateException("The Iroh host sent no write_memory_file_response.")
        return runCatching { AppServerProtocol.json.decodeFromJsonElement(AppServerInboundFrame.WriteMemoryFileResponse.serializer(), frame) }
            .getOrElse { throw IllegalStateException("The Iroh host sent an unreadable write_memory_file_response.") }
    }

    private suspend fun relayed(command: AppServerCommand, requestId: String): List<JsonObject> {
        val method = WorkspaceRelay.methodFor(command)
        val response = relay.call(method.method, WorkspaceRelay.encodeParams(command))
        if (!response.success) throw failure(method, response.error)
        return WorkspaceRelay.decodeResult(response.result, requestId)
    }

    private fun failure(method: WorkspaceRelayMethod, error: String?): Exception = when {
        AdminRpcErrors.isUnknownMethod(error) -> IllegalStateException(HOST_NEEDS_UPDATE)
        error == FORBIDDEN -> IllegalStateException(forbidden(method.access))
        else -> IllegalStateException(error ?: "The Iroh host could not relay ${method.commandType}.")
    }

    // Not relayed: the workspace sources never call these on this client.
    override suspend fun runtimeStart(command: AppServerCommand.RuntimeStart): AppServerInboundFrame.RuntimeStartResponse = notRelayed("runtime_start")
    override suspend fun input(command: AppServerCommand.Input) = notRelayed("input")
    override suspend fun sync(command: AppServerCommand.Sync): AppServerInboundFrame.SyncResponse = notRelayed("sync")
    override suspend fun abort(command: AppServerCommand.AbortMessage): AppServerInboundFrame.AbortMessageResponse = notRelayed("abort_message")
    override suspend fun adminRpc(command: AppServerCommand.AdminRpc): AppServerInboundFrame.AdminRpcResponse = notRelayed("admin_rpc")
    override suspend fun sendExternalToolResponse(command: AppServerCommand.ExternalToolCallResponse) = notRelayed("external_tool_call_response")

    private fun notRelayed(type: String): Nothing =
        throw UnsupportedOperationException("$type is not carried by the workspace relay")

    companion object {
        private const val FORBIDDEN = "forbidden"

        const val NOT_CONNECTED: String = "Not connected to an Iroh host."

        /** The host answers "Unknown method": its build predates the relay. */
        const val HOST_NEEDS_UPDATE: String =
            "The Iroh host does not relay this yet. Update the host (meridian-iroh-wrapper) to a build with the workspace relay."

        fun forbidden(access: WorkspaceRelayAccess): String = when (access) {
            WorkspaceRelayAccess.MemoryRead -> "This device is not allowed to read memory on the Iroh host."
            WorkspaceRelayAccess.MemoryWrite -> "This device is not allowed to change memory on the Iroh host."
            WorkspaceRelayAccess.Files -> "This device is not allowed to read workspace files on the Iroh host."
            WorkspaceRelayAccess.Secrets -> "This device is not allowed to manage secrets on the Iroh host (it needs admin access)."
        }
    }
}
