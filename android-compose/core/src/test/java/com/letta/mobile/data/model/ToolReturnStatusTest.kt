package com.letta.mobile.data.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression coverage for the tool-return status classifier (bead
 * `letta-mobile-o9ce`). The empirical CLI survey showed the Letta server
 * emits exactly two values for Bash-grade tool calls (`success`, `error`),
 * so the rule is **explicit-error-whitelist** — anything not "error" must
 * NOT paint the row red.
 */
class ToolReturnStatusTest {

    @Test
    fun `null status is not error`() {
        assertFalse(ToolReturnStatus.isError(null))
    }

    @Test
    fun `success status is not error`() {
        assertFalse(ToolReturnStatus.isError("success"))
    }

    @Test
    fun `error status is error`() {
        assertTrue(ToolReturnStatus.isError("error"))
    }

    @Test
    fun `unknown status values are not error`() {
        // Defensive: any unknown server value must not flip the icon to red.
        // Add explicit known error values to the constants object instead.
        assertFalse(ToolReturnStatus.isError("completed"))
        assertFalse(ToolReturnStatus.isError("ok"))
        assertFalse(ToolReturnStatus.isError("done"))
        assertFalse(ToolReturnStatus.isError(""))
        assertFalse(ToolReturnStatus.isError("SUCCESS"))  // case-sensitive on purpose
    }

    @Test
    fun `error matching is exact and case-sensitive`() {
        // Pin the contract: server value must be the literal lowercase "error".
        // If the server starts emitting different casings, that's a server
        // contract change worth catching at this test rather than silently
        // mis-painting rows.
        assertFalse(ToolReturnStatus.isError("ERROR"))
        assertFalse(ToolReturnStatus.isError("Error"))
        assertFalse(ToolReturnStatus.isError("err"))
        assertFalse(ToolReturnStatus.isError("failed"))
    }

    @Test
    fun `canonical constants match the server contract`() {
        assertTrue(ToolReturnStatus.SUCCESS == "success")
        assertTrue(ToolReturnStatus.ERROR == "error")
    }
}
