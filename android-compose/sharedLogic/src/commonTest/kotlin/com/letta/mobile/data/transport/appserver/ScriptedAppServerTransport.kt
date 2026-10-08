package com.letta.mobile.data.transport.appserver

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/**
 * A wire-level fake App Server for workspace-command tests (letta-mobile-bzvro.24–.26): every
 * command is encoded exactly as the real transport would send it, recorded in [sent], and
 * answered with the raw JSON frames [respond] returns, decoded as the real transport decodes them.
 */
internal class ScriptedAppServerTransport(
    private val respond: (JsonObject) -> List<String> = { emptyList() },
) : AppServerTransport {
    private val control = MutableSharedFlow<AppServerReceivedFrame>(extraBufferCapacity = 64)
    override val controlFrames: MutableSharedFlow<AppServerReceivedFrame> = control
    override val streamFrames: MutableSharedFlow<AppServerReceivedFrame> = MutableSharedFlow(extraBufferCapacity = 64)
    override val isConnected: MutableStateFlow<Boolean> = MutableStateFlow(true)

    /** Every command sent, as the JSON object that went on the wire. */
    val sent = mutableListOf<JsonObject>()

    /** Every command sent, as the exact text that went on the wire. */
    val sentText = mutableListOf<String>()

    override suspend fun sendControl(command: AppServerCommand) {
        val text = AppServerProtocol.encodeCommand(command)
        sentText += text
        val json = AppServerProtocol.json.parseToJsonElement(text).jsonObject
        sent += json
        respond(json).forEach { frame -> control.emit(AppServerProtocol.decodeFrame(frame)) }
    }

    /** An unsolicited server frame, such as a `memory_updated` push. */
    suspend fun push(rawJson: String) {
        control.emit(AppServerProtocol.decodeFrame(rawJson))
    }
}

/** The command's `request_id`, for building its scripted answer. */
internal val JsonObject.requestId: String
    get() = (this["request_id"] as? kotlinx.serialization.json.JsonPrimitive)?.content.orEmpty()
