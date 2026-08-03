package com.letta.mobile.data.model

/**
 * Human fallbacks for entity display names. Raw ids ("agent-c356b8f2-…") must
 * never reach user-visible text: they read as errors, they are unsearchable by
 * name, and they leak transport detail into the UI. Every surface that shows an
 * entity name goes through here so the fallback is consistent app-wide —
 * conversation titles already follow the same convention ("Conversation
 * <last6>" in ChatRuntimeContracts).
 */
object DisplayNames {

    /**
     * The agent's name when it has a usable one, else "Agent <short-id>".
     * A "name" that is itself the raw id (upstream fallback already applied)
     * counts as unusable.
     */
    fun agent(name: String?, id: String): String = agent(name, id, SHORT_ID_LENGTH)

    /**
     * As [agent], but with an explicit id-slice length. Callers rendering a
     * LIST of agents use this to widen the slice when two synthetic labels
     * would otherwise collide — see [disambiguateAgentFallbacks].
     */
    fun agent(name: String?, id: String, shortIdLength: Int): String {
        val trimmed = name?.trim().orEmpty()
        if (trimmed.isNotEmpty() && trimmed != id) return trimmed
        return AGENT_FALLBACK_PREFIX + shortId(id, shortIdLength)
    }

    /**
     * True when [label] is one this object synthesised rather than a name the
     * agent actually has.
     *
     * Surfaces that COLLAPSE agents by display name must ask this first: two
     * agents sharing a real name are one fleet (the ephemeral "Letta Code"
     * spawns the rail deliberately stacks), but two agents sharing a synthetic
     * label share nothing at all — merging them hides one behind the other with
     * no way to reach it.
     */
    fun isAgentFallback(label: String): Boolean = label.startsWith(AGENT_FALLBACK_PREFIX)

    /**
     * Widen the id slice on every synthetic label that appears more than once,
     * so a list of agents never renders two identical "Agent <short-id>" rows.
     *
     * Ids are prefixed UUIDs, so the first eight characters after the prefix
     * are NOT unique: `agent-12345678-a` and `agent-12345678-b` produced the
     * same label, and any surface that groups or dedupes by display name then
     * made the second agent unreachable. Real names are never touched — two
     * agents genuinely called the same thing stay that way, because that is a
     * fact about them rather than an artifact of our formatting.
     */
    fun disambiguateAgentFallbacks(agents: List<Pair<String, String>>): List<Pair<String, String>> {
        val colliding = agents
            .filter { (_, label) -> isAgentFallback(label) }
            .groupingBy { (_, label) -> label }
            .eachCount()
            .filterValues { it > 1 }
            .keys
        if (colliding.isEmpty()) return agents
        return agents.map { (id, label) ->
            if (label in colliding) {
                id to (AGENT_FALLBACK_PREFIX + shortId(id, DISAMBIGUATED_ID_LENGTH))
            } else {
                id to label
            }
        }
    }

    /**
     * A short, stable, human-scale slice of an entity id: strips the type
     * prefixes ("agent-", "local-", "conv-", "block-") and keeps the first
     * [SHORT_ID_LENGTH] characters of what remains — enough to disambiguate,
     * short enough to read.
     */
    fun shortId(id: String): String = shortId(id, SHORT_ID_LENGTH)

    fun shortId(id: String, length: Int): String {
        var rest = id
        do {
            val before = rest
            KNOWN_PREFIXES.forEach { prefix -> rest = rest.removePrefix(prefix) }
        } while (rest != before && rest.isNotEmpty())
        return (rest.ifEmpty { id }).take(length)
    }

    private val KNOWN_PREFIXES = listOf("agent-", "local-", "conv-", "block-")
    private const val SHORT_ID_LENGTH = 8

    /**
     * Long enough that a collision means the ids really are near-identical —
     * a full UUID's first segment plus the separator and the next group.
     */
    private const val DISAMBIGUATED_ID_LENGTH = 18
    private const val AGENT_FALLBACK_PREFIX = "Agent "
}
