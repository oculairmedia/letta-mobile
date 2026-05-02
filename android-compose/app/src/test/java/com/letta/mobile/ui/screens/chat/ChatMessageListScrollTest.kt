package com.letta.mobile.ui.screens.chat

import com.letta.mobile.data.model.UiMessage
import com.letta.mobile.ui.common.GroupPosition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class ChatMessageListScrollTest {

    @Test
    fun `first render item maps after persistent typing slot when not streaming`() {
        val items = listOf(single("m1", ts = "2026-04-20T12:00:00Z"))

        assertEquals(
            1,
            calculateLazyIndexForRenderItem(
                targetRenderIndex = 0,
                renderItems = items,
            ),
        )
    }

    @Test
    fun `first render item maps after typing slot`() {
        val items = listOf(single("m1", ts = "2026-04-20T12:00:00Z"))

        assertEquals(
            1,
            calculateLazyIndexForRenderItem(
                targetRenderIndex = 0,
                renderItems = items,
            ),
        )
    }

    @Test
    fun `target index includes typing slot and previous message rows`() {
        val items = listOf(
            single("newest", ts = "2026-04-20T12:00:00Z"),
            single("older", ts = "2026-04-20T11:00:00Z"),
        )

        assertEquals(
            2,
            calculateLazyIndexForRenderItem(
                targetRenderIndex = 1,
                renderItems = items,
            ),
        )
    }

    @Test
    fun `date separator before target adds lazy item offset after typing slot`() {
        val items = listOf(
            single("today", ts = "2026-04-20T12:00:00Z"),
            single("yesterday", ts = "2026-04-19T12:00:00Z"),
        )

        assertEquals(
            3,
            calculateLazyIndexForRenderItem(
                targetRenderIndex = 1,
                renderItems = items,
            ),
        )
    }

    @Test
    fun `multiple date separators before target accumulate offsets`() {
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
            ),
        )
    }

    @Test
    fun `run block boundary timestamp participates in date separator offsets after typing slot`() {
        val items = listOf(
            runBlock("run1", ts = "2026-04-20T12:00:00Z"),
            single("older", ts = "2026-04-19T12:00:00Z"),
        )

        assertEquals(
            3,
            calculateLazyIndexForRenderItem(
                targetRenderIndex = 1,
                renderItems = items,
            ),
        )
    }

    @Test
    fun `auto-scroll signature changes when newest message content grows`() {
        val before = newestMessageAutoScrollSignature(
            listOf(message(id = "assistant", content = "hel")),
        )
        val after = newestMessageAutoScrollSignature(
            listOf(message(id = "assistant", content = "hello")),
        )

        assertNotEquals(before, after)
    }

    @Test
    fun `auto-scroll signature ignores older page additions when newest message is unchanged`() {
        val before = newestMessageAutoScrollSignature(
            listOf(message(id = "newest", content = "hello")),
        )
        val after = newestMessageAutoScrollSignature(
            listOf(
                message(id = "older", content = "old"),
                message(id = "newest", content = "hello"),
            ),
        )

        assertEquals(before, after)
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
        ts: String = "2026-04-20T12:00:00Z",
        content: String = id,
    ) = UiMessage(
        id = id,
        role = "assistant",
        content = content,
        timestamp = ts,
    )
}
