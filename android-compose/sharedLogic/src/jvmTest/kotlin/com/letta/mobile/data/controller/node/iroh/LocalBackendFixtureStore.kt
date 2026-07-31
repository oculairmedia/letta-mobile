package com.letta.mobile.data.controller.node.iroh

import java.io.File
import java.util.Base64

/**
 * lgns8.9: a SYNTHESISED letta-code local-backend store for tests.
 *
 * Never fixture real user data: every id, transcript, and memory value here is
 * invented. The layout — not the content — is what the readers are pinned to,
 * and it mirrors what `/root/.letta/lc-local-backend` looks like on the live
 * host (sampled read-only):
 *
 * ```
 * agents/<agentId>.json
 * memfs/<agentId>/memory/system/<label>.md
 * conversations/<b64url("default:<agentId>")>/messages.jsonl
 * conversations/<b64url("default:<agentId>")>/system-prompt.json
 * runs/<runId>/run.json
 * runs/<runId>/steps.jsonl
 * runs/_archive/<runId>/run.json
 * ```
 */
internal object LocalBackendFixtureStore {
    const val AGENT_ID: String = "agent-1"
    const val RUN_ID: String = "run-1"
    const val ARCHIVED_RUN_ID: String = "run-archived"
    const val STEP_ID: String = "step-1"
    const val BLOCK_LABEL: String = "persona"
    const val BLOCK_VALUE: String = "fixture persona"
    const val SYSTEM_PROMPT: String = "fixture system prompt"

    /** The synthesised block id the readers must produce for [BLOCK_LABEL]. */
    val blockId: String get() = LocalBackendBlockReader.blockIdFor(AGENT_ID, BLOCK_LABEL)

    /** Build a complete fixture store under [root] and return it. */
    fun create(root: File): File {
        writeAgent(root, AGENT_ID, name = "Fixture Agent")
        writeBlock(root, AGENT_ID, BLOCK_LABEL, BLOCK_VALUE)
        writeConversation(root, AGENT_ID)
        writeRun(root, RUN_ID, archived = false)
        writeRun(root, ARCHIVED_RUN_ID, archived = true)
        return root
    }

    fun writeAgent(root: File, agentId: String, name: String) {
        val file = File(root, "agents/$agentId.json").apply { parentFile.mkdirs() }
        file.writeText(
            """{"id":"$agentId","name":"$name","model":"lmstudio/opus-4-7","model_settings":{}}""",
        )
    }

    fun writeBlock(root: File, agentId: String, label: String, value: String) {
        val file = File(root, "memfs/$agentId/memory/system/$label.md").apply { parentFile.mkdirs() }
        file.writeText(value)
    }

    fun writeConversation(root: File, agentId: String) {
        val dir = conversationDir(root, agentId).apply { mkdirs() }
        File(dir, "system-prompt.json").writeText("""{"content":"$SYSTEM_PROMPT"}""")
        File(dir, "messages.jsonl").writeText(
            """{"id":"m-1","role":"user","parts":[{"type":"text","text":"hello"}]}""" + "\n",
        )
    }

    /**
     * One run with the wire shape `run.json` already carries on disk, plus a
     * single step. [archived] writes it under `runs/_archive/` so tests can pin
     * "the live walk never descends the archive, but get still resolves it".
     */
    fun writeRun(root: File, runId: String, archived: Boolean, agentId: String = AGENT_ID) {
        val base = if (archived) File(root, "runs/_archive/$runId") else File(root, "runs/$runId")
        base.mkdirs()
        File(base, "run.json").writeText(
            """{"id":"$runId","agent_id":"$agentId","conversation_id":"conv-1","status":"completed",""" +
                """"background":false,"stop_reason":"end_turn","created_at":"2026-07-2${if (archived) 0 else 1}""" +
                """T20:01:42.045Z","message_ids":["m-1"],"num_steps":1}""",
        )
        File(base, "steps.jsonl").writeText(
            """{"id":"$STEP_ID","run_id":"$runId","agent_id":"$agentId",""" +
                """"created_at":"2026-07-21T20:01:46.246Z","stop_reason":"end_turn"}""" + "\n",
        )
    }

    fun conversationDir(root: File, agentId: String): File =
        File(File(root, "conversations"), b64Url("default:$agentId"))

    private fun b64Url(value: String): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(value.toByteArray(Charsets.UTF_8))
}
