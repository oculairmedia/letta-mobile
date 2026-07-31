package com.letta.mobile.data.runtime

import com.letta.mobile.data.controller.extras.ExternalToolRegistry
import com.letta.mobile.data.controller.extras.ExternalToolResult
import com.letta.mobile.data.controller.fanout.InboundControlRequestRegistry
import com.letta.mobile.data.transport.appserver.AppServerClient
import com.letta.mobile.data.transport.appserver.AppServerCommand
import com.letta.mobile.data.transport.appserver.AppServerExternalToolResult
import com.letta.mobile.data.transport.appserver.AppServerExternalToolResultContent
import com.letta.mobile.data.transport.appserver.AppServerInboundFrame
import com.letta.mobile.util.Telemetry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration.Companion.milliseconds

/**
 * letta-mobile-lgns8.22.5: owns the FULL lifecycle of one
 * `external_tool_call_request` — claim, generation fencing, bounded invocation,
 * result caching and the matched `external_tool_call_response`.
 *
 * Extracted verbatim from `AppServerTurnEngine` (lgns8.17 + lgns8.22.4.1.2 +
 * lgns8.22.4.1.6 + PR #1077); no behaviour change. The engine keeps only the
 * decisions that genuinely need a turn lease — WHICH generation is validated for
 * a frame, and WHETHER a request is unleased — and hands the rest here.
 *
 * THE GUARANTEE
 * letta-code's App-Server (WS) route does NOT self-execute tool calls: it emits
 * `external_tool_call_request` and BLOCKS the turn until it receives a matched
 * `external_tool_call_response` (matched by request_id; content irrelevant). If a
 * request goes unanswered the turn hangs until the idle watchdog force-fails it.
 * This dispatcher therefore ALWAYS answers — executing the tool via
 * [externalToolRegistry] when it advertises it, otherwise synthesizing a matched
 * `is_error` response. That is the machinery lettashim used to provide.
 *
 * Ownership of the request identity (claim, lease binding, detachment,
 * generation fence) lives in [InboundControlRequestRegistry]; the computed
 * result lives in [ExternalToolResultCache]. This class sequences the two.
 */
