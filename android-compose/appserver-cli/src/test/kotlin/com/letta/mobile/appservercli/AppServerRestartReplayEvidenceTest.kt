package com.letta.mobile.appservercli

import java.nio.charset.StandardCharsets
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Always-on gate for the observed restart/replay evidence (letta-mobile-lgns8.15).
 *
 * Runs in CI via `:appserver-cli:test` without any live server or API key. It
 * pins the letta-code version the evidence was captured against (incompatible
 * versions fail the gate) and enforces the internal consistency of the derived
 * reconciliation rules against the observed identity scopes, so the policy that
 * lgns8.5 consumes can never silently drift from the observations.
 */
class AppServerRestartReplayEvidenceTest {
    @Test
    fun `committed evidence is internally consistent and version-pinned`() {
        val raw = readResource("/appserver/restart-replay-evidence.json")
        val evidence = AppServerRestartReplayEvidence.parse(raw)

        val problems = evidence.validate(AppServerRestartReplayEvidence.PINNED_LETTA_CODE_VERSION)
        assertTrue(problems.isEmpty()) { "restart-replay evidence violated invariants:\n" + problems.joinToString("\n") }
    }

    private fun readResource(path: String): String {
        val stream = javaClass.getResourceAsStream(path) ?: error("missing resource $path")
        return stream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
    }
}
