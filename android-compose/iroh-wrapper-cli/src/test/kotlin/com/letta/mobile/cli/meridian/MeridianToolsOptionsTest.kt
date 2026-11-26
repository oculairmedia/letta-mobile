package com.letta.mobile.cli.meridian

import com.letta.mobile.data.meridian.AgentToolsModePolicy
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MeridianToolsOptionsTest {
    @Test
    fun `the endpoint follows agent-tools-mode cli, as host default or per-agent override`() {
        fun policy(default: String, overrides: String? = null) = AgentToolsModePolicy.parse(default, overrides).getOrThrow()

        assertTrue(policy("cli").servesCli())
        assertTrue(policy("native", "agent-a=cli").servesCli())
        assertFalse(policy("native").servesCli())
        assertFalse(policy("meta", "agent-a=native").servesCli())
    }
}
