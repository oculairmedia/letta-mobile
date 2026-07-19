package com.letta.mobile.data.transport

import kotlinx.serialization.json.Json

/**
 * Wire-level frame contracts for the admin-shim `/shim/v1/mobile` WebSocket.
 * Concrete declarations remain in the same package so the public API is unchanged.
 */
sealed interface ClientFrame {
    val v: Int
    val type: String
    val id: String
    val ts: String
}

/** Encodes a client frame with its concrete serializer and declared `type` field. */
fun ClientFrame.encodeJson(json: Json): String = when (this) {
    is HelloFrame -> json.encodeToString(HelloFrame.serializer(), this)
    is SendMessageFrame -> json.encodeToString(SendMessageFrame.serializer(), this)
    is UserActionFrame -> json.encodeToString(UserActionFrame.serializer(), this)
    is CancelFrame -> json.encodeToString(CancelFrame.serializer(), this)
    is SubscribeFrame -> json.encodeToString(SubscribeFrame.serializer(), this)
    is ByeFrame -> json.encodeToString(ByeFrame.serializer(), this)
    is CronListFrame -> json.encodeToString(CronListFrame.serializer(), this)
    is CronAddFrame -> json.encodeToString(CronAddFrame.serializer(), this)
    is CronGetFrame -> json.encodeToString(CronGetFrame.serializer(), this)
    is CronDeleteFrame -> json.encodeToString(CronDeleteFrame.serializer(), this)
    is CronDeleteAllFrame -> json.encodeToString(CronDeleteAllFrame.serializer(), this)
    is SubagentListFrame -> json.encodeToString(SubagentListFrame.serializer(), this)
    is SubagentTodosFrame -> json.encodeToString(SubagentTodosFrame.serializer(), this)
}
