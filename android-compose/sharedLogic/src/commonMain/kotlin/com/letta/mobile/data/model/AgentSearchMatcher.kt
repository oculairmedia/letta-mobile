package com.letta.mobile.data.model

/**
 * Shared matcher for the client-side agent search surfaces (new-chat picker,
 * agent list, dashboard search).
 *
 * Agent search never goes over the wire — every surface filters the already
 * swept roster in memory. Each of those surfaces had grown its own
 * `name.contains(wholeQuery)` filter, which made a query fail whenever the
 * words appeared in a different order or were separated differently than in
 * the agent's name: `"letta-mobile pm"` is not a substring of
 * `"PM-letta-mobile"`, so the agent was unfindable even though it was present
 * in the roster.
 *
 * Matching here is token-AND: the query is split on whitespace and every token
 * must appear somewhere in the agent's searchable text. That makes word order
 * irrelevant while keeping each token a substring match, so partial words
 * ("mobi") still narrow as the user types.
 *
 * The searchable text mirrors what the backend's own `query_text` filter
 * covers (name, description, id, model) plus tags, which the local surfaces
 * already offered.
 */
object AgentSearchMatcher {

    /**
     * True when [query] is blank (no filtering) or every whitespace-separated
     * token in it appears in [agent]'s searchable text, case-insensitively.
     */
    fun matches(agent: Agent, query: String): Boolean {
        val tokens = tokenize(query)
        if (tokens.isEmpty()) return true
        val haystack = haystackOf(agent)
        return tokens.all { haystack.contains(it) }
    }

    /** [matches] for the slim projection used by picker surfaces. */
    fun matches(agent: AgentSummary, query: String): Boolean {
        val tokens = tokenize(query)
        if (tokens.isEmpty()) return true
        val haystack = haystackOf(agent.name, agent.description, null, agent.id.value, emptyList())
        return tokens.all { haystack.contains(it) }
    }

    /** Convenience filter preserving input order. */
    fun filter(agents: List<Agent>, query: String): List<Agent> {
        val tokens = tokenize(query)
        if (tokens.isEmpty()) return agents
        return agents.filter { agent ->
            val haystack = haystackOf(agent)
            tokens.all { haystack.contains(it) }
        }
    }

    private fun tokenize(query: String): List<String> =
        query.trim().lowercase().split(WHITESPACE).filter { it.isNotEmpty() }

    private fun haystackOf(agent: Agent): String =
        haystackOf(agent.name, agent.description, agent.model, agent.id.value, agent.tags)

    private fun haystackOf(
        name: String?,
        description: String?,
        model: String?,
        id: String?,
        tags: List<String>,
    ): String = buildString {
        appendField(name)
        appendField(description)
        appendField(model)
        appendField(id)
        tags.forEach { appendField(it) }
    }.lowercase()

    private fun StringBuilder.appendField(value: String?) {
        if (value.isNullOrEmpty()) return
        if (isNotEmpty()) append(' ')
        append(value)
    }

    private val WHITESPACE = Regex("\\s+")
}
