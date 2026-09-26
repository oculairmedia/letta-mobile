package com.letta.mobile.data.system1

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Wire models for the System 1 (Laya) decision service.
 *
 * System 1 is the fast half of the dual-process split: a non-autoregressive
 * encoder answers calibrated questions about a user message in one forward pass
 * (~90ms round-trip on CUDA) so the app can decide whether System 2 — the Letta
 * agent turn loop — needs to run at all. Interactive UI affordances that would
 * be ruinous at LLM prices and latencies become affordable here.
 *
 * Field names mirror `tools/system1-laya/runtime.py` exactly. The service and
 * these models are one contract; changing a name on either side without the
 * other silently degrades every report to its neutral default, because
 * [System1Client] decodes leniently rather than failing a user turn.
 */
@Serializable
enum class System1Disposition {
    /** Nothing objectionable; dispatch to System 2 normally. */
    ALLOW,

    /** Elevated safety signal. Dispatch, but surface a caution or tighten tool policy. */
    WARN,

    /** Trivial, tool-free, non-sensitive. Eligible for a cheap model or a local answer. */
    FAST_TRACK,

    /** Safety signal above the block threshold. Refuse without spending a System 2 turn. */
    BLOCK,
}

/** Safety preflight: jailbreak, injection, sensitive data, harm, topic. */
@Serializable
data class System1GuardReport(
    @SerialName("jailbreak_prob") val jailbreakProb: Double = 0.0,
    @SerialName("injection_prob") val injectionProb: Double = 0.0,
    @SerialName("sensitive_data_prob") val sensitiveDataProb: Double = 0.0,
    @SerialName("harm_severity") val harmSeverity: Double = 0.0,
    @SerialName("harm_severity_label") val harmSeverityLabel: String = "none",
    val topic: String = "other",
    @SerialName("topic_confidence") val topicConfidence: Double = 0.0,
)

/**
 * Model routing and difficulty triage.
 *
 * [needsTools] is carried but is NOT reliable on the stock checkpoint — measured
 * separation between tool-requiring and knowledge-only requests is within noise.
 * Treat it as a hint, never as a gate. See the README's calibration findings.
 */
@Serializable
data class System1RouteReport(
    val difficulty: Double = 0.0,
    @SerialName("difficulty_label") val difficultyLabel: String = "trivial",
    val domain: String = "other",
    @SerialName("domain_confidence") val domainConfidence: Double = 0.0,
    @SerialName("needs_tools") val needsTools: Boolean = false,
    @SerialName("needs_tools_prob") val needsToolsProb: Double = 0.0,
    @SerialName("is_sensitive") val isSensitive: Boolean = false,
    @SerialName("is_sensitive_prob") val isSensitiveProb: Double = 0.0,
)

/** User intent and sentiment. */
@Serializable
data class System1TriageReport(
    val intent: String = "other",
    @SerialName("intent_confidence") val intentConfidence: Double = 0.0,
    @SerialName("is_urgent") val isUrgent: Boolean = false,
    @SerialName("is_urgent_prob") val isUrgentProb: Double = 0.0,
    val frustration: Double = 0.0,
    @SerialName("frustration_label") val frustrationLabel: String = "calm",
    @SerialName("churn_risk") val churnRisk: Double = 0.0,
)

/**
 * Semantic turn-taking signals for the interactive composer.
 *
 * Deliberately does NOT answer "is the draft finished?" or "has it changed
 * enough?" — the model does not separate those. [System1ReflexGate] answers them
 * deterministically, on-device, for free.
 */
@Serializable
data class System1InteractionReport(
    @SerialName("expects_response") val expectsResponse: Boolean = false,
    @SerialName("expects_response_prob") val expectsResponseProb: Double = 0.0,
    @SerialName("interaction_mode") val interactionMode: String = "respond_now",
    @SerialName("interaction_mode_confidence") val interactionModeConfidence: Double = 0.0,
    @SerialName("latency_tolerance") val latencyTolerance: Double = 0.0,
    @SerialName("latency_tolerance_label") val latencyToleranceLabel: String = "instant",
)

/** Full assessment from `POST /v1/system1/evaluate` — one batched forward pass. */
@Serializable
data class System1Assessment(
    val disposition: System1Disposition = System1Disposition.ALLOW,
    val reason: String = "",
    val guard: System1GuardReport = System1GuardReport(),
    val route: System1RouteReport = System1RouteReport(),
    val triage: System1TriageReport = System1TriageReport(),
    val interaction: System1InteractionReport = System1InteractionReport(),
    @SerialName("latency_ms") val latencyMs: Double = 0.0,
    val model: String = "",
    @SerialName("input_tokens") val inputTokens: Int = 0,
    /**
     * False when the service was unreachable, disabled or too slow and this is
     * the neutral fallback. Callers MUST NOT treat an unavailable assessment as
     * a safety clearance — it carries no signal, only permission to proceed to
     * System 2, which does its own safety handling.
     */
    @SerialName("available") val available: Boolean = true,
) {
    companion object {
        /**
         * The fallback returned whenever System 1 cannot answer. Neutral by
         * construction: a missing fast path must never block a user's turn.
         */
        fun unavailable(reason: String): System1Assessment = System1Assessment(
            disposition = System1Disposition.ALLOW,
            reason = reason,
            available = false,
        )
    }
}

/** Request body shared by every System 1 endpoint. */
@Serializable
data class System1EvaluateRequest(
    val text: String,
    @SerialName("previous_draft") val previousDraft: String? = null,
    val context: Map<String, String>? = null,
)
