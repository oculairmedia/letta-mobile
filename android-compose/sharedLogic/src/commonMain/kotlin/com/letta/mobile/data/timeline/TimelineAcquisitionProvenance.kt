package com.letta.mobile.data.timeline

import com.letta.mobile.util.Telemetry

/**
 * letta-mobile-grrhq: DIAGNOSTIC-ONLY acquisition provenance for timeline holders.
 *
 * WHY THIS EXISTS
 * ---------------
 * A background `Agent` dispatch was observed creating a SECOND
 * [TimelineSyncLoop] for the parent's conversation under the CHILD agent's id.
 * [TimelineRepository] correctly refuses to alias two different scoped agents
 * onto one conversation, then falls through and creates that second holder; the
 * Room head is keyed by (backendId, conversationId) but validated with an
 * agentId equality check, so the child-scoped holder finds the parent's head,
 * fails validation with METADATA_INVALID, and restarts at revision 1 against a
 * canonical holder thousands of revisions ahead.
 *
 * The refusal is CORRECT. The caller asking for it is the defect. This file
 * exists to identify that caller on a controlled device rerun.
 *
 * CONTRACT - read before changing anything here
 * ---------------------------------------------
 *  - This is a PURE DIAGNOSTIC. Nothing in this file may ever be read to make a
 *    control-flow decision. Aliasing, cache keys, snapshot scoping and holder
 *    creation must behave identically whether provenance is supplied or left as
 *    [TimelineAcquisitionProvenance.UNSPECIFIED].
 *  - Provenance is passed EXPLICITLY as a parameter. No thread-locals, no
 *    coroutine context elements, no ambient holders: the repository is a
 *    process-wide singleton reached from the Android main thread, viewModel
 *    scopes, the timeline IO dispatcher and its own scope, and any ambient
 *    carrier would leak across exactly the boundaries under investigation.
 *  - [TimelineAcquisitionSource.UNSPECIFIED] is an OBSERVABLE outcome, not a
 *    silent default. An acquisition reported as UNSPECIFIED means a creator path
 *    nobody enumerated, which is itself a finding.
 *  - Every emitted field is bounded and redacted. Identifiers only: never
 *    message content, prompts, summaries, agent names, credentials, or raw
 *    server payloads.
 */

/** Which subsystem asked for a timeline holder. Diagnostic only. */
enum class TimelineAcquisitionSource {
    /** A route/ViewModel bind with a conversation id the route already carried. */
    UI_NAVIGATION,

    /** A route that arrived WITHOUT a conversation id; a resolver picked one. */
    UI_RESOLVED_ROUTE,

    /** Optimistic append / send / mark-sent-or-failed. */
    SEND_PIPELINE,

    /** Live frame ingest through a send coordinator scoped to its own agent. */
    RUNTIME_INGEST,

    /**
     * A frame-derived scope: agent id taken from a wire frame's runtime rather
     * than from a route. No production path is known to produce this today -
     * its APPEARANCE in a rerun revives the fanout hypothesis, and its ABSENCE
     * is meaningful evidence against it.
     */
    RUNTIME_FANOUT,

    /** Replay, reconciliation, cursor repair, or frame-collector overflow. */
    REPLAY_RECONCILE,

    /** Background push delivery. */
    PUSH_NOTIFICATION,

    /** Speculative snapshot pre-warm. */
    WARM,

    /** Default. An uninstrumented call site - observable, never ignored. */
    UNSPECIFIED,
}

/** The frame family that motivated an acquisition, when one did. */
enum class TimelineAcquisitionFrameFamily {
    NONE,
    NATIVE_UPDATE_SUBAGENT_STATE,
    CHILD_STREAM_DELTA,
    SYNTHETIC_SUBAGENTS_UPDATED,
    TOOL_CALL,
    TOOL_RETURN,
    REPLAY,
    DIRECT_NAVIGATION,
}

/** How the conversation id reaching an acquisition was chosen. */
enum class TimelineConversationSelectionMode {
    /** The caller supplied an explicit conversation id. */
    EXPLICIT_CONVERSATION_ID,

    /** Taken from already-resolved route state. */
    ROUTE_STATE,

    /** Resolver picked the newest cached conversation for the agent. */
    MOST_RECENT_FALLBACK,

    /** Fell back to a default/provisional conversation id. */
    DEFAULT_FALLBACK,

    UNKNOWN,
}

