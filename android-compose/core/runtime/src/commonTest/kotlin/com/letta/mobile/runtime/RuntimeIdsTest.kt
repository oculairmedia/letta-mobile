package com.letta.mobile.runtime

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class RuntimeIdsTest {

    @Test
    fun backendId_validatesAndToString() {
        val valid = BackendId("valid-id")
        assertEquals("valid-id", valid.toString())

        val ex1 = assertFailsWith<IllegalArgumentException> { BackendId("") }
        assertEquals("BackendId cannot be blank.", ex1.message)

        val ex2 = assertFailsWith<IllegalArgumentException> { BackendId("   ") }
        assertEquals("BackendId cannot be blank.", ex2.message)
    }

    @Test
    fun runtimeId_validatesAndToString() {
        val valid = RuntimeId("valid-id")
        assertEquals("valid-id", valid.toString())

        val ex1 = assertFailsWith<IllegalArgumentException> { RuntimeId("") }
        assertEquals("RuntimeId cannot be blank.", ex1.message)

        val ex2 = assertFailsWith<IllegalArgumentException> { RuntimeId("   ") }
        assertEquals("RuntimeId cannot be blank.", ex2.message)
    }

    @Test
    fun conversationId_validatesAndToString() {
        val valid = ConversationId("valid-id")
        assertEquals("valid-id", valid.toString())

        val ex1 = assertFailsWith<IllegalArgumentException> { ConversationId("") }
        assertEquals("ConversationId cannot be blank.", ex1.message)

        val ex2 = assertFailsWith<IllegalArgumentException> { ConversationId("   ") }
        assertEquals("ConversationId cannot be blank.", ex2.message)
    }

    @Test
    fun runId_validatesAndToString() {
        val valid = RunId("valid-id")
        assertEquals("valid-id", valid.toString())

        val ex1 = assertFailsWith<IllegalArgumentException> { RunId("") }
        assertEquals("RunId cannot be blank.", ex1.message)

        val ex2 = assertFailsWith<IllegalArgumentException> { RunId("   ") }
        assertEquals("RunId cannot be blank.", ex2.message)
    }

    @Test
    fun runtimeEventId_validatesAndToString() {
        val valid = RuntimeEventId("valid-id")
        assertEquals("valid-id", valid.toString())

        val ex1 = assertFailsWith<IllegalArgumentException> { RuntimeEventId("") }
        assertEquals("RuntimeEventId cannot be blank.", ex1.message)

        val ex2 = assertFailsWith<IllegalArgumentException> { RuntimeEventId("   ") }
        assertEquals("RuntimeEventId cannot be blank.", ex2.message)
    }

    @Test
    fun runtimeEventOffset_validatesAndToString() {
        val valid = RuntimeEventOffset(42L)
        assertEquals("42", valid.toString())

        val ex = assertFailsWith<IllegalArgumentException> { RuntimeEventOffset(-1L) }
        assertEquals("RuntimeEventOffset cannot be negative.", ex.message)
    }

    @Test
    fun epochMillis_validatesAndToString() {
        val valid = EpochMillis(1600000000000L)
        assertEquals("1600000000000", valid.toString())

        val ex = assertFailsWith<IllegalArgumentException> { EpochMillis(-1L) }
        assertEquals("EpochMillis cannot be negative.", ex.message)
    }
}
