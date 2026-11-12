package com.letta.mobile.data.canvas

import com.letta.mobile.data.model.CanvasId
import com.letta.mobile.data.model.ConversationId

/**
 * Who may read and write a host canvas: its own ACL, or the live mount of the caller's own conversation
 * (owner decision D5, "grant on mount").
 *
 * The mount grant is DERIVED at every check and never stored: [mountedHere] asks [mounts] which canvas the
 * runtime-stamped caller's own conversation is mounted on right now (its binding, else its agent's default) and
 * covers exactly that canvas. No canvas ACL is ever changed, so replacing or removing the mount revokes the access on
 * the very next check. A caller in no conversation, an unreadable mount, and every other canvas get nothing from it.
 * The ACL is consulted first, so a caller its ACL already admits never reads the mount store.
 */
internal class HostCanvasAccessRule(private val mounts: HostCanvasMountLookup) {
    suspend fun mayRead(caller: HostCanvasCaller, entry: HostCanvasEntry): Boolean =
        entry.acl.canRead(caller.agentId) || mountedHere(caller, entry.id)

    suspend fun mayWrite(caller: HostCanvasCaller, entry: HostCanvasEntry): Boolean =
        entry.acl.canWrite(caller.agentId) || mountedHere(caller, entry.id)

    /** True only when [caller]'s own conversation is mounted on [canvas] now. */
    suspend fun mountedHere(caller: HostCanvasCaller, canvas: CanvasId): Boolean {
        if (caller.conversationId == null) return false
        return (mounts.mounted(caller) as? HostCanvasMount.Mounted)?.canvas == canvas
    }
}

/**
 * The read path for a canvas the relay has bound but the host directory does not hold (an app-made canvas, or another
 * conversation's canvas an app opened): a transient entry naming its topic, built from read-only lookups
 * ([CanvasRelayStore.boundCanvas]) so asking can never create a canvas or a binding, and never stored.
 *
 * Its ACL admits no agent (owner the local user, readers that user only, so not public-read): only the mount grant
 * of [HostCanvasAccessRule] lets an agent use it.
 */
internal class RelayOnlyCanvases(private val store: CanvasRelayStore) {
    suspend fun entry(canvas: CanvasId): HostCanvasEntry? {
        val canvasTopic = CanvasRelayProtocol.canvasTopic(canvas)
        if (store.boundCanvas(canvasTopic) == canvas) return transient(canvas, canvasTopic, null)
        val conversation = conversationOf(canvas) ?: return null
        val conversationTopic = CanvasRelayProtocol.conversationTopic(conversation)
        return if (store.boundCanvas(conversationTopic) == canvas) transient(canvas, conversationTopic, conversation) else null
    }

    private fun transient(canvas: CanvasId, topic: String, conversation: ConversationId?) =
        HostCanvasEntry(canvas.value, topic, MOUNTED_TITLE, conversation?.value, NO_AGENT_ACL)

    private fun conversationOf(canvas: CanvasId): ConversationId? =
        canvas.value.removePrefix(CanvasId.CONVERSATION_PREFIX).takeIf { it != canvas.value && it.isNotEmpty() }?.let(::ConversationId)

    private companion object {
        const val MOUNTED_TITLE = "Mounted canvas"

        val NO_AGENT_ACL = CanvasAcl(
            ownerUserId = CanvasSession.LOCAL_USER_ACTOR_ID,
            readerUserIds = setOf(CanvasSession.LOCAL_USER_ACTOR_ID),
        )
    }
}
