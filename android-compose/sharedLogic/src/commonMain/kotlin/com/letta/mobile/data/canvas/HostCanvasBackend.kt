package com.letta.mobile.data.canvas

import com.letta.mobile.data.canvas.plugin.PluginKindCatalog
import com.letta.mobile.data.model.CanvasActorId
import com.letta.mobile.data.model.CanvasId
import com.letta.mobile.data.model.CanvasOpId
import com.letta.mobile.data.model.ConversationId
import kotlinx.coroutines.channels.Channel
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Who is calling a host canvas tool: the runtime scope the App Server stamped, never the input.
 * [toolCallId] is the call's own id in the run, for a tool that must answer
 * a retried call the same way.
 */
data class HostCanvasCaller(val agentId: String, val conversationId: String? = null, val toolCallId: String? = null) {
    /** [agentId] as the actor its canvas ops are stamped with. */
    val actor: CanvasActorId get() = CanvasActorId(agentId)
}

/** A canvas's scene as the host's log has it: [revision] is the log's head cursor. */
data class HostCanvasScene(val sceneJson: String, val revision: Long, val lamport: Long)

/** What a caller may do with a canvas it asked for. */
sealed interface HostCanvasAccess {
    data class Granted(val entry: HostCanvasEntry) : HostCanvasAccess

    data class Denied(val reason: String) : HostCanvasAccess
}

/**
 * The host's side of the `canvas_*` tools: canvases read from and written to
 * the relay's durable op log, so an agent running on the host draws on the same boards the apps
 * share, whether or not any app is connected.
 *
 * - A scene is the projection of its topic's log, from the first op; nothing else holds a copy.
 * - An agent's ops enter the log the way an app's do: through a relay connection of its own
 *   (origin `agent:<id>`), so they are stored, acknowledged and fanned out to every app on the
 *   board by the same code.
 * - Who may read and write each canvas is in [directory]. An agent may claim
 *   the canvas of the conversation its runtime is in, and no other: the App Server's runtime scope
 *   is the proof that the conversation is its own.
 * - A plugin element's props are held to [kinds], the plugin kinds this host has installed.
 * - A call that names no canvas acts on the canvas [mounts] says the caller's own conversation is mounted on,
 *   else that conversation's own canvas.
 * - Owner decision D5: the canvas the caller's own conversation is mounted on NOW may also be read and written by
 *   the caller, whatever its ACL says and even when only the relay holds it ([HostCanvasAccessRule],
 *   [RelayOnlyCanvases]). The grant is derived from the live mount at each check and never stored.
 */
