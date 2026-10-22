package com.letta.mobile.data.model

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The ONE rule for what user-facing agent and
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

    /**
     * A listed conversation row that belongs to no agent (`agent_id` absent, null or blank). The
     * Letta API's ephemeral conversations are these: letta-code makes one for each one-shot
     * subagent / workflow worker (`--ephemeral` headless runs, agent-free App Server runtimes,
     * the OpenAI-compatible endpoint). They are worker transcripts, never a user's chat, so no
     * list shows them; opening one by id is unaffected.
     */
    fun isAgentFreeRow(row: JsonObject): Boolean = when (val agent = row[AGENT_ID_KEY]) {
        null, JsonNull -> true
        is JsonPrimitive -> agent.isString && agent.content.isBlank()
        else -> false
    }

    private const val AGENT_ID_KEY = "agent_id"

    private fun isSubagentTag(tag: String): Boolean =
        tag == SUBAGENT_ROLE_TAG || SUBAGENT_TAG_PREFIXES.any(tag::startsWith)
}

/** The agents a user-facing list shows. */
fun List<Agent>.visibleInLists(): List<Agent> = filterNot(HiddenListFilter::isHidden)

/** The conversations a user-facing list shows. */
fun List<Conversation>.visibleConversationsInLists(): List<Conversation> = filterNot(HiddenListFilter::isHidden)
