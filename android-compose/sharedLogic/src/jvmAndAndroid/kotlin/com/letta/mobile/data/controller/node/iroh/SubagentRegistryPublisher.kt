package com.letta.mobile.data.controller.node.iroh

import com.letta.mobile.data.model.SubagentEntry
import com.letta.mobile.data.transport.ServerFrame
import com.letta.mobile.util.Telemetry
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json

/** Where the host writes `subagents_updated` frames: the viewers of one conversation. */
fun interface SubagentPushTarget {
    /** Writes [frame] to every connection viewing [conversationId]; returns how many accepted it. */
    suspend fun publish(conversationId: String, frame: String): Int
}

/**
 * letta-mobile-fxoew.2: pushes the host's authoritative subagent registry to the
 * viewers of a conversation.
 *
 * Before this, clients only pulled `subagent.list` and built RUNNING-only chips
 * from their own correlator, so a completion, failure or orphaning on the host
 * never reached the ring. Every registry mutation
 * [ControllerSubagentRegistrySource] reports becomes ONE `subagents_updated`
 * frame ([ServerFrame.SubagentsUpdated], the frame the client's
 * `SubagentRepository` already folds in) carrying the full current snapshot for
 * that (agentId, conversationId), terminal chips in the linger window included.
 * A viewer that starts watching a conversation gets the same snapshot replayed
 * to it alone ([replayTo]).
 *
 * Changes are queued and sent in order by one coroutine owned by [scope]. The
 * snapshot is read at send time, so a burst of mutations converges on the
 * latest state and an older snapshot can never overtake a newer one.
 */
class SubagentRegistryPublisher(
    scope: CoroutineScope,
    private val source: ControllerSubagentRegistrySource,
    private val clock: () -> Instant = Instant::now,
) : SubagentRegistryChangeListener {
    private val changes = Channel<SubagentConversationKey>(Channel.UNLIMITED)

    @Volatile private var target: SubagentPushTarget? = null

    private val sender: Job = scope.launch {
        for (key in changes) send(key)
    }

    fun attach(target: SubagentPushTarget) {
        this.target = target
    }

    override fun onConversationChanged(key: SubagentConversationKey) {
        changes.trySend(key)
    }

    /**
     * Replays [conversationId]'s current snapshot to [viewer] only, one frame
     * per parent agent present. Returns the number of frames written.
     */
    suspend fun replayTo(conversationId: String, viewer: ViewerHandle): Int {
        val everyParent = SubagentConversationKey(agentId = null, conversationId = conversationId)
        val groups = source.pushSnapshot(everyParent, nowMs()).groupBy { it.parentAgentId }
        val written = groups.values.count { entries -> viewer.writeFrame(frame(REASON_REPLAY, entries)) }
        Telemetry.event("SubagentPush", "replay", "conversationId" to conversationId, "frames" to written)
        return written
    }

    /** Stops the sender. The owning scope normally does this; tests call it directly. */
    fun close() {
        changes.close()
        sender.cancel()
    }

    private suspend fun send(key: SubagentConversationKey) {
        val sink = target ?: return
        val entries = source.pushSnapshot(key, nowMs())
        val delivered = sink.publish(key.conversationId, frame(REASON_REGISTRY, entries))
        Telemetry.event(
            "SubagentPush",
            "published",
            "conversationId" to key.conversationId,
            "entries" to entries.size,
            "delivered" to delivered,
        )
    }

    private fun nowMs(): Long = clock().toEpochMilli()

    internal fun frame(reason: String, entries: List<SubagentEntry>): String {
        val at = clock().toString()
        val frame = ServerFrame.SubagentsUpdated(
            id = "subagents-updated-${UUID.randomUUID()}",
            ts = at,
            reason = reason,
            subagentsActive = entries,
            at = at,
        )
        return FRAME_JSON.encodeToString(ServerFrame.SubagentsUpdated.serializer(), frame)
    }

    companion object {
        const val REASON_REGISTRY = "registry"
        const val REASON_REPLAY = "replay"
        private val FRAME_JSON = Json { encodeDefaults = true }
    }
}
