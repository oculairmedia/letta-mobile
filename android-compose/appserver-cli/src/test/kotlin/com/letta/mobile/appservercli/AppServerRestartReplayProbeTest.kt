package com.letta.mobile.appservercli

import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test

/**
 * Executable restart/replay probe (letta-mobile-lgns8.15).
 *
 * Runs against a REAL local Letta Code App Server driven over the production
 * transport, so it is gated on the environment (OpenRouter key + a local
 * letta-code install) and skips in CI where those are absent. When it runs it
 * regenerates the headline observations behind `restart-replay-evidence.json`
 * and asserts they still hold, keeping the committed evidence honest rather than
 * hand-authored. Set OPENROUTER_API_KEY (and optionally LETTA_CODE_NODE /
 * LETTA_CODE_JS / LETTA_CODE_PROBE_MODEL) to enable it.
 */
class AppServerRestartReplayProbeTest {
    @Test
    fun `real app server restart preserves agent and does not dedupe replayed client_message_id`() {
        val backendDir = Files.createTempDirectory("lgns815-probe")
        val config = AppServerRestartReplayProbe.Config.fromEnv(backendDir)
        assumeTrue(config != null) {
            "skipping live probe: set OPENROUTER_API_KEY and install letta-code (LETTA_CODE_JS) to run it"
        }

        val observation = runBlocking { AppServerRestartReplayProbe(config!!).run() }

        // Durability: reattaching after a process restart must not recreate the agent/conversation.
        assertFalse(observation.reattachRecreatedAgentAfterRestart) { "agent must survive process restart" }
        assertFalse(observation.reattachRecreatedConversationAfterRestart) { "conversation must survive process restart" }
        // Idempotency: resending the SAME client_message_id after restart duplicates it (server does not dedupe).
        assertEquals(2, observation.clientMessageIdCountAfterResend) {
            "server must NOT deduplicate a replayed client_message_id across restart (duplicate committed)"
        }

        // Keep the committed evidence honest: its recorded observations must match this live run.
        val evidence = AppServerRestartReplayEvidence.parse(
            javaClass.getResourceAsStream("/appserver/restart-replay-evidence.json")!!
                .bufferedReader().use { it.readText() },
        )
        assertFalse(evidence.identityScopes.clientMessageId.serverDeduplicated) {
            "evidence claims server does not dedupe client_message_id; live run confirmed"
        }
        assertFalse(evidence.durability.processRestart.reattachCreatedFlags.agent)
        assertFalse(evidence.durability.processRestart.reattachCreatedFlags.conversation)
    }
}
