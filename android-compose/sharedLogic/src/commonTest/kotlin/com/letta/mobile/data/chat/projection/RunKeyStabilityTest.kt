package com.letta.mobile.data.chat.projection

import com.letta.mobile.data.model.UiMessage
import com.letta.mobile.data.model.UiToolCall
import com.letta.mobile.ui.common.GroupPosition
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A run's render key is `run-<runId>` from its first step to its last, so the list appends to its row
 * instead of rebuilding it: also when echo compaction drops one of its messages (a step repeating an
 * older reply's text), which used to name the block by its newest message, a new key every step.
 */
class RunKeyStabilityTest {
    private val older = "An earlier reply long enough to count as an echo when it is repeated later on."

    private val history = listOf(
        UiMessage(id = "u0", role = "user", content = "First question", timestamp = "2026-11-17T09:00:00Z"),
        UiMessage(id = "a0", role = "assistant", content = older, timestamp = "2026-11-17T09:00:01Z", runId = "r0"),
        UiMessage(id = "u1", role = "user", content = "Second question", timestamp = "2026-11-17T09:00:02Z"),
    )

    private fun step(index: Int) = UiMessage(
        id = "s$index",
        role = "assistant",
        content = "",
        timestamp = "2026-11-17T09:00:${(10 + index).toString().padStart(2, '0')}Z",
        runId = "r1",
        toolCalls = listOf(UiToolCall(name = "Bash", arguments = "{}", result = "ok", status = "success", toolCallId = "c$index")),
    )

    private fun runKey(messages: List<UiMessage>): String =
        groupMessagesForRender(messages.asReversed().map { it to GroupPosition.None })
            .single { item -> item.messagesInOrder().any { it.runId == "r1" } }
            .key

    @Test
    fun aRunOpeningWithAnEchoKeepsItsKeyAsStepsLand() {
        val opening = UiMessage(id = "s1", role = "assistant", content = older, timestamp = "2026-11-17T09:00:11Z", runId = "r1")
        var messages = history + opening + step(2)
        assertEquals("run-r1", runKey(messages))
        messages = messages + step(3)
        assertEquals("run-r1", runKey(messages))
        messages = messages + step(4)
        assertEquals("run-r1", runKey(messages))
    }

    @Test
    fun aRunOfStepsKeepsItsKeyFromItsFirstStep() {
        var messages = history + step(1)
        assertEquals("run-r1", runKey(messages))
        messages = messages + step(2)
        assertEquals("run-r1", runKey(messages))
    }
}