/**
 * Which agent(s) the selected conversation is attributed to in the cached /
 * server-derived view at selection time.
 *
 * This enum is the A/B/C discriminator for letta-mobile-grrhq:
 *  - [PARENT_AGENT_ONLY] -> outcome A: the child resolver selected a
 *    conversation that is not the child's. The defect is in resolution.
 *  - [BOTH]              -> outcome B: the backend/cache legitimately attributes
 *    one conversation to parent AND child. The resolver behaved as designed and
 *    the real problem is holder ownership precedence, NOT resolution.
 *  - [REQUESTED_AGENT_ONLY] / [NEITHER] -> the split came from somewhere else.
 *
 * [BOTH] must stay unambiguous: it is asserted only when the selected
 * conversation is attributed to the requested agent AND to a KNOWN parent agent.
 */
enum class TimelineConversationAttribution {
    REQUESTED_AGENT_ONLY,
    PARENT_AGENT_ONLY,
    BOTH,
    NEITHER,

    /**
     * Not classifiable - most often because no parent agent was known at the
     * selection boundary. Never a substitute for [BOTH]: absence of a known
     * parent can never be reported as dual attribution.
     */
    UNKNOWN,
}

/** Freshness of the candidate list the selection was made from. */
enum class TimelineCandidateListFreshness { FRESH, STALE, REFRESHED, UNKNOWN }

/** Where the candidate list came from. */
enum class TimelineCandidateListSource { AGENT_SCOPED_CACHE, SERVER_REFRESH, ROUTE_ONLY, UNKNOWN }

/**
 * Typed, bounded provenance for one timeline-holder acquisition attempt.
 *
 * [acquisitionId] correlates the three events this investigation needs to line
 * up: the acquisition entry (and any resolver/route decision that preceded it),
 * the alias refusal, and the resulting cache miss / second-holder creation.
 */
data class TimelineAcquisitionProvenance(
    val acquisitionId: String,
    val source: TimelineAcquisitionSource,
    /** Short stable literal, e.g. "observer.start". Never user data. */
    val operation: String,
    /**
     * Hand-written literal, e.g. "ChatTimelineObserver.kt:173".
     * Deliberately NOT a captured stack trace: unbounded traces are forbidden
     * and a literal is both cheaper and stable across refactors of callees.
     */
    val callSite: String,
    val frameFamily: TimelineAcquisitionFrameFamily = TimelineAcquisitionFrameFamily.NONE,
    val selectionMode: TimelineConversationSelectionMode = TimelineConversationSelectionMode.UNKNOWN,
    val parentAgentId: String? = null,
    val parentConversationId: String? = null,
    val runId: String? = null,
    val toolCallId: String? = null,
    val subagentId: String? = null,
    val otid: String? = null,
    val isReplay: Boolean = false,
    /** Set only at a resolver boundary that actually selected a conversation. */
    val attribution: TimelineConversationAttributionCapture? = null,
) {
    companion object {
        /**
         * The default carried by every uninstrumented call site. Reported as
         * [TimelineAcquisitionSource.UNSPECIFIED] so it shows up in telemetry
         * instead of vanishing.
         */
        val UNSPECIFIED: TimelineAcquisitionProvenance = TimelineAcquisitionProvenance(
            acquisitionId = "",
            source = TimelineAcquisitionSource.UNSPECIFIED,
            operation = "",
            callSite = "",
        )
    }
}

/**
 * Bounded, redacted summary of the cached conversation candidates considered for
 * a requested agent, plus the attribution class of the one selected.
 *
 * Identifier-only by construction: this type has no field that can hold a
 * summary, name, body, or payload.
 */
data class TimelineConversationAttributionCapture(
    val requestedAgentId: String,
    val selectedConversationId: String?,
    /** The selected conversation record's OWN agent attribution, if known. */
    val selectedRecordAgentId: String?,
    val candidateCount: Int,
    val parentAgentId: String?,
    /** True when the selection is also attributed to [parentAgentId]. */
    val selectedAlsoAttributedToParent: Boolean,
    val attribution: TimelineConversationAttribution,
    val selectionMode: TimelineConversationSelectionMode,
    val candidateListSource: TimelineCandidateListSource,
    val candidateListFreshness: TimelineCandidateListFreshness,
    /** Bounded sample of candidate ids (see [TimelineProvenanceRedaction]). */
    val candidateIdSample: List<String>,
    /** Stable digest over the FULL candidate set, so sets compare across events. */
    val candidateIdDigest: String,
)

