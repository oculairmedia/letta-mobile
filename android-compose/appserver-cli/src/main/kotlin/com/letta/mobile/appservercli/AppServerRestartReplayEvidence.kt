package com.letta.mobile.appservercli

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Typed model + validator for the observed App Server restart/replay evidence
 * (letta-mobile-lgns8.15). The evidence is captured from executable probes against
 * a real local Letta Code App Server (see [AppServerRestartReplayProbe]); this
 * model lets an always-on test gate it in CI (no live server / key required):
 * it pins the observed letta-code version and enforces internal consistency so
 * the derived reconciliation rules cannot silently drift from the observations.
 */
@Serializable
data class AppServerRestartReplayEvidence(
    @SerialName("schema_version") val schemaVersion: Int = 0,
    val source: Source,
    val durability: Durability,
    @SerialName("identity_scopes") val identityScopes: IdentityScopes,
    @SerialName("mid_turn_kill_before_terminal") val midTurnKill: MidTurnKill,
    val ambiguity: Ambiguity,
    @SerialName("reconciliation_rules") val reconciliationRules: List<ReconciliationRule>,
    @SerialName("anti_anchors") val antiAnchors: List<String>,
    @SerialName("durable_correlation_id") val durableCorrelationId: String,
) {
    @Serializable
    data class Source(val version: String, val backend: String, val model: String)

    @Serializable
    data class Durability(
        @SerialName("process_restart_same_backend_dir") val processRestart: ProcessRestart,
        @SerialName("control_reconnect_same_process") val controlReconnect: ControlReconnect,
    ) {
        @Serializable
        data class ProcessRestart(
            @SerialName("committed_transcript") val committedTranscript: String,
            @SerialName("reattach_created_flags") val reattachCreatedFlags: CreatedFlags,
        )

        @Serializable
        data class ControlReconnect(
            @SerialName("committed_transcript") val committedTranscript: String,
        )

        @Serializable
        data class CreatedFlags(val agent: Boolean, val conversation: Boolean)
    }

    @Serializable
    data class IdentityScopes(
        @SerialName("client_message_id") val clientMessageId: ClientMessageId,
        @SerialName("run_id") val runId: RunId,
        @SerialName("event_seq") val eventSeq: EventSeq,
        @SerialName("idempotency_key") val idempotencyKey: IdempotencyKey,
    ) {
        @Serializable
        data class ClientMessageId(
            @SerialName("durable_across_process_restart") val durable: Boolean,
            @SerialName("server_deduplicated") val serverDeduplicated: Boolean,
        )

        @Serializable
        data class RunId(
            @SerialName("globally_unique_across_restart") val globallyUnique: Boolean,
        )

        @Serializable
        data class EventSeq(@SerialName("durable_cursor") val durableCursor: Boolean)

        @Serializable
        data class IdempotencyKey(@SerialName("stable_across_replay") val stableAcrossReplay: Boolean)
    }

    @Serializable
    data class MidTurnKill(
        @SerialName("user_input_committed") val userInputCommitted: Boolean,
        @SerialName("assistant_message_committed") val assistantMessageCommitted: Boolean,
    )

    @Serializable
    data class Ambiguity(
        @SerialName("unacknowledged_input_after_disconnect") val unacknowledgedInput: String,
    )

    @Serializable
    data class ReconciliationRule(
        val operation: String,
        @SerialName("class") val classification: String,
        val retry: String? = null,
    )

    /**
     * Returns the list of validation violations (empty = valid). Enforces the
     * version pin (incompatible letta-code versions fail the gate) and the
     * internal consistency of the observed scopes vs the derived rules.
     */
    fun validate(expectedVersion: String): List<String> {
        val problems = mutableListOf<String>()
        fun check(condition: Boolean, message: String) {
            if (!condition) problems += message
        }

        check(
            source.version == expectedVersion,
            "version pin mismatch: evidence=${source.version} expected=$expectedVersion (regenerate evidence for the installed letta-code)",
        )
        check(source.backend == "local", "backend must be 'local' but was '${source.backend}'")

        // Durability observations.
        check(durability.processRestart.committedTranscript == "survives", "committed transcript must survive process restart")
        check(!durability.processRestart.reattachCreatedFlags.agent, "reattach after restart must not recreate the agent")
        check(!durability.processRestart.reattachCreatedFlags.conversation, "reattach after restart must not recreate the conversation")
        check(durability.controlReconnect.committedTranscript == "survives", "committed transcript must survive control reconnect")

        // Identity scopes.
        check(identityScopes.clientMessageId.durable, "client_message_id must be durable across process restart")
        check(!identityScopes.clientMessageId.serverDeduplicated, "client_message_id must be observed as NOT server-deduplicated (blind replay duplicates)")
        check(!identityScopes.runId.globallyUnique, "run_id must be observed as NOT globally unique across restart")
        check(!identityScopes.eventSeq.durableCursor, "event_seq must be observed as NOT a durable cursor")
        check(!identityScopes.idempotencyKey.stableAcrossReplay, "idempotency_key must be observed as NOT stable across replay")

        // Mid-turn kill.
        check(midTurnKill.userInputCommitted, "user input must be observed committed at mid-turn kill")
        check(!midTurnKill.assistantMessageCommitted, "assistant message must be observed uncommitted at mid-turn kill")

        // Ambiguity + anti-anchors + durable correlation id.
        check(ambiguity.unacknowledgedInput == "ambiguous", "unacknowledged input after disconnect must be classified ambiguous")
        listOf("run_id", "event_seq", "idempotency_key").forEach { anchor ->
            check(anchor in antiAnchors, "anti_anchors must include '$anchor' (it is not a safe cross-restart idempotency anchor)")
        }
        check(durableCorrelationId == "client_message_id", "durable_correlation_id must be client_message_id")

        // Reconciliation rules the reconnect slice (lgns8.5) consumes.
        val byOp = reconciliationRules.associateBy { it.operation }
        val write = byOp["input/create_message"]
        check(write != null, "reconciliation_rules must classify input/create_message")
        if (write != null) {
            check(write.classification == "ambiguous_write", "input/create_message must be classified ambiguous_write")
            check(write.retry == "do_not_blind_replay", "input/create_message retry policy must be do_not_blind_replay")
        }
        check(byOp["sync"]?.classification == "safe_read", "sync must be classified safe_read")
        check(byOp["runtime_start(reattach)"]?.classification == "safe_read", "runtime_start(reattach) must be classified safe_read")

        return problems
    }

    companion object {
        /**
         * Pinned installed letta-code version this evidence was captured against
         * (matches the lgns8.1 contract baseline). Bumping the installed package
         * requires regenerating the evidence via the live probe.
         */
        const val PINNED_LETTA_CODE_VERSION: String = "0.28.8"

        private val json = Json { ignoreUnknownKeys = true }

        fun parse(rawJson: String): AppServerRestartReplayEvidence =
            json.decodeFromString(serializer(), rawJson)
    }
}
