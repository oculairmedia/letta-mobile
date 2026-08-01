package com.letta.mobile.data.repository

import com.letta.mobile.util.Telemetry

/**
 * Roster/name-resolution telemetry (letta-mobile-z5lqt).
 *
 * TELEMETRY ONLY. Nothing here changes resolution behaviour: no function in
 * this file fetches, retries, mutates a roster, or alters what a caller
 * returns. It exists so the known name-resolution defect can be *watched*
 * failing with instrumentation in place rather than fixed blind. The actual
 * fix lands in a separate bead.
 *
 * Structure follows `AdminRouteTelemetry` (the `Telemetry/IrohAdminRoute`
 * precedent): a `commonMain` object that owns the tag, the outcome
 * classification, and the attribute shaping. Platform modules may only bind
 * call sites to these functions — no reducers/classifiers/formatters outside
 * `sharedLogic/src/commonMain` (`shared-multiplatform` CI check, AGENTS.md).
 */
object RosterNameTelemetry {
    const val CATEGORY = "RosterName"

    /**
     * Why an agent-roster paging sweep stopped.
     *
     * All five outcomes are individually distinguishable in telemetry: the
     * paging loop has four silent `break`s plus a page-cap exhaustion, and
     * every one of them can hand back a TRUNCATED roster. Collapsing any two
     * of them would hide which truncation actually happened.
     */
    enum class SweepStop(val wireName: String) {
        /** RPC returned no result payload at all. */
        NO_RESULT("noResult"),

        /** The server returned a structurally empty page. */
        EMPTY_PAGE("emptyPage"),

        /**
         * Every agent on the page had already been seen: the server IGNORED
         * our offset. Distinct from [EMPTY_PAGE] — the server sent data, it
         * was just the same data, so the sweep cannot advance.
         */
        NO_FRESH_IGNORED_OFFSET("noFreshIgnoredOffset"),

        /** Short page: the normal, expected end of a complete sweep. */
        SHORT_PAGE("shortPage"),

        /** Hit the hard page cap with pages still full. Always WARN. */
        PAGE_CAP_EXHAUSTED("pageCapExhausted"),
        ;

        /** Page-cap exhaustion is the only stop that is inherently abnormal. */
        val level: Telemetry.Level
            get() = if (this == PAGE_CAP_EXHAUSTED) Telemetry.Level.WARN else Telemetry.Level.INFO
    }

    /**
     * Result of comparing a swept roster against the authoritative
     * `agent.count`.
     *
     * Modelled as an explicit type rather than a nullable Boolean on purpose.
     * A window where the authoritative count could not be measured must never
     * collapse into either "agreed" or "disagreed": an unmeasurable window
     * reported as a pass or a fail is the exact defect class this bead exists
     * to expose.
     */
    sealed interface Completeness {
        val wireName: String

        /** Swept size equals the authoritative count. */
        data class Match(val sweptSize: Int, val authoritativeCount: Int) : Completeness {
            override val wireName: String get() = "match"
        }

        /** Both sides measured and they differ — the real defect signal. */
        data class Mismatch(val sweptSize: Int, val authoritativeCount: Int) : Completeness {
            override val wireName: String get() = "mismatch"

            /** Swept minus authoritative; negative means the roster is short. */
            val delta: Int get() = sweptSize - authoritativeCount
        }

        /**
         * The authoritative count was unavailable (RPC failed, absent,
         * negative, …). Loud and explicit: NOT a match, NOT a mismatch.
         */
        data class Unknown(val sweptSize: Int, val reason: String) : Completeness {
            override val wireName: String get() = "unknown"
        }
    }

    /** Reason codes for [Completeness.Unknown]. */
    object UnknownReason {
        const val COUNT_UNAVAILABLE = "countUnavailable"
        const val COUNT_FAILED = "countFailed"
        const val COUNT_INVALID = "countInvalid"
    }

    /**
     * Classify a swept roster against an authoritative count.
     *
     * @param authoritativeCount the server-reported total, or null when it
     *   could not be obtained. A null — or a nonsensical negative — yields
     *   [Completeness.Unknown], never a match and never a mismatch.
     */
    fun classifyCompleteness(
        sweptSize: Int,
        authoritativeCount: Int?,
        unknownReason: String = UnknownReason.COUNT_UNAVAILABLE,
    ): Completeness = when {
        authoritativeCount == null ->
            Completeness.Unknown(sweptSize = sweptSize, reason = unknownReason)
        authoritativeCount < 0 ->
            Completeness.Unknown(sweptSize = sweptSize, reason = UnknownReason.COUNT_INVALID)
        authoritativeCount == sweptSize ->
            Completeness.Match(sweptSize = sweptSize, authoritativeCount = authoritativeCount)
        else ->
            Completeness.Mismatch(sweptSize = sweptSize, authoritativeCount = authoritativeCount)
    }

