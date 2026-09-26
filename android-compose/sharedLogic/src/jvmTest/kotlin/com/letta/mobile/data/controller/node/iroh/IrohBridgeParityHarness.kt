package com.letta.mobile.data.controller.node.iroh

import com.letta.mobile.data.controller.AppServerController
import com.letta.mobile.data.controller.node.FakeAppServerController
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.transport.appserver.AppServerProtocol
import com.letta.mobile.data.transport.appserver.AppServerReceivedFrame
import com.letta.mobile.data.transport.appserver.AppServerRuntimeScope
import com.letta.mobile.data.transport.iroh.IrohFrameCodec
import com.letta.mobile.runtime.BackendId
import com.letta.mobile.runtime.ConversationId
import com.letta.mobile.runtime.RuntimeEventDraft
import com.letta.mobile.runtime.RuntimeId
import com.letta.mobile.runtime.RuntimeRunStatus
import com.letta.mobile.runtime.TurnCommand
import com.letta.mobile.runtime.TurnInput
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.sync.Mutex
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

/** How an engine ended a turn, how much virtual time it took, and whether it still holds the lease. */
internal data class EngineOutcome(
    val status: RuntimeRunStatus?,
    val reason: String?,
    val runId: String?,
    val elapsedMs: Long,
    val busyAfter: Boolean = false,
)

internal fun turnCommandFor(runtime: AppServerRuntimeScope, clientMessageId: String) = TurnCommand(
    backendId = BackendId("iroh-node-server"),
    runtimeId = RuntimeId("iroh-node:${runtime.agentId}:${runtime.conversationId}"),
    agentId = AgentId(runtime.agentId),
    conversationId = ConversationId(runtime.conversationId),
    input = TurnInput.UserMessage(localMessageId = clientMessageId, text = "<redacted>"),
)

/**
 * A controller whose turns come from [runTurn]; every other call is the shared fake. [frames] is
 * what [AppServerController.observeRuntimeFrames] reads (the App Server the turns run against).
 */
internal fun controllerRunning(
    frames: Flow<AppServerReceivedFrame>? = null,
    turns: (TurnCommand) -> Flow<RuntimeEventDraft>,
): AppServerController =
    object : AppServerController by FakeAppServerController() {
        override fun runTurn(command: TurnCommand): Flow<RuntimeEventDraft> = turns(command)

        override fun observeRuntimeFrames(runtime: AppServerRuntimeScope): Flow<AppServerReceivedFrame>? =
            frames?.filter { it.frame.runtime?.conversationId == runtime.conversationId }
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
        relayTurn(controller, command, protocol) { error -> throw AssertionError("relayed turn failed", error) }
    }

    private fun parse(frame: String): JsonObject = AppServerProtocol.json.parseToJsonElement(frame).jsonObject
}
