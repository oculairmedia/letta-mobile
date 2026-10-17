package com.letta.mobile.data.subagents

/**
 * Who is doing the work: a Letta subagent or an external coding agent (letta-code 0.34).
 *
 * letta-mobile-zxk2n.8: the ONE classifier every subagent surface asks - the roster rows and the
 * timeline's dispatch and report rows - so an external agent's work is named the same way
 * everywhere and is never offered as a Letta conversation to open.
 */
enum class SubagentIdentity(val label: String?) {
    LETTA(null),
    CLAUDE_CODE("Claude Code"),
    CODEX("Codex"),
    ;

    /** Not a Letta agent: it has no Letta conversation to open beside its parent. */
    val isExternal: Boolean get() = this != LETTA
}

/**
 * letta-code dispatches an external coding agent with `subagent_type` claude-code or codex and gives
 * it a synthetic agent id with a `claude_` or `codex_` prefix; no other identity field rides the
 * wire. [ids] are the ids the caller knows (the child agent id, the dispatch's tool call id).
 */
fun subagentIdentityOf(subagentType: String?, ids: List<String?> = emptyList()): SubagentIdentity {
    val type = subagentType?.trim()?.lowercase().orEmpty()
    val knownIds = ids.mapNotNull { it?.lowercase() }
    return when {
        isClaudeCode(type, knownIds) -> SubagentIdentity.CLAUDE_CODE
        isCodex(type, knownIds) -> SubagentIdentity.CODEX
        else -> SubagentIdentity.LETTA
    }
}

private fun isClaudeCode(type: String, ids: List<String>): Boolean =
    CLAUDE_CODE_TYPES.any(type::startsWith) || ids.any { it.startsWith(CLAUDE_ID_PREFIX) }

private fun isCodex(type: String, ids: List<String>): Boolean =
    type.startsWith(CODEX_TYPE) || ids.any { it.startsWith(CODEX_ID_PREFIX) }

private val CLAUDE_CODE_TYPES = listOf("claude-code", "claude code")
private const val CLAUDE_ID_PREFIX = "claude_"
private const val CODEX_TYPE = "codex"
private const val CODEX_ID_PREFIX = "codex_"