@OptIn(ExperimentalUuidApi::class)
class HostCanvasBackend(
    private val relay: CanvasRelayHost,
    private val store: CanvasRelayStore,
    private val directory: HostCanvasDirectory,
    private val newOpId: () -> CanvasOpId = { CanvasOpId("agent-op-${Uuid.random()}") },
    private val ackTimeout: Duration = DEFAULT_ACK_TIMEOUT,
    private val kinds: PluginKindCatalog = PluginKindCatalog.Empty,
    private val mounts: HostCanvasMountLookup = HostCanvasMountLookup.None,
) {
    private val access = HostCanvasAccessRule(mounts)
    private val relayOnly = RelayOnlyCanvases(store)

    /**
     * The canvas [canvasId] for [caller]: known to the directory, its own conversation's, or a canvas only the relay
     * holds that the caller's own conversation is mounted on now (never created by asking).
     */
    suspend fun open(caller: HostCanvasCaller, canvasId: CanvasId): HostCanvasAccess {
        directory.get(canvasId)?.let { return readable(caller, it) }
        val own = caller.conversationId?.let(::ConversationId)
        if (own != null && canvasId == CanvasId.forConversation(own)) return claimConversation(caller, own)
        if (access.mountedHere(caller, canvasId)) relayOnly.entry(canvasId)?.let { return HostCanvasAccess.Granted(it) }
        return HostCanvasAccess.Denied("Canvas not found: $canvasId")
    }

    /**
     * The canvas of [conversationId]. The caller's own conversation always has one (claimed on first
     * use); another conversation's is the caller's only if the directory lets it read, and otherwise
     * a refusal when the caller asked to [claim] it, or null (nothing to show) when it only looked.
     */
    suspend fun conversation(caller: HostCanvasCaller, conversationId: ConversationId, claim: Boolean): HostCanvasAccess? {
        directory.forConversation(conversationId)?.let { return readable(caller, it) }
        if (conversationId.value == caller.conversationId) return claimConversation(caller, conversationId)
        return if (claim) HostCanvasAccess.Denied(notOwnConversation(caller, conversationId)) else null
    }

    /**
     * The canvas a call that names none acts on: the one the caller's own conversation is mounted on, else
     * that conversation's own canvas (claimed, so created, on first use); null when the caller is in no
     * conversation. The mount itself grants the caller access (D5); a mount that cannot be read, or a mounted
     * canvas the host does not have, is refused, never swapped for another board.
     */
    suspend fun ownConversation(caller: HostCanvasCaller): HostCanvasAccess? {
        val own = caller.conversationId?.let(::ConversationId) ?: return null
        return when (val mounted = mounts.mounted(caller)) {
            HostCanvasMount.None -> conversation(caller, own, claim = true)
            is HostCanvasMount.Mounted -> when (mounted.canvas) {
                CanvasId.forConversation(own) -> conversation(caller, own, claim = true)
                else -> open(caller, mounted.canvas)
            }
            HostCanvasMount.Unavailable -> HostCanvasAccess.Denied(MOUNT_UNAVAILABLE)
        }
    }

    /** The canvas [caller]'s own conversation shows now (see [ownConversation]), or null when it is unknown. */
    suspend fun currentCanvas(caller: HostCanvasCaller): CanvasId? {
        val own = caller.conversationId?.let(::ConversationId) ?: return null
        return when (val mounted = mounts.mounted(caller)) {
            HostCanvasMount.None -> CanvasId.forConversation(own)
            is HostCanvasMount.Mounted -> mounted.canvas
            HostCanvasMount.Unavailable -> null
        }
    }

    /** A new canvas of no conversation, owned by the local user, the caller its writer. */
    suspend fun create(caller: HostCanvasCaller, title: String): HostCanvasEntry {
        val id = CanvasId.generate()
        val topic = CanvasRelayProtocol.canvasTopic(id)
        val bound = store.bind(topic, id)
        return directory.putIfAbsent(HostCanvasEntry(bound.value, topic, title, acl = aclFor(caller)))
    }

    /** Every canvas [caller] may read, its own conversation's and the one it is mounted on now included. */
    suspend fun list(caller: HostCanvasCaller): List<HostCanvasEntry> {
        caller.conversationId?.let { conversation(caller, ConversationId(it), claim = false) }
        val known = directory.all()
        // One mount lookup for the whole list; the grant covers only the canvas it names.
        val mounted = caller.conversationId?.let { (mounts.mounted(caller) as? HostCanvasMount.Mounted)?.canvas }
        val readable = known.filter { it.acl.canRead(caller.agentId) || it.id == mounted }
        val ownCanvas = caller.conversationId?.let { CanvasId.forConversation(ConversationId(it)) }
        if (mounted == null || mounted == ownCanvas || known.any { it.id == mounted }) return readable
        return readable + listOfNotNull(relayOnly.entry(mounted))
    }

    /** Whether [caller] may write [entry] now: its ACL, or the live mount of its own conversation (D5). */
    internal suspend fun mayWrite(caller: HostCanvasCaller, entry: HostCanvasEntry): Boolean = access.mayWrite(caller, entry)

    /** [entry]'s scene, projected from its whole log. */
    suspend fun scene(entry: HostCanvasEntry): HostCanvasScene {
        val ops = mutableListOf<CanvasOp>()
        var cursor = 0L
        while (true) {
            val page = store.readAfter(entry.topic, cursor)
            if (page.isEmpty()) break
            page.forEach { ops += it.op }
            cursor = page.last().cursor
        }
        return HostCanvasScene(
            sceneJson = CanvasOpProjector.project("", ops),
            revision = cursor,
            lamport = ops.maxOfOrNull { it.lamport } ?: 0L,
        )
    }

    /**
     * Publishes [ops] to [entry] as [caller], through a relay connection of the caller's own: each is
     * rebound to the caller (whatever actor it named) and stamped after everything in the log, so
     * it wins over what the caller read. Returns the log's head once the relay has acknowledged
     * every op.
     *
     * The batch is all or nothing ([check]): elements the apps cannot draw
     * and a board state they cannot draw as meant refuse the whole of it
     * before anything is sent. Published, a bad op would be acknowledged, logged and fanned out.
     *
     * [atomic] also makes the write itself all or nothing: the ops go to the
     * relay as one [CanvasOp.BatchOp] ([CanvasStampedBatch]), appended, acknowledged and fanned out
     * as one log entry, so a relay that fails mid-write leaves none of them. Sent op by op, a
     * failure after the first ack leaves the ones before it on the board.
     */
    suspend fun publish(
        caller: HostCanvasCaller,
        entry: HostCanvasEntry,
        ops: List<CanvasOp>,
        atomic: Boolean = false,
    ): HostCanvasPublish =
        when (val checked = check(caller, entry, ops)) {
            is HostCanvasCheck.Denied -> HostCanvasPublish.Denied(checked.reason)
            is HostCanvasCheck.Checked -> when (val result = checked.result) {
                is CanvasBatchCheck.Invalid -> HostCanvasPublish.Invalid(result.message)
                is CanvasBatchCheck.Valid -> send(caller, entry, result.ops, atomic)
            }
        }

    /**
     * What publishing [ops] would do, without publishing them: the batch applied to a scratch copy
     * of [entry]'s scene and the result checked ([CanvasBatchValidator]). A `dry_run` call answers
     * with this; [publish] refuses on it.
     */
    suspend fun check(caller: HostCanvasCaller, entry: HostCanvasEntry, ops: List<CanvasOp>): HostCanvasCheck {
        if (!access.mayWrite(caller, entry)) {
            return HostCanvasCheck.Denied("Unauthorized: actor '${caller.agentId}' cannot write to canvas '${entry.canvasId}'")
        }
        val scene = scene(entry)
        return HostCanvasCheck.Checked(scene.revision, CanvasBatchValidator.check(scene.sceneJson, ops.map { it.withActor(caller.actor) }, kinds))
    }

    private suspend fun send(caller: HostCanvasCaller, entry: HostCanvasEntry, ops: List<CanvasOp>, atomic: Boolean): HostCanvasPublish {
        val replies = Channel<CanvasRelayMessage>(Channel.UNLIMITED)
        val link = relay.connect(CanvasRelayProtocol.AGENT_ORIGIN_PREFIX + caller.agentId) { replies.send(it) }
        try {
            link.receive(
                CanvasRelayMessage.Join(entry.topic, entry.canvasId, store.head(entry.topic), CanvasRelayFeatures.SUPPORTED),
            )
            val lamport = scene(entry).lamport
            val stamped = if (atomic) {
                listOf(CanvasStampedBatch.of(ops, caller.actor, lamport, newOpId))
            } else {
                CanvasStampedBatch.separately(ops, caller.actor, lamport, newOpId)
            }
            stamped.forEach { link.receive(CanvasRelayMessage.Publish(entry.topic, it)) }
            return HostCanvasAcks(stamped).await(replies, ackTimeout)
        } finally {
            link.close()
            replies.close()
        }
    }

    /**
     * Claims [conversationId]'s canonical canvas for [caller]. A relay that already bound the conversation's topic to
     * any other id (any signed-in app may propose one) is refused as not found and nothing is recorded: adopting it
     * would let a canvas id that names another conversation be stored as this one's.
     */
    private suspend fun claimConversation(caller: HostCanvasCaller, conversationId: ConversationId): HostCanvasAccess {
        val topic = CanvasRelayProtocol.conversationTopic(conversationId)
        val canonical = CanvasId.forConversation(conversationId)
        val bound = store.bind(topic, canonical)
        if (bound != canonical) return HostCanvasAccess.Denied("Canvas not found: $canonical")
        val entry = directory.putIfAbsent(
            HostCanvasEntry(bound.value, topic, CONVERSATION_CANVAS_TITLE, conversationId.value, aclFor(caller)),
        )
        return readable(caller, entry)
    }

    private suspend fun readable(caller: HostCanvasCaller, entry: HostCanvasEntry): HostCanvasAccess =
        if (access.mayRead(caller, entry)) {
            HostCanvasAccess.Granted(entry)
        } else {
            HostCanvasAccess.Denied("Unauthorized: actor '${caller.agentId}' cannot read canvas '${entry.canvasId}'")
        }

    private fun notOwnConversation(caller: HostCanvasCaller, conversationId: ConversationId): String =
        "Unauthorized: actor '${caller.agentId}' cannot open the canvas of conversation '$conversationId'"

    private companion object {
        const val CONVERSATION_CANVAS_TITLE = "Conversation canvas"
        const val MOUNT_UNAVAILABLE = "The canvas mounted for this conversation could not be read; name a canvas_id"

        /** Generous: the relay acknowledges as soon as the op is durable, normally in the same call. */
        val DEFAULT_ACK_TIMEOUT: Duration = 10.seconds

        /**
         * The local user owns it and the caller writes to it, as an app-made canvas; the caller is
         * also named as a reader, which makes the canvas private to those three rather than
         * public-read, so no other agent on the host can list or read it.
         */
        fun aclFor(caller: HostCanvasCaller) = CanvasAcl(
            ownerUserId = CanvasSession.LOCAL_USER_ACTOR_ID,
            writerAgentIds = setOf(caller.agentId),
            readerAgentIds = setOf(caller.agentId),
        )
    }
}

/** A batch checked against a canvas without being published to it. */
sealed interface HostCanvasCheck {
    data class Denied(val reason: String) : HostCanvasCheck

    /** [result] for the scene at [revision]. */
    data class Checked(val revision: Long, val result: CanvasBatchCheck) : HostCanvasCheck
}

/** How a host publish ended. */
sealed interface HostCanvasPublish {
    /** Every op is durable in the log; [revision] is its head. */
    data class Published(val revision: Long) : HostCanvasPublish

    data class Denied(val reason: String) : HostCanvasPublish

    /** Nothing was sent: [reason] names what the apps could not draw and how to write it. */
    data class Invalid(val reason: String) : HostCanvasPublish
}
