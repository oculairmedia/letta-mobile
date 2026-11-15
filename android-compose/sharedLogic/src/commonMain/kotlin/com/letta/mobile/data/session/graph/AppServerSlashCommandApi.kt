package com.letta.mobile.data.session.graph

import com.letta.mobile.data.commands.api.SlashCommandQuery
import com.letta.mobile.data.transport.appserver.AppServerSlashCommandApi
import com.letta.mobile.data.transport.appserver.AppServerClient
import com.letta.mobile.data.transport.appserver.AppServerInboundFrame
import com.letta.mobile.data.transport.api.IChannelTransport
import com.letta.mobile.data.commands.api.SlashCommandsResponse
import com.letta.mobile.data.commands.api.SlashCommandCatalog
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import com.letta.mobile.data.serialization.LettaJson
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/** The shared session's command adapter, using the wrapper's confirmed admin responses. */
@OptIn(ExperimentalUuidApi::class)
internal fun sessionSlashCommands(
    transport: IChannelTransport,
    scope: CoroutineScope,
    clientProvider: (suspend () -> AppServerClient)?,
): AppServerSlashCommandApi {
    if (clientProvider != null) return directSessionSlashCommands(scope, clientProvider)
    val json = LettaJson.wire
    suspend fun call(method: String, params: JsonObject): JsonObject {
        val response = transport.adminRpc(method, "", params.toString())
        check(response.success) { response.error ?: "$method failed" }
        return response.result?.jsonObject ?: error("$method returned no result")
    }
    return AppServerSlashCommandApi(
        execute = { command ->
            val runtime = requireNotNull(command.runtime) { "A command needs a conversation" }
            val result = call("slash_command.execute", buildJsonObject {
                put("command_id", command.commandId)
                put("agent_id", runtime.agentId)
                put("conversation_id", runtime.conversationId)
                command.args?.let { put("args", it) }
            })
            json.decodeFromJsonElement(
                AppServerInboundFrame.ExecuteCommandResponse.serializer(),
                JsonObject(result + buildJsonObject {
                    put("request_id", command.requestId)
                    put("success", true)
                }),
            )
        },
        commandsProvider = { agent ->
            val result = call("slash_command.list_agent", buildJsonObject { put("agent_id", agent) })
            json.decodeFromJsonElement(SlashCommandsResponse.serializer(), result).commands
        },
        requestId = { "slash-$it-${Uuid.random()}" },
    )
}

/** Direct App Server bindings use the same catalog projection as the wrapper routes. */
// Transport implementations expose heterogeneous failures through the command boundary.
@Suppress("TooGenericExceptionCaught")
@OptIn(ExperimentalUuidApi::class)
private fun directSessionSlashCommands(
    scope: CoroutineScope,
    clientProvider: suspend () -> AppServerClient,
): AppServerSlashCommandApi {
    val catalog = SlashCommandCatalog()
    val unavailable = MutableStateFlow<Exception?>(null)
    val client = CompletableDeferred<AppServerClient>()
    scope.launch(start = CoroutineStart.UNDISPATCHED) {
        try {
            val connected = clientProvider()
            client.complete(connected)
            connected.events.collect { received ->
                val status = received.frame as? AppServerInboundFrame.UpdateDeviceStatus ?: return@collect
                catalog.ingest(
                    status.runtime.agentId.takeUnless { status.runtime.isAgentFree }?.let(::SlashCommandQuery),
                    status.deviceStatus, received.connectionGeneration,
                )
            }
        } catch (cancelled: CancellationException) {
            client.cancel(cancelled)
            throw cancelled
        } catch (failure: Exception) {
            client.completeExceptionally(failure)
            unavailable.value = failure
        }
    }
    return AppServerSlashCommandApi(
        execute = { command ->
            val connected = client.await()
            scope.coroutineContext.ensureActive()
            connected.executeCommand(command)
        },
        commandsProvider = { agent ->
            unavailable.value?.let { throw it }
            catalog.listing(SlashCommandQuery(agent)).first
        },
        requestId = { "slash-$it-${Uuid.random()}" },
    )
}
