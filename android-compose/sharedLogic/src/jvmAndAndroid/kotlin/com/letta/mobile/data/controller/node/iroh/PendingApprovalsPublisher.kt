package com.letta.mobile.data.controller.node.iroh

import com.letta.mobile.data.runtime.ApprovalRelay
import com.letta.mobile.data.runtime.PendingApprovalDetails
import com.letta.mobile.data.runtime.TurnRuntimeKey
import com.letta.mobile.util.Telemetry
import java.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** Where the host writes `pending_approvals` frames: the viewers of one conversation. */
fun interface PendingApprovalsPushTarget {
    /** Writes [frame] to every connection viewing [conversationId]; returns how many accepted it. */
    suspend fun publish(conversationId: String, frame: String): Int
}

/**
 * letta-mobile-bzvro.40: pushes what this host's engine has parked on the person to the viewers of
 * each conversation, as [ApprovalRelay] `pending_approvals` snapshots.
 *
 * The node answers its App Server's `can_use_tool` control requests itself, so the always-allow rules,
 * blocked path and diffs a request offered never reached an Iroh client, and under any mode stricter
 * than Unrestricted a parked call had no card on the phone at all. Every change of [source] becomes
 * one frame per conversation whose list changed (an emptied list included, which clears the client's
 * cards), and a viewer that starts watching a conversation gets its current list replayed to it
 * alone ([replayTo]), which is also how a reconnecting client rebuilds its pending set.
 *
 * [source] is a StateFlow, so a burst of parks and answers conflates to the latest snapshot; one
 * collector owned by [scope] sends in order, so an older snapshot never overtakes a newer one.
 */
class PendingApprovalsPublisher(
    scope: CoroutineScope,
    private val source: StateFlow<Map<TurnRuntimeKey, List<PendingApprovalDetails>>>,
    private val clock: () -> Instant = Instant::now,
) {
    @Volatile private var target: PendingApprovalsPushTarget? = null

    /** Only the collector touches this: what each runtime's viewers were last sent. */
    private val lastSent = mutableMapOf<TurnRuntimeKey, List<PendingApprovalDetails>>()

    private val collector: Job = scope.launch {
        source.collect { snapshot -> publishChanges(snapshot) }
    }

    fun attach(target: PendingApprovalsPushTarget) {
        this.target = target
    }

    /**
     * Replays [conversationId]'s parked requests to [viewer] only, an empty snapshot when there are
     * none: a client that kept cards across a disconnect (so the person could still answer them
     * offline) learns here which of them the host no longer holds. Returns the number of frames written.
     */
    suspend fun replayTo(conversationId: String, viewer: ViewerHandle): Int {
        val parked = source.value.filter { (key, details) -> key.conversationId == conversationId && details.isNotEmpty() }
        val frames = parked.map { (key, details) -> frame(key, details) }
            .ifEmpty { listOf(frame(TurnRuntimeKey(agentId = "", conversationId = conversationId), emptyList())) }
        val written = frames.count { viewer.writeFrame(it) }
        Telemetry.event("ApprovalPush", "replay", "frames" to written, "entries" to parked.values.sumOf { it.size })
        return written
    }

    /** Stops the collector. The owning scope normally does this; tests call it directly. */
    fun close() {
        collector.cancel()
    }

    private suspend fun publishChanges(snapshot: Map<TurnRuntimeKey, List<PendingApprovalDetails>>) {
        val sink = target ?: return
        (lastSent.keys + snapshot.keys).forEach { key ->
            val current = snapshot[key].orEmpty()
            if (lastSent[key].orEmpty() == current) return@forEach
            val delivered = sink.publish(key.conversationId, frame(key, current))
            if (current.isEmpty()) lastSent.remove(key) else lastSent[key] = current
            Telemetry.event("ApprovalPush", "published", "entries" to current.size, "delivered" to delivered)
        }
    }

    internal fun frame(key: TurnRuntimeKey, details: List<PendingApprovalDetails>): String =
        ApprovalRelay.encodeFrame(key.agentId, key.conversationId, details, clock().toString()).toString()
}
