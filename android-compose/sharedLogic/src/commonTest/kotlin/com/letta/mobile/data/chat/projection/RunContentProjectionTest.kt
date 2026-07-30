package com.letta.mobile.data.chat.projection

import com.letta.mobile.data.model.UiMessage
import com.letta.mobile.data.model.UiToolCall
import kotlin.test.Test
import kotlin.test.assertEquals

class RunContentProjectionTest {
    @Test
    fun classifiesReasoningToolsAndNarration() {
        val projection = projectRunContent(
            listOf(
                message("reason", "think", reasoning = true),
                message("tool", "", tools = listOf(UiToolCall("Read", "{}", null))),
                message("text", "done"),
            ),
        )

        assertEquals(listOf("reason"), projection.reasoning.map { it.id })
        assertEquals(listOf("Read"), projection.toolCalls.map { it.name })
        assertEquals(listOf("text"), projection.narration.map { it.id })
    }

    private fun message(
        id: String,
        content: String,
        reasoning: Boolean = false,
        tools: List<UiToolCall>? = null,
    ) = UiMessage(id, "assistant", content, "", isReasoning = reasoning, toolCalls = tools)
}
