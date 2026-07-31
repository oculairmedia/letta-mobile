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
    fun agent(name: String?, id: String): String {
        val trimmed = name?.trim().orEmpty()
        if (trimmed.isNotEmpty() && trimmed != id) return trimmed
        return "Agent ${shortId(id)}"
    }

    /**
     * A short, stable, human-scale slice of an entity id: strips the type
     * prefixes ("agent-", "local-", "conv-", "block-") and keeps the first
     * [SHORT_ID_LENGTH] characters of what remains — enough to disambiguate,
     * short enough to read.
     */
    fun shortId(id: String): String {
        var rest = id
        do {
            val before = rest
            KNOWN_PREFIXES.forEach { prefix -> rest = rest.removePrefix(prefix) }
        } while (rest != before && rest.isNotEmpty())
        return (rest.ifEmpty { id }).take(SHORT_ID_LENGTH)
    }

    private val KNOWN_PREFIXES = listOf("agent-", "local-", "conv-", "block-")
    private const val SHORT_ID_LENGTH = 8
}
