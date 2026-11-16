package com.letta.mobile.data.controller.extras

import com.letta.mobile.data.controller.extras.messaging.IrohMessageOptions
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

internal fun MutableList<String>.addMessageOptions(options: IrohMessageOptions) {
    options.conversationId?.let { add("--conversation-id"); add(it) }
    if (options.conversationId == null) options.lane?.let { add("--lane"); add(it) }
    options.correlation?.let { addCorrelation(it) }
    options.correlationId?.let { add("--in-reply-to"); add(it) }
    options.replyTo?.let {
        add("--reply-correlation-id"); add(it.correlationId)
        add("--reply-expires-at"); add(it.expiresAt.toString())
    }
}

private fun MutableList<String>.addCorrelation(correlation: Map<String, String>) {
    if (canUseCorrelationFlags(correlation)) {
        correlation.forEach { (key, value) -> add("--correlation"); add("$key=$value") }
    } else {
        add("--correlation-json")
        add(JsonObject(correlation.mapValues { JsonPrimitive(it.value) }).toString())
    }
}

private fun canUseCorrelationFlags(correlation: Map<String, String>): Boolean {
    if (correlation.isEmpty()) return false
    return correlation.keys.all { it.isNotEmpty() && '=' !in it }
}
