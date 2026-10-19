package com.letta.mobile.data.chat.branch

/**
 * Letta-code's conversation-branching slash commands, as the composer types them.
 *
 * letta-code (0.29.12 through 0.34.8) handles `/fork` and `/btw` in its terminal UI only: neither is
 * in the App Server's `SUPPORTED_REMOTE_COMMANDS`, so `device_status.supported_commands` never lists
 * them and a `/fork` sent as a message would reach the agent as text. The client runs them itself,
 * through [ConversationBranchController], with letta-code's semantics:
 * - `/fork [name]`: forks the whole conversation (no message point), names the fork with the rest
 *   of the line when given (letta-code then writes `summary`), and switches to it.
 * - `/btw <question>`: forks the whole conversation and asks the question there, as a side branch;
 *   the user stays where they are. Parsed here; the side-answer surface is a follow-up.
 */
sealed interface BranchSlashCommand {
    /** `/fork [name]`. */
    data class Fork(val name: String?) : BranchSlashCommand

    /** `/btw <question>`. */
    data class Aside(val question: String) : BranchSlashCommand

    companion object {
        const val FORK = "fork"
        const val BTW = "btw"

        private val FORK_LINE = Regex("""^/fork(?:\s+([\s\S]+))?$""")
        private val BTW_LINE = Regex("""^/btw\s+([\s\S]+)$""")

        /** The command [text] is, or null when it is an ordinary message (letta-code's own patterns). */
        fun parse(text: String): BranchSlashCommand? {
            val line = text.trim()
            FORK_LINE.matchEntire(line)?.let { match ->
                return Fork(match.groupValues[1].trim().takeIf { it.isNotEmpty() })
            }
            return BTW_LINE.matchEntire(line)?.let { Aside(it.groupValues[1].trim()) }
        }
    }
}
