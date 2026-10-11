package com.letta.mobile.data.dreaming

import kotlinx.serialization.Serializable

/**
 * letta-mobile-iczm7: the dreaming admin_rpc contract shared by the Iroh host that serves it and
 * the clients that call it.
 *
 *  - `reflection.get` {agent_id, conversation_id?} -> {reflection_settings}
 *  - `reflection.set` {agent_id, conversation_id?, trigger, step_count, merge?, merge_instructions?,
 *    scope?} -> {reflection_settings, scope}
 *  - `reflection.run` {agent_id, conversation_id?, instruction?} -> [DreamRunStart]: letta-code's
 *    own `/dream` (`execute_command dream`), which on the local backend starts a local reflection.
 *
 * A missing conversation is the agent's `default` conversation. A host that predates
 * `reflection.run` answers "Unknown method"; one without a native App Server client answers
 * `capability_unavailable`.
 */
object DreamingRpc {
    const val GET: String = "reflection.get"
    const val SET: String = "reflection.set"
    const val RUN: String = "reflection.run"

    const val AGENT_ID: String = "agent_id"
    const val CONVERSATION_ID: String = "conversation_id"
    const val SCOPE: String = "scope"
    const val INSTRUCTION: String = "instruction"
    const val REFLECTION_SETTINGS: String = "reflection_settings"

    /** letta-code's slash command; `reflect` and `reflection` are its aliases. */
    const val COMMAND_ID: String = "dream"
    val COMMAND_IDS: Set<String> = setOf(COMMAND_ID, "reflect", "reflection")

    /** `--` ends letta-code's flags; everything after it is the dream's instruction. */
    fun commandArgs(instruction: String?): String? = instruction?.trim()?.takeIf { it.isNotEmpty() }?.let { "-- $it" }
}

/** What letta-code said when asked to dream; [DreamStartStatus] reads its text. */
enum class DreamStartStatus {
    Started,
    AlreadyRunning,
    NothingNew,
    MemoryOff,
    Failed,
}

/**
 * letta-mobile-iczm7: letta-code's answer to `/dream`. It arrives when the run is launched, not
 * when it finishes: the run itself shows up in the subagent registry and, when it changes memory,
 * as a reflection commit.
 */
@Serializable
data class DreamRunStart(
    val status: DreamStartStatus,
    /** letta-code's own sentence. */
    val output: String? = null,
) {
    val started: Boolean get() = status == DreamStartStatus.Started

    companion object {
        /** Reads `handleReflectCommand`'s replies (letta-code 0.33.6 and 0.34.8 alike). */
        fun parse(success: Boolean, output: String?): DreamRunStart {
            val text = output.orEmpty()
            val status = when {
                text.contains(ALREADY_RUNNING, ignoreCase = true) -> DreamStartStatus.AlreadyRunning
                text.contains(NOTHING_NEW, ignoreCase = true) -> DreamStartStatus.NothingNew
                text.contains(MEMORY_OFF, ignoreCase = true) -> DreamStartStatus.MemoryOff
                text.contains(FAILED, ignoreCase = true) || !success -> DreamStartStatus.Failed
                else -> DreamStartStatus.Started
            }
            return DreamRunStart(status, output?.trim()?.takeIf { it.isNotEmpty() })
        }

        private const val ALREADY_RUNNING = "already running"
        private const val NOTHING_NEW = "No new transcript content"
        private const val MEMORY_OFF = "needs the memory filesystem"
        private const val FAILED = "Failed to start"
    }
}
