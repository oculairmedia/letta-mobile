package com.letta.mobile.clientmode

import com.letta.mobile.data.timeline.SubscriberSuppressionGate
import com.letta.mobile.util.Telemetry
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Tracks which conversations are currently being driven by the Client Mode
 * WS gateway path so that [com.letta.mobile.data.timeline.TimelineSyncLoop]'s
 * resume-stream subscriber stays dormant for them.
 *
 * Background — the doubled-response bug:
 *
 * In Client Mode, the lettabot WS gateway forwards Letta SSE events to the
 * device, which `AdminChatViewModel.handleClientModeStreamChunkViaTimeline`
 * writes into the timeline as `cm-assist-*` Locals (source =
 * CLIENT_MODE_HARNESS). At the same time the `TimelineSyncLoop` keeps an
 * always-on direct-Letta SSE subscriber that, left to itself, would open
 * a parallel stream against the same conversation and ingest the same
 * upstream events as Confirmed bubbles.
 *
 * The fuzzy reconcile path
 * ([com.letta.mobile.data.timeline.Timeline.collapseClientModeFuzzyMatch])
 * is supposed to merge those pairs, but it misses cases (10s window for
 * long replies, content drift between the gateway accumulator and the
 * server's final text, ordering races). Result: two assistant bubbles per
 * turn — the user-visible "doubled response" symptom.
 *
 * This gate suppresses the direct-SSE subscriber on conversations the WS
 * gateway already owns. The gate is implemented as a process-lifetime set:
 * once a conversation is touched via Client Mode it stays owned until
 * Client Mode is disabled. We do NOT release on screen navigation because
 * the gateway can keep streaming in the background.
 *
 * Releases:
 *  - explicit [release] when Client Mode is disabled (called from the
 *    `:app` settings/observe layer);
 *  - [releaseAll] when Client Mode is fully torn down.
 *
 * Implements [SubscriberSuppressionGate] directly so it can be bound into
 * Hilt without an adapter.
 *
 * Plan: see `2026-04-clientmode-double-bubble-fix.md`.
 */
@Singleton
class ClientModeSuppressionGate @Inject constructor() : SubscriberSuppressionGate {
    // ConcurrentHashMap.newKeySet() — safe for the gate's hot path
    // (subscriber loop calls isSuppressed every STREAM_DORMANT_MS) and the
    // mark/release callers from the Client Mode send path.
    private val owned: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /**
     * Mark [conversationId] as owned by the Client Mode WS gateway. After
     * this, the direct-SSE subscriber will stay dormant for that
     * conversation until [release] (or [releaseAll]) is called. Idempotent.
     */
    fun markOwned(conversationId: String) {
        if (conversationId.isBlank()) return
        val added = owned.add(conversationId)
        if (added) {
            Telemetry.event(
                "ClientModeSuppressionGate", "markOwned",
                "conversationId" to conversationId,
                "ownedCount" to owned.size,
            )
        }
    }

    /**
     * Release ownership of [conversationId]. The next iteration of the
     * subscriber loop will re-open the direct SSE for that conversation.
     * Idempotent.
     */
    fun release(conversationId: String) {
        if (conversationId.isBlank()) return
        val removed = owned.remove(conversationId)
        if (removed) {
            Telemetry.event(
                "ClientModeSuppressionGate", "release",
                "conversationId" to conversationId,
                "ownedCount" to owned.size,
            )
        }
    }

    /**
     * Release every conversation currently marked owned. Called when Client
     * Mode is disabled — direct SSE resumes for every previously-gated
     * conversation.
     */
    fun releaseAll() {
        val previous = owned.toList()
        owned.clear()
        if (previous.isNotEmpty()) {
            Telemetry.event(
                "ClientModeSuppressionGate", "releaseAll",
                "releasedCount" to previous.size,
            )
        }
    }

    /** Test helper / inspection: snapshot of currently-owned conversations. */
    fun snapshot(): Set<String> = owned.toSet()

    override fun isSuppressed(conversationId: String): Boolean =
        conversationId.isNotBlank() && conversationId in owned
}
