package com.letta.mobile.data.repository.subagent

import com.letta.mobile.data.model.SubagentEntry
import com.letta.mobile.data.model.SubagentStatus
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * letta-mobile-m6oa1.1: the Kotlin App Server's own Agent-tool_call
 * correlation reducer — the first migration step of epic m6oa1
 * (lettashim → Kotlin App Server). This is the Kotlin analogue of the
 * shim's `ingestParentFrame`, but STRICTLY scoped to dispatch + return
 * correlation:
 *
 *  - Identity-from-return-body parsing (taskId / subagentAgentId /
 *    parentRunId scraped from the dispatch's return body) is m6oa1.3 —
 *    NOT here. This reducer never scrapes identity from a body.
 *  - Terminal / lifecycle / log / PID detection (the reaper-style
 *    `cancelled` nuance, failure classification) is m6oa1.4 — NOT here.
 *    A return is treated as a single COMPLETED transition.
 *
 * This is a PURE reducer: no coroutines, no IO, no platform APIs, and no
 * `java.*`. It holds an in-memory [Map] keyed by the parent Agent
 * tool_call's `tool_call_id` (the canonical correlation key mobile uses
 * everywhere — see [SubagentEntry.toolCallId]) and exposes pure functions
 * that a stateful, platform-aware caller (e.g. `IrohChannelTransport`) taps
 * as it observes the parent run's frames.
 *
 * Because it is pure common code with no platform dependencies, it compiles
 * for hostNative and is exercised by the `:sharedLogic:allTests` gate.
 *
 * Provenance ([SubagentEntry.parentRunId] / [parentAgentId] /
 * [parentConversationId]) is filled ONLY from the [ParentContext] the caller
 * already has in scope at the ingesting call site. It is never inferred from
 * the display name and never invented: absent context stays null.
 */
class SubagentCorrelator {

    private val entries: MutableMap<String, SubagentEntry> = mutableMapOf()

    /**
     * Bumped on every observable state change so a caller can cheaply detect
     * "did anything change?" without diffing the snapshot. Idempotent
     * re-observes that produce no change do NOT bump it.
     */
    var revision: Long = 0
        private set

    /**
     * Immutable snapshot of the current correlation state, insertion-ordered
     * (a [LinkedHashMap] backs [entries]).
     */
    fun snapshot(): List<SubagentEntry> = entries.values.toList()

    /**
     * Record (or backfill) a `running` [SubagentEntry] for a parent Agent
     * dispatch, keyed by [toolCallId].
     *
     * [arguments] is the raw JSON of the Agent tool_call's `arguments` (a JSON
     * object, which may itself be a JSON-encoded string — both shapes are
     * handled). `description` and `subagentType` are parsed from it.
     *
     * Idempotent on re-observe: a second dispatch frame for the same
     * [toolCallId] does NOT create a duplicate. Instead it BACKFILLS — any
     * field that is currently empty/null is filled from the newly-observed
     * frame, but a value already terminal or already populated is preserved
     * (never clobbered back to running/empty). This mirrors the shim's
     * conservative fold.
     */
    fun onAgentDispatch(
        toolCallId: String,
        arguments: String?,
        parent: ParentContext,
    ) {
        val parsed = parseAgentArguments(arguments)
        val existing = entries[toolCallId]

        val next = existing?.copy(
            // Backfill only: never clobber a populated field with an empty one,
            // and never downgrade a terminal status back to running.
            description = existing.description.ifEmpty { parsed.description },
            subagentType = existing.subagentType.ifEmpty { parsed.subagentType },
            parentRunId = existing.parentRunId ?: parent.runId,
            parentAgentId = existing.parentAgentId ?: parent.agentId,
            parentConversationId = existing.parentConversationId ?: parent.conversationId,
        ) ?: SubagentEntry(
            toolCallId = toolCallId,
            description = parsed.description,
            subagentType = parsed.subagentType,
            status = SubagentStatus.RUNNING,
            parentRunId = parent.runId,
            parentAgentId = parent.agentId,
            parentConversationId = parent.conversationId,
        )

        if (next != existing) {
            entries[toolCallId] = next
            revision += 1
        }
    }

    /**
     * Mark the entry for [toolCallId] terminal ([SubagentStatus.COMPLETED]).
     *
     * Minimal by design: full failure / lifecycle nuance is m6oa1.4. A return
     * is a single COMPLETED transition here.
     *
     * Correlates purely by [toolCallId]: a `tool_return_message` does NOT carry
     * the tool name, so the only returns this reducer can attribute to an
     * `Agent` dispatch are those whose id it already recorded via
     * [onAgentDispatch]. A return for an UNKNOWN id (a non-Agent tool, or a
     * dispatch that was never observed) is therefore IGNORED — the reducer
     * never invents an entry from a bare return. This keeps the "non-Agent
     * tool_call is ignored" invariant holding for returns too.
     *
     * Does NOT scrape identity from any return body.
     */
    fun onAgentReturn(
        toolCallId: String,
        parent: ParentContext,
    ) {
        val existing = entries[toolCallId] ?: return

        val next = existing.copy(
            status = SubagentStatus.COMPLETED,
            parentRunId = existing.parentRunId ?: parent.runId,
            parentAgentId = existing.parentAgentId ?: parent.agentId,
            parentConversationId = existing.parentConversationId ?: parent.conversationId,
        )

        if (next != existing) {
            entries[toolCallId] = next
            revision += 1
        }
    }

    private data class ParsedAgentArgs(
        val description: String,
        val subagentType: String,
    )

    private fun parseAgentArguments(arguments: String?): ParsedAgentArgs {
        if (arguments.isNullOrBlank()) return ParsedAgentArgs("", "")
        return runCatching {
            // The tool_call `arguments` may be a JSON object OR a JSON-encoded
            // string that itself holds the object. Unwrap one level of string
            // encoding if needed.
            var element = json.parseToJsonElement(arguments)
            (element as? kotlinx.serialization.json.JsonPrimitive)
                ?.takeIf { it.isString }
                ?.contentOrNull
                ?.let { element = json.parseToJsonElement(it) }

            val obj = element.jsonObject
            val description = obj["description"]?.jsonPrimitive?.contentOrNull ?: ""
            val subagentType = (
                obj["subagent_type"]?.jsonPrimitive?.contentOrNull
                    ?: obj["subagentType"]?.jsonPrimitive?.contentOrNull
                ) ?: ""
            ParsedAgentArgs(description, subagentType)
        }.getOrElse { ParsedAgentArgs("", "") }
    }

    private companion object {
        /** Lenient so malformed/partial arguments never throw at the seam. */
        private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    }
}

/**
 * Authoritative parent identity in scope at the ingesting call site. Carried
 * into every [SubagentCorrelator] call so provenance is filled from real
 * context rather than inferred. [runId] is nullable because it is derivable
 * only when the parent run's frame carries it.
 */
data class ParentContext(
    val agentId: String?,
    val conversationId: String?,
    val runId: String?,
)
