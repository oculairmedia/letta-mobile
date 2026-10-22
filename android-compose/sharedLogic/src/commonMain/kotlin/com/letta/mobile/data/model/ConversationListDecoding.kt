package com.letta.mobile.data.model

import com.letta.mobile.util.Telemetry
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement

/**
 * A host's conversation list, read row by row. A row this client cannot represent (the host's
 * agent-free conversations carry `agent_id: null`, which [Conversation] cannot hold) is skipped
 * rather than failing the whole list, so one odd row never leaves every conversation unlisted.
 * A payload that is not a list still fails as a whole.
 */
internal fun Json.decodeConversationList(payload: JsonElement): List<Conversation> {
    if (payload !is JsonArray) return decodeFromJsonElement(ListSerializer(Conversation.serializer()), payload)
    var skipped = 0
    var sample: String? = null
    val rows = payload.mapNotNull { row ->
        runCatching { decodeFromJsonElement(Conversation.serializer(), row) }
            .onFailure {
                skipped += 1
                if (sample == null) sample = it.message?.lineSequence()?.firstOrNull()
            }
            .getOrNull()
    }
    if (skipped > 0) {
        Telemetry.event(
            "ConversationList", "rows_skipped",
            "skipped" to skipped, "kept" to rows.size, "firstError" to sample.orEmpty(),
            level = Telemetry.Level.WARN,
        )
    }
    return rows
}
