package com.letta.mobile.data.controller.node.iroh.mounts

import com.letta.mobile.data.canvas.CanvasRelayStore
import com.letta.mobile.data.canvas.HostCanvasDirectory
import com.letta.mobile.data.canvas.HostCanvasEntry
import com.letta.mobile.data.controller.node.iroh.mounts.directory.CanvasMountDirectory
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.CanvasId
import com.letta.mobile.data.model.ConversationId

/** Whether a canvas exists on this host and which agents own it, by the host's own records only. */
internal sealed interface CanvasOwnership {
    /** The host has no such canvas. */
    data object Absent : CanvasOwnership

    /** The canvas exists; [agents] own it. Empty means no owner is known. */
    data class Owned(val agents: Set<AgentId>) : CanvasOwnership
}

/**
 * The host's canvas records the mount visibility rule (owner decision D3) reads: its canvas directory, its relay
 * store and its conversation records. Request fields and document content (titles, ACLs inside a scene) are never
 * consulted. A failed lookup throws; the authority then denies a proposal, and answers any other check
 * `unavailable` / `authority`.
 */
class CanvasMountCatalog(
    /** The host's agent canvas directory (`host-canvases.json`), written only by the host canvas tools. */
    private val canvases: HostCanvasDirectory,
    /** The relay's topic bindings, read without creating any. */
    private val relay: CanvasRelayStore,
) {
    /**
     * In order: a directory entry for a conversation is owned by that conversation's agent; any other directory entry
     * by the agents its ACL names as writers (the creating agent, stamped by the runtime); a conversation's canonical
     * canvas the relay has bound is owned by that conversation's agent; any other canvas the relay has bound has no
     * known owner; anything else does not exist.
     *
     * The conversation owner lookup (the only one that leaves the host) runs before existence is decided, for the
     * entry's conversation or else the conversation a canonical-shaped id names, so a missing canvas and another
     * agent's take the same path and fail the same way.
     */
    internal suspend fun ownership(canvas: CanvasId, directory: CanvasMountDirectory): CanvasOwnership {
        val entry = canvases.get(canvas)
        val conversation = entry?.conversationId?.let(::ConversationId) ?: conversationOf(canvas)
        val owner = conversation?.let { directory.ownerOf(it) }
        if (entry != null) return if (entry.conversationId != null) owned(owner) else writers(entry.acl.writerAgentIds)
        if (conversation != null && relay.isBound(canvas, conversation)) {
            return owned(owner)
        }
        val bound = relay.isBound(canvas)
        return if (bound) CanvasOwnership.Owned(emptySet()) else CanvasOwnership.Absent
    }

    /**
     * Whether [conversation]'s canonical canvas id is still unused on this host or is held through [conversation]
     * itself: its directory entry belongs to [conversation], or the relay bound `conversation:<conversation>` to it.
     * False when the id is recorded for any other conversation or board, or bound only under another topic, since any
     * signed-in app may bind a topic to an id that names a different conversation.
     */
    internal suspend fun canonicalHeldBy(conversation: ConversationId): Boolean {
        val canvas = CanvasId.forConversation(conversation)
        canvases.get(canvas)?.let { entry -> return entry.conversationId == conversation.value }
        if (relay.isBound(canvas, conversation)) return true
        return !relay.isBound(canvas)
    }

    /** Every canvas the host's agent canvas directory records, the only canvases `canvas.mount.candidates` enumerates. */
    internal suspend fun known(): List<HostCanvasEntry> = canvases.all()

    /** This catalog with its directory answered from [entries], loaded once for one call, instead of re-read per canvas. */
    internal fun over(entries: List<HostCanvasEntry>): CanvasMountCatalog = CanvasMountCatalog(LoadedCanvases(entries), relay)

    private fun writers(agents: Set<String>): CanvasOwnership = CanvasOwnership.Owned(agents.mapTo(mutableSetOf(), ::AgentId))

    private fun owned(agent: AgentId?): CanvasOwnership = CanvasOwnership.Owned(setOfNotNull(agent))

    private fun conversationOf(canvas: CanvasId): ConversationId? =
        canvas.value.removePrefix(CanvasId.CONVERSATION_PREFIX).takeIf { it != canvas.value && it.isNotEmpty() }?.let(::ConversationId)
}

/** A read-only view of directory entries loaded once; a mount authority never writes the directory. */
private class LoadedCanvases(entries: List<HostCanvasEntry>) : HostCanvasDirectory {
    private val byId = entries.associateBy { it.canvasId }

    override suspend fun get(canvasId: CanvasId): HostCanvasEntry? = byId[canvasId.value]

    override suspend fun forConversation(conversationId: ConversationId): HostCanvasEntry? =
        byId.values.firstOrNull { it.conversationId == conversationId.value }

    override suspend fun putIfAbsent(entry: HostCanvasEntry): HostCanvasEntry = error("loaded canvas entries are read-only")

    override suspend fun all(): List<HostCanvasEntry> = byId.values.toList()
}
