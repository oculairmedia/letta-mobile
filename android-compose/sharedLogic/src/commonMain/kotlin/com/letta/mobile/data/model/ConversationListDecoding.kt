package com.letta.mobile.data.model

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement

/**
 * A host's conversation list, read row by row. A row this client cannot represent (the host's
 * agent-free conversations carry `agent_id: null`, which [Conversation] cannot hold) is skipped
 * rather than failing the whole list, so one odd row never leaves every conversation unlisted;
 * [onSkipped] hears how many rows were left out, how many stayed and why the first was refused. A payload that is not a list still fails as a whole.
 */
internal fun Json.decodeConversationList(
    payload: JsonElement,
    onSkipped: (skipped: Int, kept: Int, firstError: String?) -> Unit = { _, _, _ -> },
): List<Conversation> {
    if (payload !is JsonArray) return decodeFromJsonElement(ListSerializer(Conversation.serializer()), payload)
    var skipped = 0
    var firstError: String? = null
    val rows = payload.mapNotNull { row ->
        runCatching { decodeFromJsonElement(Conversation.serializer(), row) }
            .onFailure {
                skipped += 1
                if (firstError == null) firstError = it.message?.lineSequence()?.firstOrNull()
            }
            .getOrNull()
    }
    if (skipped > 0) onSkipped(skipped, rows.size, firstError)
    return rows
}
