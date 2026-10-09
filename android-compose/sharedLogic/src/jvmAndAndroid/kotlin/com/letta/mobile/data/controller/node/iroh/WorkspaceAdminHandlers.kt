package com.letta.mobile.data.controller.node.iroh

import com.letta.mobile.data.transport.appserver.AppServerClient
import com.letta.mobile.data.transport.appserver.AppServerCommand
import com.letta.mobile.data.transport.appserver.AppServerInboundFrame
import com.letta.mobile.data.transport.appserver.AppServerProtocol
import com.letta.mobile.data.transport.appserver.AppServerRequestTimeoutException
import com.letta.mobile.data.transport.appserver.AppServerWorkspaceCommand
import com.letta.mobile.data.transport.appserver.WorkspaceRelay
import com.letta.mobile.data.transport.appserver.WorkspaceRelayMethod
import com.letta.mobile.util.Telemetry
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/**
 * letta-mobile-bzvro.37: relays the agent-workspace commands (MemFS browser, secrets vault,
 * workspace files) from an Iroh client to this node's App Server — the same commands, through the
 * same [AppServerClient.workspaceRequest] / [AppServerClient.writeMemoryFile] path, a direct
 * desktop session uses.
 *
 * Only the [WorkspaceRelayMethod] allowlist is registered; each method fixes its App Server
 * command type, and [WorkspaceRelay.decodeCommand] applies the field caps before anything is sent.
 * The per-method capability (memory read/write, files, admin for secrets) is enforced before
 * dispatch by [IrohPeerCapabilities.forAdminMethod].
 *
 * Secret values cross this relay in plaintext (inside the authenticated, encrypted Iroh
 * connection): nothing here logs params or frames, and failures carry fixed sentences only.
 */
internal object WorkspaceAdminHandlers {
    val methods: Set<String> = WorkspaceRelayMethod.methods

    fun register(router: AdminRpcRouter, nativeClient: AppServerClient?) {
        if (nativeClient == null) {
            CapabilityUnavailable.register(router, methods, "native App Server client")
            return
        }
        WorkspaceRelayMethod.entries.forEach { method ->
            router.register(method.method) { params -> relay(nativeClient, method, params) }
        }
        router.featureCapabilities += WorkspaceRelay.CAPABILITY
    }

    private suspend fun relay(client: AppServerClient, method: WorkspaceRelayMethod, params: JsonObject?): JsonElement {
        val command = WorkspaceRelay.decodeCommand(method, params, NativeAdmin.requestId())
        val frames = forward(client, method, command).map { WorkspaceRelay.normalizeFrame(method, it) }
        val result = WorkspaceRelay.encodeResult(frames)
        Telemetry.event("IrohNode", "workspace_relay.ok", "method" to method.method, "frames" to frames.size)
        return result
    }

    private suspend fun forward(client: AppServerClient, method: WorkspaceRelayMethod, command: AppServerCommand): List<JsonObject> = try {
        when (command) {
            is AppServerWorkspaceCommand -> client.workspaceRequest(command)
            is AppServerCommand.WriteMemoryFile -> listOf(encode(client.writeMemoryFile(command)))
            else -> adminError("${method.method} is not relayed")
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (timeout: AppServerRequestTimeoutException) {
        fail(method, "timeout", timeout, "the App Server did not answer ${method.commandType} in time")
    } catch (unsupported: UnsupportedOperationException) {
        fail(method, "unsupported", unsupported, "capability_unavailable: the App Server does not support ${method.commandType}")
    } catch (error: Exception) {
        // Never the exception's message: it can quote a frame, and a frame can hold secret values.
        fail(method, "failed", error, "${method.commandType} failed on the host")
    }

    private fun fail(method: WorkspaceRelayMethod, outcome: String, error: Exception, message: String): Nothing {
        Telemetry.event(
            "IrohNode", "workspace_relay.$outcome",
            "method" to method.method,
            "class" to error::class.simpleName,
            level = Telemetry.Level.WARN,
        )
        adminError(message)
    }

    private fun encode(frame: AppServerInboundFrame): JsonObject =
        AppServerProtocol.json.encodeToJsonElement(AppServerInboundFrame.serializer(), frame).jsonObject
}