internal class ExternalToolDispatcher(
    private val client: AppServerClient,
    /** Null = no controller tools, so every request still gets a benign error response. */
    private val externalToolRegistry: ExternalToolRegistry?,
    private val inboundControlRegistry: InboundControlRequestRegistry,
    private val connectionGenerationProvider: () -> Long,
    /**
     * lgns8.22.4.1.6: computed results retained PAST a successful (one-way, hence
     * ambiguous) send so a reconnect replay reuses the result instead of
     * re-invoking a non-idempotent tool. Bounded + TTL-expiring.
     *
     * Shared across concurrent runtime keys on purpose: it is internally
     * lock-guarded and keyed by request identity (request_id, tool_call_id)
     * rather than by turn — a result belongs to a REQUEST, not to whichever
     * runtime observed it.
     */
    private val resultCache: ExternalToolResultCache = ExternalToolResultCache(),
    /**
     * lgns8.17(c): per-invocation deadline for `ExternalTool.invoke`. A
     * protocol-derived constant, not a per-host tuning knob — the value must stay
     * strictly inside the server's own window. Injectable for tests only.
     */
    private val invocationTimeoutMs: Long = INVOCATION_TIMEOUT_MS,
) {
    /**
     * Drop cached results the server will never replay for. Called on definitive
     * connection-generation cleanup.
     */
    fun pruneExpiredResults() {
        resultCache.pruneExpired()
    }

    /**
     * Answer [request] so the App Server unblocks the turn.
     *
     * Matching is by request_id — the ONLY correlation key the App Server uses
     * (the response carries request_id, not tool_call_id). Fire-and-forget
     * one-way send: any send failure is logged, never rethrown, so it cannot
     * break the turn's event collector. If the connection has since dropped the
     * send is lost, but the App Server re-emits the still-blocking request on
     * reconnect/sync, so the next collect re-answers.
     *
     * @param validatedGeneration the generation `matches()` VALIDATED for this
     *   lease, NEVER a fresh read of the live provider (lgns8.22.4.1.2). A
     *   disconnect racing this handler must not make us register/claim (and
     *   execute) under a successor generation, poisoning its registry entry and
     *   duplicating tool side effects.
     */
    suspend fun answer(
        request: AppServerInboundFrame.ExternalToolCallRequest,
        leaseToken: Long,
        validatedGeneration: Long,
    ) {
        val generation = validatedGeneration
        val ref = InboundControlRequestRegistry.RequestRef(request.requestId, request.toolCallId)
        // Direct client.events path may not have gone through the fanout register.
        inboundControlRegistry.register(
            InboundControlRequestRegistry.RegisterRequest(
                requestId = request.requestId,
                kind = InboundControlRequestRegistry.Kind.ExternalTool,
                connectionGeneration = generation,
                agentId = request.runtime?.agentId,
                conversationId = request.runtime?.conversationId,
                toolCallId = request.toolCallId,
            ),
        )
        // matches() already claimed delivery for this lease; only answer if we own it
        // (or claim here on paths that skipped the registry match branch).
        if (!inboundControlRegistry.ownsClaim(ref, leaseToken, generation) &&
            !inboundControlRegistry.tryClaim(ref, leaseToken, generation)
        ) {
            Telemetry.event(
                TELEMETRY_SOURCE, "externalTool.claimSkipped",
                "requestId" to request.requestId,
                "toolCallId" to request.toolCallId,
                "leaseToken" to leaseToken,
                "generation" to generation,
            )
            return
        }
        // PR #1077 review (P1): PIN the claim to this invocation before suspending.
        // This job runs on the engine's dispatch scope and deliberately outlives its
        // turn, but the turn's `finally` calls releaseClaimsForLease — which would
        // flip this request back to Pending WHILE the tool is still running, letting
        // a replay or successor lease execute a non-idempotent tool a second time.
        // Detaching keeps ownership with the invocation until it answers or releases.
        inboundControlRegistry.markDetached(ref, leaseToken, generation)
        // Fence BEFORE invoking a possibly non-idempotent handler: if the
        // connection died between the claim and here, the tool must not run and
        // this claim is returned so the successor generation's replay can own it.
        if (abortStale(request, leaseToken, generation, phase = "beforeInvoke")) return
        val cached = resultCache.get(request.resultCacheKey())
        val result: AppServerExternalToolResult = cached ?: computeAndCacheResult(request)
        // Tool invocation is a suspension point: re-fence before sending so an
        // old-generation response is not written onto the successor connection.
        // The result is cached, so the replay answers without re-invoking.
        if (abortStale(request, leaseToken, generation, phase = "beforeSend")) return
        Telemetry.event(
            TELEMETRY_SOURCE, "externalTool.responded",
            "requestId" to request.requestId,
            "toolCallId" to request.toolCallId,
            "toolName" to request.toolName,
            "isError" to (result.isError == true).toString(),
            "handled" to (externalToolRegistry != null).toString(),
            "cached" to (cached != null).toString(),
        )
        runCatching {
            client.sendExternalToolResponse(
                AppServerCommand.ExternalToolCallResponse(requestId = request.requestId, result = result),
            )
        }.onSuccess {
            // Lease-scoped: if this detached invocation lost the claim (released on
            // an earlier send failure, then re-claimed by a successor), retiring the
            // identity here would delete the successor's LIVE claim and strand its
            // response. markAnsweredBy no-ops unless we still own it.
            inboundControlRegistry.markAnsweredBy(ref, leaseToken, generation)
            // lgns8.22.4.1.6: the cached result is deliberately RETAINED. A one-way
            // send is an AmbiguousMutation — if the server never received it, it
            // replays the request and the replay must reuse this result rather than
            // re-invoke the tool. The cache expires the entry itself if no replay
            // ever comes (bounded + TTL).
        }.onFailure {
            Telemetry.error(TELEMETRY_SOURCE, "externalTool.responseSendFailed", it)
            // Keep retriable: server never saw the response and will re-emit.
            // Cached result above prevents re-invoking the tool on replay.
            inboundControlRegistry.releaseClaim(ref, leaseToken, generation)
        }
    }

    /**
     * Invoke the wired handler (or synthesize a matched is_error result when none
     * handles the tool) and cache the outcome.
     *
     * lgns8.22.4.1.6: cached by (request_id, tool_call_id) and NOT by connection
     * generation, so a successor-generation replay reuses it and a non-idempotent
     * tool never runs twice for one request identity.
     */
    private suspend fun computeAndCacheResult(
        request: AppServerInboundFrame.ExternalToolCallRequest,
    ): AppServerExternalToolResult {
        val computed = try {
            // lgns8.17(c): BOUND the invocation. ExternalTool.invoke is arbitrary
            // controller code; without a deadline the only bound is the turn idle
            // watchdog, which a parked approval can pause indefinitely. On expiry we
            // synthesize a matched is_error so the turn still terminates.
            val outcome = if (externalToolRegistry == null) {
                null
            } else {
                withTimeoutOrNull(invocationTimeoutMs.milliseconds) {
                    externalToolRegistry.invoke(request.toolName, request.input)
                } ?: run {
                    Telemetry.event(
                        TELEMETRY_SOURCE, "externalTool.invocationTimedOut",
                        "requestId" to request.requestId,
                        "toolCallId" to request.toolCallId,
                        "toolName" to request.toolName,
                        "timeoutMs" to invocationTimeoutMs,
                        level = Telemetry.Level.WARN,
                    )
                    ExternalToolResult.Error(
                        "external tool '${request.toolName}' timed out after ${invocationTimeoutMs}ms",
                    )
                }
            }
            when (outcome) {
                is ExternalToolResult.Success -> toolResult(outcome.content, isError = false)
                is ExternalToolResult.Error -> toolResult(outcome.error, isError = true)
                null -> toolResult(
                    "external tool '${request.toolName}' is not handled by this controller",
                    isError = true,
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            toolResult(
                "external tool '${request.toolName}' failed: ${e.message ?: e::class.simpleName}",
                isError = true,
            )
        }
        resultCache.put(request.resultCacheKey(), computed)
        return computed
    }

    /**
     * lgns8.22.4.1.2 fence. Returns true (and releases the claim) when the live
     * connection generation has moved past the generation this external-tool
     * request was validated/claimed on.
     */
    private fun abortStale(
        request: AppServerInboundFrame.ExternalToolCallRequest,
        leaseToken: Long,
        generation: Long,
        phase: String,
    ): Boolean {
        if (connectionGenerationProvider() == generation) return false
        Telemetry.event(
            TELEMETRY_SOURCE, "externalTool.staleGenerationAborted",
            "requestId" to request.requestId,
            "toolCallId" to request.toolCallId,
            "toolName" to request.toolName,
            "claimGeneration" to generation,
            "liveGeneration" to connectionGenerationProvider(),
            "phase" to phase,
            level = Telemetry.Level.WARN,
        )
        inboundControlRegistry.releaseClaim(
            InboundControlRequestRegistry.RequestRef(request.requestId, request.toolCallId),
            leaseToken,
            generation,
        )
        return true
    }

    private fun AppServerInboundFrame.ExternalToolCallRequest.resultCacheKey() =
        ExternalToolResultCache.Key(requestId, toolCallId)

    private fun toolResult(text: String, isError: Boolean) = AppServerExternalToolResult(
        content = listOf(AppServerExternalToolResultContent(type = "text", text = text)),
        isError = isError,
    )

    companion object {
        /**
         * Telemetry source is deliberately UNCHANGED by the extraction — dashboards
         * and the appserver-cli evidence probes key off this string.
         */
        private const val TELEMETRY_SOURCE = "AppServerTurnEngine"

        /**
         * lgns8.17(c): deadline for ONE `ExternalTool.invoke`.
         *
         * Chosen against the server's own bound, not picked for feel: letta-code's
         * app-server parks an external tool call on a pending promise with
         * `EXTERNAL_TOOL_CALL_TIMEOUT_MS = 5 * 60 * 1000` and rejects the tool call
         * when it lapses. Our deadline must fire COMFORTABLY FIRST so the App
         * Server receives a matched `is_error` response (the turn then terminates
         * cleanly and the model sees a real tool result) rather than the server
         * self-rejecting on a timeout it attributes to a dead controller. 120s
         * leaves 3 minutes of slack for the send and any queuing, and is also well
         * inside `AppServerTurnEngine.DEFAULT_TURN_IDLE_TIMEOUT_MS` so a hung tool
         * can never be the thing that trips the idle watchdog.
         */
        const val INVOCATION_TIMEOUT_MS: Long = 120_000L
    }
}
