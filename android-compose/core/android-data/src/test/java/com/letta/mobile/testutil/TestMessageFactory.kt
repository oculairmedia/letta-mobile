package com.letta.mobile.testutil

import com.letta.mobile.data.model.UserMessage
import kotlinx.serialization.json.JsonPrimitive

object TestMessageFactory {
    fun userMessage(id: String = "message-1", content: String = "hello") = UserMessage(
        id = id,
        contentRaw = JsonPrimitive(content),
    )
}
