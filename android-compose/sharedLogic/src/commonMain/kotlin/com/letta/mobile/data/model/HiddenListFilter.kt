package com.letta.mobile.data.model

/**
 * letta-mobile-fxoew.6: the ONE rule for what user-facing agent and
 * conversation LISTS leave out. Every list source applies it at the point it
 * decodes a list; opening an agent or conversation by id (deep links, a
 * subagent's own chat) never goes through it.
 *
 * Mirrors the App Server's own default (`normalizeAgentHiddenFlag`,
 * `include_hidden`), so it is a guard for paths that bypass that default (the
 * host's on-disk readers), not a second policy.
 */
object HiddenListFilter {
    private val SUBAGENT_TAG_PREFIXES = listOf("type:", "parent:")

    /** The App Server's subagent marker tag (`LETTA_CODE_SUBAGENT_TAG`). */
    private const val SUBAGENT_ROLE_TAG = "role:subagent"

    /**
     * Hidden when `hidden == true`, or when `hidden` is unset and the tags mark
     * a subagent (`type:` / `parent:` prefixes, or exactly `role:subagent`).
     * An explicit `false` always wins. Other `role:` tags (tester, coder, ...)
     * are ordinary agents.
     */
    fun isHidden(agent: Agent): Boolean = when (agent.hidden) {
        true -> true
        false -> false
        null -> agent.tags.any(::isSubagentTag)
    }

    fun isHidden(conversation: Conversation): Boolean = conversation.hidden == true

    private fun isSubagentTag(tag: String): Boolean =
        tag == SUBAGENT_ROLE_TAG || SUBAGENT_TAG_PREFIXES.any(tag::startsWith)
}

/** The agents a user-facing list shows. */
fun List<Agent>.visibleInLists(): List<Agent> = filterNot(HiddenListFilter::isHidden)

/** The conversations a user-facing list shows. */
fun List<Conversation>.visibleConversationsInLists(): List<Conversation> = filterNot(HiddenListFilter::isHidden)
