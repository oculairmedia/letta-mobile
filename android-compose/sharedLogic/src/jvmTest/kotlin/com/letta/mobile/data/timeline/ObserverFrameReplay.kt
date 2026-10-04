package com.letta.mobile.data.timeline

import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.LettaMessage
import com.letta.mobile.data.runtime.AppServerRuntimeEventMapper
import com.letta.mobile.data.runtime.StreamTextFrameSource
import com.letta.mobile.data.runtime.turnStreamIdentityFor
import com.letta.mobile.data.timeline.snapshot.TimelineScope
import com.letta.mobile.data.transport.WsFrameMapper
import com.letta.mobile.data.transport.appserver.AppServerChannel
import com.letta.mobile.data.transport.appserver.AppServerProtocol
import com.letta.mobile.data.transport.iroh.RuntimeEventServerFrameMapper
import com.letta.mobile.runtime.BackendId
import com.letta.mobile.runtime.ConversationId
import com.letta.mobile.runtime.RuntimeId
import com.letta.mobile.runtime.TurnCommand
import com.letta.mobile.runtime.TurnInput
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * letta-mobile-dzt3g: the phone's observer path, run on raw lines. A line is a `stream_delta` wire
 * frame, bare or wrapped as `{"wire": {...}}` the way the captured turns record it. Each goes
 * through the decoder, [AppServerRuntimeEventMapper] and [RuntimeEventServerFrameMapper] with the
 * context `IrohObserverIngestor.projectPassiveObserverDelta` builds (one synthetic turn and run id
 * per conversation), then [WsFrameMapper], which is what the coordinator ingests.
 */
internal class ObserverFrameReplay(private val scope: TimelineScope) {

    /**
     * The host's role (letta-mobile-jdcoj): it stamps every stream frame with its logical message id
     * and text sequence before a viewer sees it, and the phone's mapper drops text that carries
     * none. A captured or hand-written line without a stamp gets one here; a stamped line passes.
     */
    private val hostStamp = turnStreamIdentityFor(null)
    private val mapper = AppServerRuntimeEventMapper()
    private val command = TurnCommand(
        backendId = BackendId("iroh-app-server"),
        runtimeId = RuntimeId("iroh-observer"),
        agentId = AgentId(scope.agentId.orEmpty()),
        conversationId = ConversationId(scope.conversationId),
        input = TurnInput.UserMessage(localMessageId = "iroh-observer-${scope.conversationId}", text = ""),
    )

    fun messages(lines: List<String>): List<LettaMessage> = lines.filter(String::isNotBlank).flatMap(::messagesOf)

    private fun messagesOf(line: String): List<LettaMessage> {
        val wire = hostStamp.stamp(wireOf(line), StreamTextFrameSource.CumulativeSnapshot) ?: return emptyList()
        val received = AppServerProtocol.decodeFrame(wire, AppServerChannel.Stream)
        return mapper.map(command, received).flatMap { draft ->
            RuntimeEventServerFrameMapper.map(
                draft.payload,
                RuntimeEventServerFrameMapper.Context(
                    agentId = draft.agentId?.value ?: scope.agentId.orEmpty(),
                    conversationId = draft.conversationId?.value ?: scope.conversationId,
                    turnId = "iroh-observer-turn-${scope.conversationId}",
                    runId = draft.runId?.value ?: "iroh-observer-run-${scope.conversationId}",
                ),
            )
        }.mapNotNull(WsFrameMapper::toLettaMessage)
    }

    companion object {
        /** The bare wire frame of a recorded line. */
        fun wireOf(line: String): String {
            val root = Json.parseToJsonElement(line).jsonObject
            return (root["wire"] as? JsonObject ?: root).toString()
        }

        /** The scope a captured wire line was recorded under, or null when it names none. */
        fun scopeOf(line: String, backend: String = "backend"): TimelineScope? {
            val runtime = Json.parseToJsonElement(wireOf(line)).jsonObject["runtime"] as? JsonObject ?: return null
            val agent = runtime["agent_id"]?.jsonPrimitive?.contentOrNull ?: return null
            val conversation = runtime["conversation_id"]?.jsonPrimitive?.contentOrNull ?: return null
            return TimelineScope(backend, conversation, agent)
        }
    }
}
