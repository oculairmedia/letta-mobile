package com.letta.mobile.data.canvas

import com.letta.mobile.data.canvas.mount.lookup.HostCanvasMount
import com.letta.mobile.data.canvas.mount.lookup.HostCanvasMountLookup
import com.letta.mobile.data.model.CanvasId
import com.letta.mobile.data.model.ConversationId

// Who may read and write a host canvas: its own ACL, or the live mount of the caller's own conversation (owner
// decision D5, "grant on mount"). The mount grant is DERIVED at every check and never stored: mountedHere asks the
// lookup which canvas the runtime-stamped caller's own conversation is mounted on right now (its binding, else its
// agent's default) and covers exactly that canvas. No canvas ACL is ever changed, so replacing or removing the mount
// revokes the access on the very next check. A caller in no conversation, an unreadable mount, and every other canvas
// get nothing from it. The ACL is consulted first, so a caller its ACL already admits never reads the mount store. A
// plugin placing for a call is judged as that call's caller, so it gets the same grant while the mount lasts.

/** Whether [caller] may read [entry]: its ACL, or the live mount of the caller's own conversation. */
internal suspend fun HostCanvasMountLookup.mayRead(caller: HostCanvasCaller, entry: HostCanvasEntry): Boolean =
    entry.acl.canRead(caller.agentId) || mountedHere(caller, entry.id)

/** Whether [caller] may write [entry]: its ACL, or the live mount of the caller's own conversation. */
internal suspend fun HostCanvasMountLookup.mayWrite(caller: HostCanvasCaller, entry: HostCanvasEntry): Boolean =
    entry.acl.canWrite(caller.agentId) || mountedHere(caller, entry.id)

/** True only when [caller]'s own conversation is mounted on [canvas] now. */
internal suspend fun HostCanvasMountLookup.mountedHere(caller: HostCanvasCaller, canvas: CanvasId): Boolean =
    (mountOf(caller) as? HostCanvasMount.Mounted)?.canvas == canvas

/** The mount of [caller]'s own conversation; a caller in no conversation has none. */
internal suspend fun HostCanvasMountLookup.mountOf(caller: HostCanvasCaller): HostCanvasMount =
    caller.conversationId?.let { mounted(caller.agentId, it) } ?: HostCanvasMount.None

/** The mounted canvas, typed. */
internal val HostCanvasMount.Mounted.canvas: CanvasId get() = CanvasId(canvasId)

/**
 * The read path for a canvas the relay has bound but the host directory does not hold (an app-made canvas, or another
 * conversation's canvas an app opened): a transient entry naming its topic, built from read-only lookups
 * ([CanvasRelayStore.isBound]) so asking can never create a canvas or a binding, and never stored.
 *
 * Its ACL admits no agent (owner the local user, readers that user only, so not public-read): only the mount grant
 * ([mountedHere]) lets an agent use it.
 */
internal suspend fun relayOnlyEntry(store: CanvasRelayStore, canvas: CanvasId): HostCanvasEntry? {
    val canvasTopic = CanvasRelayProtocol.canvasTopic(canvas)
    if (store.isBound(canvas)) return transientEntry(canvas, canvasTopic, null)
    val conversation = canonicalConversationOf(canvas) ?: return null
    val conversationTopic = CanvasRelayProtocol.conversationTopic(conversation)
    return if (store.isBound(canvas, conversation)) transientEntry(canvas, conversationTopic, conversation) else null
}

private fun transientEntry(canvas: CanvasId, topic: String, conversation: ConversationId?) =
    HostCanvasEntry(canvas.value, topic, MOUNTED_TITLE, conversation?.value, NO_AGENT_ACL)

private fun canonicalConversationOf(canvas: CanvasId): ConversationId? =
    canvas.value.removePrefix(CanvasId.CONVERSATION_PREFIX).takeIf { it != canvas.value && it.isNotEmpty() }?.let(::ConversationId)

private const val MOUNTED_TITLE = "Mounted canvas"

private val NO_AGENT_ACL = CanvasAcl(
    ownerUserId = CanvasSession.LOCAL_USER_ACTOR_ID,
    readerUserIds = setOf(CanvasSession.LOCAL_USER_ACTOR_ID),
)
