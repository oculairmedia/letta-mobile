package com.letta.mobile.cli.commands

import com.letta.mobile.data.transport.iroh.IrohProbeAssertions
import kotlinx.coroutines.CoroutineScope

/** Normalized Iroh endpoint address (without the `iroh://` scheme). */
@JvmInline
internal value class ProbeEndpointAddress(val value: String)

/** Conversation id used for a probe scenario session. */
@JvmInline
internal value class ProbeConversationId(val value: String)

/** Optional path for dumping raw stream_delta JSONL. */
@JvmInline
internal value class ProbeDumpPath(val value: String)

/** Scenario label attached to turn metrics (e.g. `hydrate-heavy`). */
@JvmInline
internal value class ProbeScenarioName(val value: String) {
    companion object {
        val HydrateHeavy = ProbeScenarioName("hydrate-heavy")
        val CancelMidstream = ProbeScenarioName("cancel-midstream")
        val NoHttp = ProbeScenarioName("no-http")
        val DuplicateSend = ProbeScenarioName("duplicate-send")
    }
}

/** Client-side message id for probe input frames. */
@JvmInline
internal value class ProbeClientMessageId(val value: String)

/** Fallback idempotency / message id when a delta omits `id`. */
@JvmInline
internal value class ProbeFallbackId(val value: String)

/** HTTP method for probe admin setup calls. */
@JvmInline
internal value class ProbeHttpMethod(val value: String) {
    companion object {
        val Get = ProbeHttpMethod("GET")
        val Post = ProbeHttpMethod("POST")
    }
}

/** HTTP path for probe admin setup calls. */
@JvmInline
internal value class ProbeHttpPath(val value: String)

/** JSON request body for probe admin setup calls. */
@JvmInline
internal value class ProbeJsonBody(val value: String)

/** Address + conversation pair shared by every scenario runner. */
internal data class ProbeTarget(
    val address: ProbeEndpointAddress,
    val conversationId: ProbeConversationId,
)

/** Params for establishing a dialed probe session. */
internal data class ProbeEstablishRequest(
    val target: ProbeTarget,
    val scope: CoroutineScope,
    val turn: Int,
    val runAdminRpcScenario: Boolean = false,
    val turnStartedAt: Long = nowMs(),
    val setupMetrics: ProbeSetupMetrics = ProbeSetupMetrics(turn),
)

/** Params for sending one probe user input and collecting stream metrics. */
internal data class ProbeSendInputRequest(
    val turn: Int,
    val session: ProbeSession,
    val turnStartedAt: Long = nowMs(),
    val sendFailureViolation: Boolean = false,
    val scenario: ProbeScenarioName? = null,
    val clientMessageId: ProbeClientMessageId =
        ProbeClientMessageId("probe-local-${java.util.UUID.randomUUID()}"),
)

/** Params for converting a [ProbeAccumulator] into turn metrics. */
internal data class ProbeMetricsRequest(
    val dialMs: Long?,
    val firstFrameMs: Long?,
    val timedOut: Boolean,
    val scenario: ProbeScenarioName? = null,
    val profile: String = IrohProbeAssertions.PROFILE_SEND,
    val extraViolations: List<String> = emptyList(),
    val extraNotes: List<String> = emptyList(),
)

/** Result of paging through a hydrated conversation via admin_rpc. */
internal data class HydratePageResult(
    val listed: Int,
    val pageLimit: Int,
    val failures: List<String>,
)
