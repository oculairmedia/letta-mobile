package com.letta.mobile.data.chat.projection

import com.letta.mobile.data.model.UiMessage
import kotlin.test.Test
import kotlin.test.assertEquals

class ChatMessageListChangeTest {
    private fun createMessage(id: String) = UiMessage(
        id = id,
        role = "assistant",
        content = "message $id",
        timestamp = "2024-01-01T00:00:00Z"
    )

    @Test
    fun testNone_IdenticalReference() {
        val list = listOf(createMessage("1"))
        assertEquals(ChatMessageListChange.None, ChatMessageListChange.compute(list, list))
    }

    @Test
    fun testNone_EmptyLists() {
        assertEquals(ChatMessageListChange.None, ChatMessageListChange.compute(emptyList(), emptyList()))
    }

    @Test
    fun testNone_EqualLists() {
        val prev = listOf(createMessage("1"), createMessage("2"))
        val next = listOf(createMessage("1"), createMessage("2"))
        assertEquals(ChatMessageListChange.None, ChatMessageListChange.compute(prev, next))
    }

    @Test
    fun testFull_EmptyToNonEmpty() {
        val next = listOf(createMessage("1"))
        assertEquals(ChatMessageListChange.Full, ChatMessageListChange.compute(emptyList(), next))
    }

    @Test
    fun testFull_NonEmptyToEmpty() {
        val prev = listOf(createMessage("1"))
        assertEquals(ChatMessageListChange.Full, ChatMessageListChange.compute(prev, emptyList()))
    }

    @Test
    fun testReplaceTail_SameSize_OnlyLastDiffers() {
        val prev = listOf(createMessage("1"), createMessage("2"))
        val next = listOf(createMessage("1"), createMessage("2_replaced"))
        assertEquals(ChatMessageListChange.ReplaceTail, ChatMessageListChange.compute(prev, next))
    }

    @Test
    fun testFull_SameSize_NonLastDiffers() {
        val prev = listOf(createMessage("1"), createMessage("2"))
        val next = listOf(createMessage("1_replaced"), createMessage("2"))
        assertEquals(ChatMessageListChange.Full, ChatMessageListChange.compute(prev, next))
    }

    @Test
    fun testAppendTail_NextIsOneLarger_SamePrefix() {
        val prev = listOf(createMessage("1"))
        val next = listOf(createMessage("1"), createMessage("2"))
        assertEquals(ChatMessageListChange.AppendTail, ChatMessageListChange.compute(prev, next))
    }

    @Test
    fun testFull_NextIsOneLarger_DifferentPrefix() {
        val prev = listOf(createMessage("1"))
        val next = listOf(createMessage("1_different"), createMessage("2"))
        assertEquals(ChatMessageListChange.Full, ChatMessageListChange.compute(prev, next))
    }

    @Test
    fun testFull_NextIsTwoLarger() {
        val prev = listOf(createMessage("1"))
        val next = listOf(createMessage("1"), createMessage("2"), createMessage("3"))
        assertEquals(ChatMessageListChange.Full, ChatMessageListChange.compute(prev, next))
    }
}
