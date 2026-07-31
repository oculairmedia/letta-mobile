package com.letta.mobile.data.runtime

import com.letta.mobile.util.Telemetry
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized

/**
 * letta-mobile-lgns8.22.5: runtime-key-scoped store of OUTSTANDING user-input
 * approval gates (`tool_call_id -> real approval id`).
 *
 * WHAT THIS OWNS — AND WHAT IT DELIBERATELY DOES NOT
 * This registry owns exactly one thing: which `{agent, conversation}` runtime is
 * currently parked on which interactive tool call, and the REAL `can_use_tool`
 * control-request id (e.g. `perm-call_…`) needed to answer it. That id is NOT
 * derivable from the tool_call_id across LLM providers (`call_…` vs `toolu_…`),
 * which is why it must be captured when the approval is surfaced rather than
 * reconstructed at submit time.
 *
 * Claim/lease/connection-generation ownership of inbound control requests stays
 * in [com.letta.mobile.data.controller.fanout.InboundControlRequestRegistry]
 * (lgns8.22.4 / PR #1040 + #1061). Duplicating a generation fence here would
 * create a second, divergent source of truth for the same request identity.
 *
 * WHY IT IS KEYED BY RUNTIME
 * letta-mobile-vilsn.6 made the gate set double as the idle-watchdog pause
 * signal, and letta-mobile-8xxzv keyed it per runtime so an unanswered
 * AskUserQuestion in conversation A can neither pause conversation B's watchdog
 * nor be cleared by B's terminal. Both properties are preserved verbatim: every
 * mutation a turn performs is scoped to its own [TurnRuntimeKey].
 *
 * The two by-tool-call-id lookups ([approvalIdFor], [clearIfMatches]) scan every
 * key on purpose — tool_call_ids are globally unique, and the submit path
 * (DefaultAppServerController / DesktopHybridAppServerChatGateway) knows the
 * tool_call_id but not the runtime key.
 *
 * Extracted from `AppServerTurnEngine` + `TurnLeaseSlot.approvalIds` with no
 * behaviour change.
 */
internal class ApprovalRegistry(private val cap: Int = MAX_TRACKED_RUNTIME_KEYS) {
    private val lock = SynchronizedObject()

    /**
     * Recency-ordered so the overflow victim is the least recently touched
     * runtime. Only non-empty gate maps are retained: a key whose last gate
     * resolves is removed outright, so an idle client tracks nothing.
     */
    private val gates = linkedMapOf<TurnRuntimeKey, Map<String, String>>()

    /**
     * One parked interactive tool call. [approvalId] is the REAL can_use_tool
     * control-request id; pairing the two in a type keeps callers from
     * transposing two same-typed identifiers at the call site.
     */
    data class Gate(val toolCallId: String, val approvalId: String)

    /**
     * Record a surfaced [gate] for [key]. Called when a non-auto-approved
     * AskUserQuestion / ExitPlanMode approval reaches the collect body, from
     * either the ControlRequest path or the streamed `approval_request_message`
     * path (letta-mobile-vilsn.7).
     */
    fun record(key: TurnRuntimeKey, gate: Gate) {
        synchronized(lock) {
            val current = gates.remove(key) ?: emptyMap()
            gates[key] = current + (gate.toolCallId to gate.approvalId)
            evictOverflowLocked(keep = key)
        }
    }

    /**
     * Resolve ONE gate on [key] (a matching tool_return was observed). No-op when
     * the submit path already consumed it.
     */
    fun resolve(key: TurnRuntimeKey, toolCallId: String) {
        synchronized(lock) {
            val current = gates[key] ?: return
            val next = current - toolCallId
            if (next.isEmpty()) gates.remove(key) else gates[key] = next
        }
    }

    /**
     * Definitive end of [key]'s turn (terminal, settle, idle timeout,
     * cancellation, stream error): drop every parked gate for that runtime so
     * none leaks into a later turn and keeps a fresh watchdog wrongly paused.
     * Scoped to one key — a sibling runtime's parked question survives.
     */
    fun clearKey(key: TurnRuntimeKey) {
        synchronized(lock) { gates.remove(key) }
    }

    /**
     * True while [key] owes the user an answer. This is the idle-watchdog pause
     * signal (letta-mobile-vilsn.6).
     */
    fun hasOutstanding(key: TurnRuntimeKey): Boolean =
        synchronized(lock) { gates[key]?.isNotEmpty() == true }

    /** Snapshot of [key]'s outstanding gates (`tool_call_id -> approval id`). */
    fun outstanding(key: TurnRuntimeKey): Map<String, String> =
        synchronized(lock) { gates[key] ?: emptyMap() }

    /**
     * READ the recorded real approval id for [toolCallId] across every runtime,
     * WITHOUT removing it.
     *
     * Deliberately not consume-on-read: if `client.input` fails on a transient
     * disconnect the id must survive so the user's retry still targets the real
     * gate. [clearIfMatches] removes it only after the response is actually sent.
     */
    fun approvalIdFor(toolCallId: String): String? = synchronized(lock) {
        gates.values.firstNotNullOfOrNull { it[toolCallId] }
    }

    /**
     * Consume [gate]'s tool call on whichever runtime holds it, but ONLY when
     * the recorded id still equals the gate's approval id. A mismatch means the
     * gate was already re-surfaced under a newer approval id, which the
     * successful send for the OLD id must not delete.
     */
    fun clearIfMatches(gate: Gate) {
        synchronized(lock) {
            val victims = gates.entries
                .filter { it.value[gate.toolCallId] == gate.approvalId }
                .map { it.key }
            for (key in victims) {
                val next = (gates[key] ?: continue) - gate.toolCallId
                if (next.isEmpty()) gates.remove(key) else gates[key] = next
            }
        }
    }

    /** Runtime keys currently holding at least one gate. */
    fun trackedCount(): Int = synchronized(lock) { gates.size }

    private fun evictOverflowLocked(keep: TurnRuntimeKey) {
        while (gates.size > cap) {
            val victim = gates.keys.firstOrNull { it != keep } ?: return
            val dropped = gates.remove(victim)?.size ?: 0
            // Repo convention: a bounded collection reports what it drops. This is
            // the same bound the lease registry applies, and matches the previous
            // storage (gates lived on TurnLeaseSlot, which evicts at the same cap).
            Telemetry.event(
                "ApprovalRegistry", "gate.evictedOverCap",
                "key" to victim.toString(),
                "gates" to dropped,
                "cap" to cap,
                level = Telemetry.Level.WARN,
            )
        }
    }

    companion object {
        /** Same cap as [TurnLeaseRegistry], which previously held these gates. */
        const val MAX_TRACKED_RUNTIME_KEYS: Int = TurnLeaseRegistry.MAX_TRACKED_RUNTIME_KEYS
    }
}
