package com.letta.mobile.data.controller.node.iroh

import com.letta.mobile.data.controller.AppServerController
import com.letta.mobile.data.controller.node.FakeAppServerController
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.runtime.AppServerTurnEngine
import com.letta.mobile.data.runtime.runLifecycleStatus
import com.letta.mobile.data.runtime.turnEngineTerminalStatuses
import com.letta.mobile.data.transport.appserver.AppServerChannel
import com.letta.mobile.data.transport.appserver.AppServerClient
import com.letta.mobile.data.transport.appserver.AppServerCommand
import com.letta.mobile.data.transport.appserver.AppServerInboundFrame
import com.letta.mobile.data.transport.appserver.AppServerProtocol
import com.letta.mobile.data.transport.appserver.AppServerReceivedFrame
import com.letta.mobile.data.transport.appserver.AppServerRuntimeScope
import com.letta.mobile.data.transport.iroh.IrohFrameCodec
import com.letta.mobile.runtime.BackendId
import com.letta.mobile.runtime.ConversationId
import com.letta.mobile.runtime.RuntimeEventDraft
import com.letta.mobile.runtime.RuntimeEventPayload
import com.letta.mobile.runtime.RuntimeId
import com.letta.mobile.runtime.RuntimeRunStatus
import com.letta.mobile.runtime.TurnCommand
import com.letta.mobile.runtime.TurnInput
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.test.TestScope
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

/*
 * letta-mobile-qygvv.12 / qygvv.14: shared harness for the Iroh bridge parity gate. A recorded App
 * Server turn is replayed through (a) an engine directly and (b) the node's relay into a fake
 * phone, whose captured control + stream frames are then replayed into a second engine.
 */

internal enum class WireChannel { Control, Stream }

/** One frame the fake phone received, in arrival order across both channels. */
internal data class WireFrame(val channel: WireChannel, val json: JsonObject) {
    val type: String? get() = json.parityString("type")

    /** `stream_delta` frames are keyed by their delta's message_type, the rest by frame type. */
    val kind: String?
        get() = if (type == "stream_delta") json["delta"]?.jsonObject?.parityString("message_type") else type

    val loopStatus: String? get() = json["loop_status"]?.jsonObject?.parityString("status")
}