    /**
     * Classify from a `runCatching { countAgents() }`-style result without the
     * caller having to flatten it, so a failed count is recorded as UNKNOWN
     * rather than silently defaulting to some placeholder number.
     */
    fun classifyCompleteness(
        sweptSize: Int,
        authoritativeCount: Result<Int?>,
    ): Completeness = authoritativeCount.fold(
        onSuccess = { count ->
            classifyCompleteness(sweptSize, count, UnknownReason.COUNT_UNAVAILABLE)
        },
        onFailure = {
            Completeness.Unknown(sweptSize = sweptSize, reason = UnknownReason.COUNT_FAILED)
        },
    )

    /** Attribute shaping for a completeness outcome. Pure; no emission. */
    fun completenessAttrs(outcome: Completeness): List<Pair<String, Any?>> = when (outcome) {
        is Completeness.Match -> listOf(
            "completeness" to outcome.wireName,
            "sweptSize" to outcome.sweptSize,
            "authoritativeCount" to outcome.authoritativeCount,
        )
        is Completeness.Mismatch -> listOf(
            "completeness" to outcome.wireName,
            "sweptSize" to outcome.sweptSize,
            "authoritativeCount" to outcome.authoritativeCount,
            "delta" to outcome.delta,
        )
        is Completeness.Unknown -> listOf(
            "completeness" to outcome.wireName,
            "sweptSize" to outcome.sweptSize,
            "authoritativeCount" to "unknown",
            "unknownReason" to outcome.reason,
        )
    }

    /**
     * A missing authoritative count is as loud as a mismatch: an unmeasurable
     * window must not look like a healthy one on a dashboard.
     */
    fun completenessLevel(outcome: Completeness): Telemetry.Level = when (outcome) {
        is Completeness.Match -> Telemetry.Level.INFO
        is Completeness.Mismatch -> Telemetry.Level.WARN
        is Completeness.Unknown -> Telemetry.Level.WARN
    }

    /** Attribute shaping for a sweep stop. Pure; no emission. */
    fun sweepStopAttrs(
        stop: SweepStop,
        offset: Int,
        pageSize: Int,
        mergedSize: Int,
    ): List<Pair<String, Any?>> = listOf(
        "stop" to stop.wireName,
        "offset" to offset,
        "pageSize" to pageSize,
        "mergedSize" to mergedSize,
    )

    /**
     * Record why an agent-roster paging sweep stopped.
     *
     * `pageSize` is the size of the page that triggered the stop (0 when the
     * RPC produced no page at all).
     */
    fun sweepStopped(
        stop: SweepStop,
        offset: Int,
        pageSize: Int,
        mergedSize: Int,
        source: String,
    ) {
        val attrs = sweepStopAttrs(stop, offset, pageSize, mergedSize) + ("source" to source)
        Telemetry.event(
            CATEGORY,
            "roster.sweepStopped",
            *attrs.toTypedArray(),
            level = stop.level,
        )
    }

    /** Record the roster-completeness invariant for a finished sweep. */
    fun rosterCompleteness(outcome: Completeness, source: String) {
        val attrs = completenessAttrs(outcome) + ("source" to source)
        Telemetry.event(
            CATEGORY,
            "roster.completeness",
            *attrs.toTypedArray(),
            level = completenessLevel(outcome),
        )
    }

    /**
     * Record a cached-agent lookup miss.
     *
     * Emitted from a pure, non-suspending accessor: this call must stay
     * allocation-light and must never fetch.
     */
    fun cacheMiss(agentId: String, cacheSize: Int, source: String) {
        Telemetry.event(
            CATEGORY,
            "agentCache.miss",
            "agentId" to agentId,
            "cacheSize" to cacheSize,
            "source" to source,
            level = Telemetry.Level.WARN,
        )
    }

    /** Where a display-name fallback was taken. */
    enum class NameFallbackSite(val wireName: String) {
        /** Conversation list row: name degraded to `agentId.take(8)`. */
        CONVERSATION_LIST("conversationList"),

        /** Chat coordinator: name degraded to the previous ui-state name. */
        CHAT_COORDINATOR("chatCoordinator"),
    }

    /**
     * Record that a display name could not be resolved and a fallback was
     * used. The fallback itself is left exactly as-is — this only observes it.
     */
    fun nameFallback(
        site: NameFallbackSite,
        agentId: String,
        fallbackKind: String,
        rosterSize: Int,
    ) {
        Telemetry.event(
            CATEGORY,
            "name.fallback",
            "site" to site.wireName,
            "agentId" to agentId,
            "fallbackKind" to fallbackKind,
            "rosterSize" to rosterSize,
            level = Telemetry.Level.WARN,
        )
    }

    /** Fallback kind constants for [nameFallback]. */
    object FallbackKind {
        const val ID_PREFIX = "idPrefix"
        const val PREVIOUS_UI_NAME = "previousUiName"
    }
}
