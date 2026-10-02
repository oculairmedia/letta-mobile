package com.letta.mobile.data.canvas

/**
 * What a relay peer reads beyond the ops protocol v1 started with (letta-mobile-s416w.5).
 *
 * The relay frame is versioned, but a peer built before an op type existed cannot decode that op at
 * all: the frame is malformed to it, and the host refuses the connection, or the app drops it and
 * replays the same catch-up on every reconnect. So each side says what it reads, in [CanvasRelayMessage.Join]
 * and [CanvasRelayMessage.Joined] (old peers send neither and ignore both), and nothing is sent to a
 * peer that cannot read it:
 *
 *  - a host skips the ops an app did not say it reads, in catch-up and in fan-out, and sends a batch
 *    without them ([forPeer]); the app keeps everything else, and never sees what it cannot draw;
 *  - an app holds back its own ops the host did not say it reads: they stay queued, never refused,
 *    and go up once the host is redeployed with a build that reads them.
 */
object CanvasRelayFeatures {
    /** `set_plugin_element` / `remove_plugin_element` (letta-mobile-s416w.1). */
    const val PLUGIN_ELEMENTS: String = "plugin_elements"

    /** Everything this build reads: what it says in every Join and Joined. */
    val SUPPORTED: List<String> = listOf(PLUGIN_ELEMENTS)

    /** The features a reader of [op] needs, a batch's included. */
    fun required(op: CanvasOp): Set<String> = when (op) {
        is CanvasOp.SetPluginElementOp, is CanvasOp.RemovePluginElementOp -> setOf(PLUGIN_ELEMENTS)
        is CanvasOp.BatchOp -> op.ops.flatMapTo(mutableSetOf(), ::required)
        else -> emptySet()
    }

    /** Whether a peer that reads [features] can decode [op]. */
    fun readableBy(op: CanvasOp, features: Collection<String>): Boolean = features.containsAll(required(op))

    /**
     * [op] as a peer that reads [features] can take it: whole when it can, a batch without the ops
     * it cannot read, or null when nothing of it is left.
     */
    fun forPeer(op: CanvasOp, features: Collection<String>): CanvasOp? = when {
        readableBy(op, features) -> op
        op is CanvasOp.BatchOp -> op.ops.mapNotNull { forPeer(it, features) }.takeIf { it.isNotEmpty() }?.let { op.copy(ops = it) }
        else -> null
    }
}
