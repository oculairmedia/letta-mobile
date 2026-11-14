package com.letta.mobile.data.canvas

import com.letta.mobile.data.canvas.mount.lookup.HostCanvasMount
import com.letta.mobile.data.canvas.mount.lookup.HostCanvasMountLookup
import com.letta.mobile.data.controller.extras.identity.CallerIdentity
import com.letta.mobile.data.model.CanvasId
import com.letta.mobile.data.model.ConversationId

// Who may read and write a host canvas: its own ACL, or the live mount of the caller's own conversation (owner
// decision D5, "grant on mount"). The mount grant is DERIVED at every check and never stored: mountedHere asks the
// lookup which canvas the caller's own conversation is mounted on right now (its binding, else its agent's default)
// and covers exactly that canvas. Only a caller whose scope the runtime stamped (or a live-call token vouched for)
// has a mount at all: a claimed scope (shell environment, arguments, a client) gets no grant and no mount-based board
// choice, exactly as on a host without mounts, since anyone can claim any agent and conversation. No canvas ACL is ever changed, so replacing or removing the mount
// revokes the access on the very next check. A caller in no conversation, an unreadable mount, and every other canvas
// get nothing from it. The ACL is consulted first, so a caller its ACL already admits never reads the mount store. A
// plugin placing for a call is judged as that call's caller, so it gets the same grant while the mount lasts.

/** Whether [caller] may read [entry]: its ACL, or the live mount of the caller's own conversation. */
internal suspend fun HostCanvasMountLookup<HostCanvasCaller>.mayRead(caller: HostCanvasCaller, entry: HostCanvasEntry): Boolean =
    entry.acl.canRead(caller.agentId) || mountedHere(caller, entry.id)

/** Whether [caller] may write [entry]: its ACL, or the live mount of the caller's own conversation. */
internal suspend fun HostCanvasMountLookup<HostCanvasCaller>.mayWrite(caller: HostCanvasCaller, entry: HostCanvasEntry): Boolean =
    entry.acl.canWrite(caller.agentId) || mountedHere(caller, entry.id)

/** True only when [caller]'s own conversation is mounted on [canvas] now. */
internal suspend fun HostCanvasMountLookup<HostCanvasCaller>.mountedHere(caller: HostCanvasCaller, canvas: CanvasId): Boolean =
    (mountOf(caller) as? HostCanvasMount.Mounted)?.canvas == canvas

/**
 * The mount of [caller]'s own conversation. A caller in no conversation, or whose scope is only claimed
 * ([CallerIdentity.CLAIMED]), has none: the lookup is not even asked.
 */
internal suspend fun HostCanvasMountLookup<HostCanvasCaller>.mountOf(caller: HostCanvasCaller): HostCanvasMount =
    if (caller.followsMounts) mounted(caller) else HostCanvasMount.None

/** Whether [this] caller's scope may follow its conversation's mount: stamped by the runtime, in a conversation. */
private val HostCanvasCaller.followsMounts: Boolean
    get() = conversationId != null && identity == CallerIdentity.VERIFIED

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

/**
 * The relay-only entry of [mounted], when [caller]'s conversation is mounted on a canvas that is neither its own nor in
 * the directory ([known]); null otherwise.
 */
internal suspend fun relayOnlyMount(
    store: CanvasRelayStore,
    caller: HostCanvasCaller,
    mounted: CanvasId?,
    known: List<HostCanvasEntry>,
): HostCanvasEntry? {
    val ownCanvas = caller.conversationId?.let { CanvasId.forConversation(ConversationId(it)) }
    val listedAlready = mounted == ownCanvas || known.any { it.id == mounted }
    return mounted?.takeUnless { listedAlready }?.let { relayOnlyEntry(store, it) }
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
