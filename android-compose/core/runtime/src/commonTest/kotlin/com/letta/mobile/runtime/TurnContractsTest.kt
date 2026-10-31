package com.letta.mobile.runtime

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class TurnContractsTest {

    @Test
    fun testToolCallIdValidation() {
        val ex1 = assertFailsWith<IllegalArgumentException> {
            ToolCallId("")
        }
        assertEquals("ToolCallId cannot be blank.", ex1.message)

        val ex2 = assertFailsWith<IllegalArgumentException> {
            ToolCallId("   ")
        }
        assertEquals("ToolCallId cannot be blank.", ex2.message)

        val validId = ToolCallId("valid-id")
        assertEquals("valid-id", validId.value)
        assertEquals("valid-id", validId.toString())
    }

    @Test
    fun testToolNameValidation() {
        val ex1 = assertFailsWith<IllegalArgumentException> {
            ToolName("")
        }
        assertEquals("ToolName cannot be blank.", ex1.message)

        val ex2 = assertFailsWith<IllegalArgumentException> {
            ToolName("   ")
        }
        assertEquals("ToolName cannot be blank.", ex2.message)

        val validName = ToolName("valid-name")
        assertEquals("valid-name", validName.value)
        assertEquals("valid-name", validName.toString())
    }

    @Test
    fun testToolApprovalIdValidation() {
        val ex1 = assertFailsWith<IllegalArgumentException> {
            ToolApprovalId("")
        }
        assertEquals("ToolApprovalId cannot be blank.", ex1.message)

        val ex2 = assertFailsWith<IllegalArgumentException> {
            ToolApprovalId("   ")
        }
        assertEquals("ToolApprovalId cannot be blank.", ex2.message)

        val validId = ToolApprovalId("valid-approval")
        assertEquals("valid-approval", validId.value)
        assertEquals("valid-approval", validId.toString())
    }
}