/**
 * Pure classifier for [TimelineConversationAttribution].
 *
 * Two attribution signals exist, and they are NOT interchangeable:
 *  1. the selected conversation record's own `agentId` field — authoritative;
 *  2. membership of the selected id in an agent's cached conversation list.
 *
 * ASYMMETRY, AND WHY IT MATTERS. The resolver picks the selection FROM the
 * requested agent's own cache bucket, so "the selection is in the requested
 * agent's candidate list" is very nearly a tautology. Treating that membership
 * as proof of requested-attribution would make `attributedToRequested` always
 * true for a fallback selection and would collapse outcome A
 * (PARENT_AGENT_ONLY) into outcome B (BOTH) — silently converting "the child
 * resolver picked a conversation that is not the child's" into "the backend
 * legitimately attributes it to both". That is the exact misdiagnosis this
 * classifier exists to prevent.
 *
 * So for the REQUESTED agent the record's own attribution decides, and bucket
 * membership is consulted only when the record carries no agent id at all. For
 * the PARENT, whose bucket was not the selection source, either signal counts.
 *
 * Returns [TimelineConversationAttribution.UNKNOWN] when no parent agent is
 * known, because "only the requested agent" cannot be asserted without knowing
 * who the other claimant would be. This is deliberate: silently reporting
 * REQUESTED_AGENT_ONLY on missing parent data would mask outcome B.
 */
fun classifyConversationAttribution(
    selectedConversationId: String?,
    requestedAgentId: String,
    selectedRecordAgentId: String?,
    requestedAgentCandidateIds: Set<String>,
    parentAgentId: String?,
    parentAgentCandidateIds: Set<String>,
): TimelineConversationAttribution {
    if (selectedConversationId.isNullOrBlank()) return TimelineConversationAttribution.UNKNOWN
    val attributedToRequested = if (selectedRecordAgentId != null) {
        selectedRecordAgentId == requestedAgentId
    } else {
        selectedConversationId in requestedAgentCandidateIds
    }
    if (parentAgentId.isNullOrBlank()) return TimelineConversationAttribution.UNKNOWN
    val attributedToParent = selectedConversationId in parentAgentCandidateIds ||
        selectedRecordAgentId == parentAgentId
    return when {
        attributedToRequested && attributedToParent -> TimelineConversationAttribution.BOTH
        attributedToRequested -> TimelineConversationAttribution.REQUESTED_AGENT_ONLY
        attributedToParent -> TimelineConversationAttribution.PARENT_AGENT_ONLY
        else -> TimelineConversationAttribution.NEITHER
    }
}

/**
 * Injection seam for acquisition ids so tests can assert exact correlation
 * chains instead of pattern-matching random tokens.
 */
fun interface AcquisitionIdGenerator {
    fun next(): String
}

/**
 * Default generator: a short per-process random prefix plus a monotonic
 * counter. Bounded length, no java.* dependency, and unique enough to correlate
 * three events inside one log capture (it is not, and need not be, globally
 * unique).
 */
class SequentialAcquisitionIdGenerator(
    private val prefix: String = kotlin.random.Random.nextInt(0, 0xFFFFFF).toString(16),
) : AcquisitionIdGenerator {
    private val counter = kotlinx.atomicfu.atomic(0L)
    override fun next(): String = "acq-" + prefix + "-" + counter.incrementAndGet()
}

/** Bounding + redaction helpers. Every emitted string goes through these. */
object TimelineProvenanceRedaction {
    /** Max UTF-16 length of any single emitted identifier. */
    const val MAX_IDENTIFIER_LENGTH: Int = 64

    /** Max number of candidate ids emitted verbatim. */
    const val MAX_CANDIDATE_SAMPLE: Int = 8

    private const val FNV_OFFSET_BASIS: Long = -3750763034362895579L
    private const val FNV_PRIME: Long = 1099511628211L

    /**
     * Bound one identifier. Over-long values are replaced by a digest rather
     * than truncated, so an id that exceeds the cap can still be correlated
     * across events without emitting unbounded text.
     */
    fun boundedIdentifier(value: String?): String {
        if (value.isNullOrEmpty()) return ""
        return if (value.length <= MAX_IDENTIFIER_LENGTH) value else "h:" + digest(value)
    }

    /** Bounded candidate sample: capped count, each element bounded. */
    fun boundedSample(values: Collection<String>): List<String> =
        values.asSequence().take(MAX_CANDIDATE_SAMPLE).map { boundedIdentifier(it) }.toList()

    /**
     * Stable order-independent digest over a candidate set. FNV-1a: common-code
     * safe (no java.*, no extra dependency) and sufficient for equality
     * comparison across log lines. Not a security primitive, not used as one.
     */
    fun setDigest(values: Collection<String>): String {
        if (values.isEmpty()) return "0"
        var acc = 0L
        for (value in values.sorted()) {
            acc = acc xor fnv1a(value)
        }
        return acc.toULong().toString(16)
    }

