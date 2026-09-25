package com.letta.mobile.data.controller.node.iroh

import com.letta.mobile.data.transport.appserver.AppServerChannel
import com.letta.mobile.data.transport.appserver.AppServerClient
import com.letta.mobile.data.transport.appserver.AppServerCommand
import com.letta.mobile.data.transport.appserver.AppServerInboundFrame
import com.letta.mobile.data.transport.appserver.AppServerProtocol
import com.letta.mobile.data.transport.appserver.AppServerReceivedFrame
import com.letta.mobile.data.transport.appserver.AppServerRuntimeScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

/*
 * letta-mobile-qygvv.14: a recorded App Server turn replayed as a scripted server. The recording is
 * the chronological log of what the server sent; where the server waited on the client (an approval
 * answer, an abort, a resume_queue, an external tool result) the replay waits for that command too,
 * so a client that never answers stalls exactly as it would against the live server.
 */

/** A client command the recorded server waited on before sending the rest of the turn. */
internal enum class ReplayGate { Input, Abort, ResumeQueue, ExternalToolResponse }

/** The server's reply to one gated command (null when it has none) and the frames that follow it. */
internal class ReplayStep(val gate: ReplayGate, val reply: String?, val frames: List<String>)

/** Splits a recording into the steps the replay releases one client command at a time. */
internal object ReplayScript {
    private val REPLY_GATES = mapOf(
        "input_accepted" to ReplayGate.Input,
        "abort_message_response" to ReplayGate.Abort,
        "resume_queue_response" to ReplayGate.ResumeQueue,
    )

    /** Frames after this type wait for the client's answer to it. */
    private const val EXTERNAL_TOOL_REQUEST = "external_tool_call_request"

    /** Types that are the server's reply to a client command, never a turn event. */
    val replyTypes: Set<String> get() = REPLY_GATES.keys

    fun steps(ackJson: String, frames: List<String>): List<ReplayStep> {
        val steps = mutableListOf<ReplayStep>()
        var gate = ReplayGate.Input
        var reply: String? = ackJson
        var pending = mutableListOf<String>()
        fun close(nextGate: ReplayGate, nextReply: String?) {
            steps += ReplayStep(gate, reply, pending)
            gate = nextGate
            reply = nextReply
            pending = mutableListOf()
        }
        for (frame in frames) {
            val type = typeOf(frame)
            val replyGate = REPLY_GATES[type]
            if (replyGate != null) {
                close(replyGate, frame)
                continue
            }
            pending += frame
            if (type == EXTERNAL_TOOL_REQUEST) close(ReplayGate.ExternalToolResponse, null)
        }
        close(ReplayGate.Input, null)
        return steps
    }

    fun typeOf(frame: String): String? =
        (AppServerProtocol.json.parseToJsonElement(frame).jsonObject["type"] as? JsonPrimitive)?.contentOrNull
}

/**
 * An App Server that acknowledges the turn's input with [ackJson] and then streams [frames] as
 * recorded, decoded by the production [AppServerProtocol.decodeFrame], releasing each gated step
 * only when the client sends the command it waits on. Every command is kept in [commands].
 */
internal class RecordedAppServerClient(
    ackJson: String,
    frames: List<String>,
    scope: CoroutineScope,
) : AppServerClient {
    private val inbound = MutableSharedFlow<AppServerReceivedFrame>(extraBufferCapacity = 256)
    private val outbound = Channel<String>(Channel.UNLIMITED)
    private val steps = ArrayDeque(ReplayScript.steps(ackJson, frames))
    override val events: Flow<AppServerReceivedFrame> = inbound

    /** Commands the client sent, in order. */
    val commands = mutableListOf<AppServerCommand>()

    init {
        scope.launch { for (frame in outbound) inbound.emit(AppServerProtocol.decodeFrame(frame, AppServerChannel.Stream)) }
    }

    override suspend fun runtimeStart(command: AppServerCommand.RuntimeStart): AppServerInboundFrame.RuntimeStartResponse =
        AppServerInboundFrame.RuntimeStartResponse(
            requestId = command.requestId,
            success = true,
            runtime = AppServerRuntimeScope(requireNotNull(command.agentId), requireNotNull(command.conversationId)),
        )

    override suspend fun input(command: AppServerCommand.Input) {
        commands += command
    }

    override suspend fun inputAwaitingAcceptance(command: AppServerCommand.Input): AppServerInboundFrame.InputAccepted {
        commands += command
        val ack = release(ReplayGate.Input) as? AppServerInboundFrame.InputAccepted
            ?: error("the recording has no input_accepted for ${command.payload}")
        return ack.copy(requestId = requireNotNull(command.requestId))
    }

    override suspend fun sync(command: AppServerCommand.Sync): AppServerInboundFrame.SyncResponse =
        AppServerInboundFrame.SyncResponse(requestId = command.requestId.orEmpty(), runtime = command.runtime, success = true)

    override suspend fun abort(command: AppServerCommand.AbortMessage): AppServerInboundFrame.AbortMessageResponse {
        commands += command
        val response = release(ReplayGate.Abort) as? AppServerInboundFrame.AbortMessageResponse
            ?: error("the recording has no abort_message_response")
        return response.copy(requestId = command.requestId.orEmpty())
    }

    override suspend fun resumeQueue(command: AppServerCommand.ResumeQueue): AppServerInboundFrame.ResumeQueueResponse {
        commands += command
        val response = release(ReplayGate.ResumeQueue) as? AppServerInboundFrame.ResumeQueueResponse
            ?: error("the recording has no resume_queue_response")
        return response.copy(requestId = command.requestId.orEmpty())
    }

    override suspend fun adminRpc(command: AppServerCommand.AdminRpc): AppServerInboundFrame.AdminRpcResponse =
        AppServerInboundFrame.AdminRpcResponse(requestId = command.requestId, success = true, result = null)

    override suspend fun sendExternalToolResponse(command: AppServerCommand.ExternalToolCallResponse) {
        commands += command
        release(ReplayGate.ExternalToolResponse)
    }

    /**
     * The next step waits on [gate]: stream its frames (the server answers first, then continues)
     * and return its decoded reply. A command the recording did not wait on releases nothing.
     */
    private fun release(gate: ReplayGate): AppServerInboundFrame? {
        if (steps.firstOrNull()?.gate != gate) return null
        val step = steps.removeFirst()
        step.frames.forEach { outbound.trySend(it) }
        return step.reply?.let { AppServerProtocol.decodeFrame(it, AppServerChannel.Control).frame }
    }
}
