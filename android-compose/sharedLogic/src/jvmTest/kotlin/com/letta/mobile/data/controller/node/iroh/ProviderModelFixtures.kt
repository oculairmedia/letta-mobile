package com.letta.mobile.data.controller.node.iroh

import com.letta.mobile.data.transport.appserver.AppServerClient
import com.letta.mobile.data.transport.appserver.AppServerCommand
import com.letta.mobile.data.transport.appserver.AppServerInboundFrame
import com.letta.mobile.data.transport.appserver.AppServerProtocol
import com.letta.mobile.data.transport.appserver.AppServerReceivedFrame
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Recorded App Server 0.32.17 frames for the provider/model control surface
 * (letta-mobile-w4q4p). Captured from the live `ws://127.0.0.1:4500/ws`
 * App Server with read-only / rejected commands only; LAN addresses replaced
 * by 127.0.0.1.
 */
internal object ProviderModelFixtures {
    fun listConnectProvidersResponse(requestId: String): AppServerInboundFrame.ListConnectProvidersResponse {
        val frame = decode(resource("/appserver/probe-list-connect-providers-response.json"))
            as AppServerInboundFrame.ListConnectProvidersResponse
        return frame.copy(requestId = requestId)
    }

    /** The three recorded rejection frames, keyed by frame type. */
    fun errorFrames(): Map<String, AppServerInboundFrame> =
        resource("/appserver/probe-provider-model-error-responses.jsonl")
            .lineSequence()
            .filter { it.isNotBlank() }
            .map(::decode)
            .associateBy { checkNotNull(it.type) }

    /** Two reasoning variants of one handle plus a second model — the upstream `list_models` shape. */
    fun listModelsEntries() = buildJsonArray {
        add(entry(id = "gpt-sol-none", handle = "openai/gpt-sol", effort = "none"))
        add(entry(id = "gpt-sol-high", handle = "openai/gpt-sol", effort = "high"))
        add(entry(id = "minimax", handle = "lmstudio/minimax-m3", effort = null))
    }

    private fun entry(id: String, handle: String, effort: String?): JsonObject = buildJsonObject {
        put("id", id)
        put("handle", handle)
        put("label", id)
        put("description", "fixture")
        put(
            "updateArgs",
            buildJsonObject {
                effort?.let { put("reasoning_effort", it) }
                put("context_window", 128000)
            },
        )
    }

    fun decode(raw: String): AppServerInboundFrame = AppServerProtocol.decodeFrame(raw).frame

    fun resource(path: String): String =
        checkNotNull(ProviderModelFixtures::class.java.getResourceAsStream(path)) { "missing fixture $path" }
            .bufferedReader()
            .use { it.readText() }
}

/** Unwraps a successful admin_rpc response frame to its `result`. */
internal object AdminRpcTestEnvelope {
    fun result(response: String): JsonElement {
        val envelope = Json.parseToJsonElement(response) as JsonObject
        check(envelope["success"]?.toString() == "true") { "expected success, got $response" }
        return checkNotNull(envelope["result"]) { "no result in $response" }
    }
}

/** Fake native client answering the provider/model commands; records every command it receives. */
internal class FakeProviderModelClient(
    private val connectSucceeds: Boolean = true,
    private val updateSucceeds: Boolean = true,
) : AppServerClient {
    override val events: Flow<AppServerReceivedFrame> = MutableSharedFlow()
    val commands = mutableListOf<AppServerCommand>()

    override suspend fun runtimeStart(command: AppServerCommand.RuntimeStart) = error("unused")
    override suspend fun input(command: AppServerCommand.Input) = error("unused")
    override suspend fun sync(command: AppServerCommand.Sync) = error("unused")
    override suspend fun abort(command: AppServerCommand.AbortMessage) = error("unused")
    override suspend fun adminRpc(command: AppServerCommand.AdminRpc) = error("unused")
    override suspend fun sendExternalToolResponse(command: AppServerCommand.ExternalToolCallResponse) = error("unused")

    override suspend fun listConnectProviders(command: AppServerCommand.ListConnectProviders) =
        ProviderModelFixtures.listConnectProvidersResponse(command.requestId).also { commands += command }

    override suspend fun connectProvider(command: AppServerCommand.ConnectProvider): AppServerInboundFrame.ConnectProviderResponse {
        commands += command
        if (!connectSucceeds) {
            return (ProviderModelFixtures.errorFrames().getValue("connect_provider_response")
                as AppServerInboundFrame.ConnectProviderResponse).copy(requestId = command.requestId)
        }
        val providers = ProviderModelFixtures.listConnectProvidersResponse(command.requestId).providers
        return AppServerInboundFrame.ConnectProviderResponse(command.requestId, true, "local", providers, true)
    }

    override suspend fun disconnectProvider(command: AppServerCommand.DisconnectProvider) =
        (ProviderModelFixtures.errorFrames().getValue("disconnect_provider_response")
            as AppServerInboundFrame.DisconnectProviderResponse).copy(requestId = command.requestId)
            .also { commands += command }

    override suspend fun updateModel(command: AppServerCommand.UpdateModel): AppServerInboundFrame.UpdateModelResponse {
        commands += command
        if (!updateSucceeds) {
            return (ProviderModelFixtures.errorFrames().getValue("update_model_response")
                as AppServerInboundFrame.UpdateModelResponse).copy(requestId = command.requestId)
        }
        return AppServerInboundFrame.UpdateModelResponse(
            requestId = command.requestId,
            success = true,
            scope = command.runtime,
            appliedTo = "conversation",
            modelHandle = command.payload.modelHandle,
        )
    }

    override suspend fun listModels(command: AppServerCommand.ListModels) =
        AppServerInboundFrame.ListModelsResponse(command.requestId, true, ProviderModelFixtures.listModelsEntries())
            .also { commands += command }
}
