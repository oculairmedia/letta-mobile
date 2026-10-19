package com.letta.mobile.data.composer

/**
 * One agent referenced in a draft or a sent message: [name] as shown, [agentId] as the agent's
 * messaging tools address it, and the `[start, end)` span of the whole reference in the text.
 */
data class AgentMentionRef(
    val name: String,
    val agentId: String,
    val start: Int,
    val end: Int,
)

/** An agent the composer can mention: its id and display name. */
data class MentionableAgent(val id: String, val name: String)

/**
 *  (G31): `@agent` mentions.
 *
 * Neither letta-code nor the Letta server has a mention syntax: an agent reaches another agent
 * through its messaging tools (the `messaging-agents` skill, `SendAgentMessage`), which address it
 * by agent id. So a mention is delivered as plain text the receiving agent can act on: the name a
 * person reads, and the id the agent's tools need, in one fixed shape:
 *
 * `@Research Bot (agent agent-123)`
 *
 * The composer inserts it through the shared `@` autocomplete ([Mentionable.insertText] without
 * the leading `@`, which the autocomplete adds), shows each reference in the draft as a chip, and
 * removes the whole reference when its chip is removed.
 */
object AgentMentions {
    private const val MAX_NAME_CHARS = 80
    private const val ID_KEYWORD = "agent"

    // `@` at the start of the text or after whitespace; a name without `@`, brackets or line
    // breaks; then ` (agent <id>)`. No lookbehind: the boundary is captured and skipped instead.
    private val REFERENCE = Regex(
        "(^|\\s)@([^@()\\r\\n]{1,$MAX_NAME_CHARS}?) \\($ID_KEYWORD ([A-Za-z0-9][A-Za-z0-9_.:-]{0,127})\\)",
    )

    /** The reference text for [agent], without the leading `@` (the autocomplete adds it). */
    fun insertText(agent: MentionableAgent): String = "${displayName(agent)} ($ID_KEYWORD ${agent.id})"

    /** The complete reference, as it appears in a draft. */
    fun reference(agent: MentionableAgent): String = "@${insertText(agent)}"

    /** The `@` suggestion for [agent] in the composer's AGENTS section. */
    fun mentionable(agent: MentionableAgent): Mentionable =
        Mentionable(
            id = agent.id,
            label = displayName(agent),
            sublabel = "agent",
            kind = MentionKind.Agent,
            insertText = insertText(agent),
        )

    /**
     * Suggestions for every agent in [roster] except [excludeAgentId] (the conversation's own
     * agent), in roster order, one per id.
     */
    fun mentionables(roster: List<MentionableAgent>, excludeAgentId: String? = null): List<Mentionable> =
        roster
            .asSequence()
            .filter { it.id.isNotBlank() && it.id != excludeAgentId }
            .distinctBy { it.id }
            .map(::mentionable)
            .toList()

    /** Every agent reference in [text], in order. */
    fun parse(text: String): List<AgentMentionRef> =
        REFERENCE.findAll(text).map { match ->
            val boundary = match.groupValues[1]
            AgentMentionRef(
                name = match.groupValues[2].trim(),
                agentId = match.groupValues[3],
                start = match.range.first + boundary.length,
                end = match.range.last + 1,
            )
        }.toList()

    /**
     * [text] without [ref], and without the one space that followed it (the autocomplete inserts
     * a reference followed by a space), so removing a chip leaves no double space behind.
     */
    fun remove(text: String, ref: AgentMentionRef): String {
        if (ref.start !in 0..text.length || ref.end !in ref.start..text.length) return text
        val end = if (ref.end < text.length && text[ref.end] == ' ') ref.end + 1 else ref.end
        return text.removeRange(ref.start, end)
    }

    /** The name a reference shows: never blank, never able to break the reference's shape. */
    private fun displayName(agent: MentionableAgent): String {
        val cleaned = agent.name
            .replace(Regex("[@()\\r\\n]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
            .take(MAX_NAME_CHARS)
            .trim()
        return cleaned.ifEmpty { agent.id }
    }
}