internal fun JsonObject.parityString(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

/** A recorded App Server turn: its `input_accepted` and every frame after it, as raw JSON. */
internal class AppServerRecording(val ackJson: String, val frames: List<String>) {
    val parsedFrames: List<JsonObject> = frames.map { AppServerProtocol.json.parseToJsonElement(it).jsonObject }
    val runtime: AppServerRuntimeScope =
        AppServerProtocol.json.decodeFromJsonElement(
            AppServerRuntimeScope.serializer(),
            AppServerProtocol.json.parseToJsonElement(ackJson).jsonObject.getValue("runtime"),
        )

    companion object {
        fun load(resource: String): AppServerRecording {
            val text = requireNotNull(AppServerRecording::class.java.classLoader.getResource(resource)) {
                "missing fixture $resource"
            }.readText()
            val lines = text.lines().filter { it.isNotBlank() }
            require(lines.first().contains("\"input_accepted\"")) { "$resource must start with input_accepted" }
            return AppServerRecording(lines.first(), lines.drop(1))
        }
    }
}

/**
 * An App Server that acknowledges the input with [ackJson] and then streams [frames] exactly as
 * recorded, decoded by the production [AppServerProtocol.decodeFrame].
 */
internal class RecordedAppServerClient(
    private val ackJson: String,
    private val frames: List<String>,
    private val scope: CoroutineScope,
) : AppServerClient {
    private val inbound = MutableSharedFlow<AppServerReceivedFrame>(extraBufferCapacity = 256)
    override val events: Flow<AppServerReceivedFrame> = inbound

    override suspend fun runtimeStart(command: AppServerCommand.RuntimeStart): AppServerInboundFrame.RuntimeStartResponse =
        AppServerInboundFrame.RuntimeStartResponse(
            requestId = command.requestId,
            success = true,
            runtime = AppServerRuntimeScope(requireNotNull(command.agentId), requireNotNull(command.conversationId)),
        )

    override suspend fun input(command: AppServerCommand.Input) = Unit

    override suspend fun inputAwaitingAcceptance(command: AppServerCommand.Input): AppServerInboundFrame.InputAccepted {
        val ack = AppServerProtocol.decodeFrame(ackJson, AppServerChannel.Control).frame as AppServerInboundFrame.InputAccepted
        // The App Server streams the turn after it answers the ack.
        scope.launch { frames.forEach { inbound.emit(AppServerProtocol.decodeFrame(it, AppServerChannel.Stream)) } }
        return ack.copy(requestId = requireNotNull(command.requestId))
    }

    override suspend fun sync(command: AppServerCommand.Sync): AppServerInboundFrame.SyncResponse =
        AppServerInboundFrame.SyncResponse(requestId = command.requestId.orEmpty(), runtime = command.runtime, success = true)

    override suspend fun abort(command: AppServerCommand.AbortMessage): AppServerInboundFrame.AbortMessageResponse =
        error("abort unused")

    override suspend fun adminRpc(command: AppServerCommand.AdminRpc): AppServerInboundFrame.AdminRpcResponse =
        AppServerInboundFrame.AdminRpcResponse(requestId = command.requestId, success = true, result = null)

    override suspend fun sendExternalToolResponse(command: AppServerCommand.ExternalToolCallResponse) = Unit
}

/** How an engine ended a turn, and how much virtual time it took. */
internal data class EngineOutcome(
    val status: RuntimeRunStatus?,
    val reason: String?,
    val runId: String?,
    val elapsedMs: Long,
)

internal suspend fun TestScope.runEngineOn(client: AppServerClient, command: TurnCommand): EngineOutcome {
    val engine = AppServerTurnEngine(client = client, turnIdleTimeoutMs = 600_000, nowMs = { testScheduler.currentTime })
    val startedAt = testScheduler.currentTime
    val drafts = engine.runTurn(command).toList()
    val terminal = drafts.lastOrNull { it.runLifecycleStatus() in turnEngineTerminalStatuses }
    return EngineOutcome(
        status = terminal?.runLifecycleStatus(),
        reason = (terminal?.payload as? RuntimeEventPayload.RunLifecycleChanged)?.reason,
        runId = terminal?.runId?.value,
        elapsedMs = testScheduler.currentTime - startedAt,
    )
}

internal fun turnCommandFor(runtime: AppServerRuntimeScope, clientMessageId: String) = TurnCommand(
    backendId = BackendId("iroh-node-server"),
    runtimeId = RuntimeId("iroh-node:${runtime.agentId}:${runtime.conversationId}"),
    agentId = AgentId(runtime.agentId),
    conversationId = ConversationId(runtime.conversationId),
    input = TurnInput.UserMessage(localMessageId = clientMessageId, text = "<redacted>"),
)

/** A controller whose turns come from [runTurn]; every other call is the shared fake. */
internal fun controllerRunning(turns: (TurnCommand) -> Flow<RuntimeEventDraft>): AppServerController =
    object : AppServerController by FakeAppServerController() {
        override fun runTurn(command: TurnCommand): Flow<RuntimeEventDraft> = turns(command)
    }

/**
 * The fake phone on the far side of the node: one ordered log of what reached its control and
 * stream channels, and the node-side relay objects that write to it.
 */
internal class FakePhoneLink(
    val runtime: AppServerRuntimeScope,
    val clientMessageId: String,
    val requestId: String?,
    writeScope: CoroutineScope,
) {
    val log = mutableListOf<WireFrame>()
    private val decoder = IrohFrameCodec.Decoder(
        IrohFrameCodec.DEFAULT_MAX_FRAME_BYTES,
        IrohFrameCodec.DEFAULT_MAX_REASSEMBLED_BYTES,
    )
    private val viewer = IrohViewerHandle(
        connectionId = "phone-1",
        sink = object : ViewerFrameSink {
            override suspend fun writeAll(bytes: ByteArray) {
                decoder.feed(bytes).forEach { log += WireFrame(WireChannel.Stream, parse(it)) }
            }
        },
        eventSeq = IrohEventSeqAllocator.newConnectionSeq(),
        streamWriteMutex = Mutex(),
        frameParts = { false },
        maxFrameBytes = IrohFrameCodec.DEFAULT_MAX_FRAME_BYTES,
    )
    val fanout = ConversationTurnFanout(
        conversationId = runtime.conversationId,
        runtime = runtime,
        viewersFor = { emptySet() },
        initiatorViewer = viewer,
        observerWrites = ObserverWriteQueue(writeScope),
    )
    val protocol = IrohRelayedTurnProtocol(
        runtime = runtime,
        requestId = requestId,
        clientMessageId = clientMessageId,
        fanout = fanout,
        writeControl = { frame -> log += WireFrame(WireChannel.Control, parse(frame)) },
        afterAccepted = { fanout.broadcastUserEcho(clientMessageId, "<redacted>", null) },
    )

    val control: List<WireFrame> get() = log.filter { it.channel == WireChannel.Control }
    val stream: List<WireFrame> get() = log.filter { it.channel == WireChannel.Stream }

    suspend fun relay(controller: AppServerController, command: TurnCommand) {
        relayTurn(controller, command, fanout, protocol) { error -> throw AssertionError("relayed turn failed", error) }
    }

    private fun parse(frame: String): JsonObject = AppServerProtocol.json.parseToJsonElement(frame).jsonObject
}