    private fun digest(value: String): String = fnv1a(value).toULong().toString(16)

    private fun fnv1a(value: String): Long {
        var hash = FNV_OFFSET_BASIS
        for (char in value) {
            hash = hash xor char.code.toLong()
            hash *= FNV_PRIME
        }
        return hash
    }
}

/**
 * Emission helpers. Kept out of [TimelineRepository] so the repository body
 * stays readable and so the redaction rules live next to the types they bound.
 *
 * All events share [TimelineAcquisitionProvenance.acquisitionId].
 */
object TimelineAcquisitionTelemetry {
    const val TAG: String = "TimelineRepo"

    /** Acquisition entry. Fixed attribute allowlist; cardinality bounded by construction. */
    fun emitEntry(
        agentId: String?,
        conversationId: String,
        provenance: TimelineAcquisitionProvenance,
        creator: String,
    ) {
        if (!Telemetry.timelineAcquisitionProvenanceEnabled.get()) return
        Telemetry.event(
            TAG, "acquisition.entry",
            *baseAttrs(agentId, conversationId, provenance),
            "creator" to creator,
        )
        provenance.attribution?.let { emitAttribution(it, provenance) }
    }

    /** Resolver/route decision. Correlated to the acquisition by acquisitionId. */
    fun emitAttribution(
        capture: TimelineConversationAttributionCapture,
        provenance: TimelineAcquisitionProvenance,
    ) {
        if (!Telemetry.timelineAcquisitionProvenanceEnabled.get()) return
        Telemetry.event(
            TAG, "acquisition.conversationAttribution",
            "acquisitionId" to TimelineProvenanceRedaction.boundedIdentifier(provenance.acquisitionId),
            "requestedAgentId" to TimelineProvenanceRedaction.boundedIdentifier(capture.requestedAgentId),
            "selectedConversationId" to TimelineProvenanceRedaction.boundedIdentifier(capture.selectedConversationId),
            "selectedRecordAgentId" to TimelineProvenanceRedaction.boundedIdentifier(capture.selectedRecordAgentId),
            "candidateCount" to capture.candidateCount,
            "parentAgentId" to TimelineProvenanceRedaction.boundedIdentifier(capture.parentAgentId),
            "selectedAlsoAttributedToParent" to capture.selectedAlsoAttributedToParent,
            "attribution" to capture.attribution.name,
            "selectionMode" to capture.selectionMode.name,
            "candidateListSource" to capture.candidateListSource.name,
            "candidateListFreshness" to capture.candidateListFreshness.name,
            "candidateIdSample" to capture.candidateIdSample.joinToString(","),
            "candidateIdDigest" to capture.candidateIdDigest,
        )
    }

    /** Shared attribute block for entry / refusal / cache-miss correlation. */
    fun baseAttrs(
        agentId: String?,
        conversationId: String,
        provenance: TimelineAcquisitionProvenance,
    ): Array<Pair<String, Any?>> = arrayOf(
        "acquisitionId" to TimelineProvenanceRedaction.boundedIdentifier(provenance.acquisitionId),
        "source" to provenance.source.name,
        "operation" to TimelineProvenanceRedaction.boundedIdentifier(provenance.operation),
        "callSite" to TimelineProvenanceRedaction.boundedIdentifier(provenance.callSite),
        "frameFamily" to provenance.frameFamily.name,
        "selectionMode" to provenance.selectionMode.name,
        "agentId" to TimelineProvenanceRedaction.boundedIdentifier(agentId),
        "conversationId" to TimelineProvenanceRedaction.boundedIdentifier(conversationId),
        "parentAgentId" to TimelineProvenanceRedaction.boundedIdentifier(provenance.parentAgentId),
        "parentConversationId" to TimelineProvenanceRedaction.boundedIdentifier(provenance.parentConversationId),
        "runId" to TimelineProvenanceRedaction.boundedIdentifier(provenance.runId),
        "toolCallId" to TimelineProvenanceRedaction.boundedIdentifier(provenance.toolCallId),
        "subagentId" to TimelineProvenanceRedaction.boundedIdentifier(provenance.subagentId),
        "otid" to TimelineProvenanceRedaction.boundedIdentifier(provenance.otid),
        "isReplay" to provenance.isReplay,
        "attribution" to (provenance.attribution?.attribution?.name ?: TimelineConversationAttribution.UNKNOWN.name),
    )
}
