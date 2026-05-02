package com.letta.mobile.ui.screens.chat

import com.letta.mobile.data.model.UiMessage
import com.letta.mobile.ui.common.GroupPosition
import org.junit.Assert.assertEquals
import org.junit.Test

class ChatMessageListScrollTest {

    @Test
    fun `first render item maps to first lazy item when not streaming`() {
        val items = listOf(single("m1", ts = "2026-04-20T12:00:00Z"))

        assertEquals(
            0,
            calculateLazyIndexForRenderItem(
                targetRenderIndex = 0,
                renderItems = items,
                isStreaming = false,
            ),
        )
    }

    @Test
    fun `streaming typing indicator adds one lazy item before messages`() {
        val items = listOf(single("m1", ts = "2026-04-20T12:00:00Z"))

        assertEquals(
            1,
            calculateLazyIndexForRenderItem(
                targetRenderIndex = 0,
                renderItems = items,
                isStreaming = true,
            ),
        )
    }

    @Test
    fun `target index includes previous message rows`() {
        val items = listOf(
            single("newest", ts = "2026-04-20T12:00:00Z"),
            single("older", ts = "2026-04-20T11:00:00Z"),
        )

        assertEquals(
            1,
            calculateLazyIndexForRenderItem(
                targetRenderIndex = 1,
                renderItems = items,
                isStreaming = false,
            ),
        )
    }

    @Test
    fun `date separator before target adds lazy item offset`() {
        val items = listOf(
            single("today", ts = "2026-04-20T12:00:00Z"),
            single("yesterday", ts = "2026-04-19T12:00:00Z"),
        )

        assertEquals(
            2,
            calculateLazyIndexForRenderItem(
                targetRenderIndex = 1,
                renderItems = items,
                isStreaming = false,
            ),
        )
    }

    @Test
    fun `multiple date separators before target accumulate offsets with streaming`() {
        val items = listOf(
            single("day3", ts = "2026-04-21T12:00:00Z"),
            single("day2", ts = "2026-04-20T12:00:00Z"),
            single("day1", ts = "2026-04-19T12:00:00Z"),
        )

        assertEquals(
            5,
            calculateLazyIndexForRenderItem(
                targetRenderIndex = 2,
                renderItems = items,
                isStreaming = true,
            ),
        )
    }

    @Test
    fun `run block boundary timestamp participates in date separator offsets`() {
        val items = listOf(
            runBlock("run1", ts = "2026-04-20T12:00:00Z"),
            single("older", ts = "2026-04-19T12:00:00Z"),
        )

        assertEquals(
            2,
            calculateLazyIndexForRenderItem(
                targetRenderIndex = 1,
                renderItems = items,
                isStreaming = false,
            ),
        )
    }

    private fun single(
        id: String,
        ts: String,
    ): ChatRenderItem.Single = ChatRenderItem.Single(
        message = message(id = id, ts = ts),
        groupPosition = GroupPosition.None,
    )

    private fun runBlock(
        runId: String,
        ts: String,
    ): ChatRenderItem.RunBlock = ChatRenderItem.RunBlock(
        runId = runId,
        messages = listOf(message(id = "$runId-message", ts = ts) to GroupPosition.None),
    )

    private fun message(
        id: String,
        ts: String,
    ) = UiMessage(
        id = id,
        role = "assistant",
        content = id,
        timestamp = ts,
    )
}
