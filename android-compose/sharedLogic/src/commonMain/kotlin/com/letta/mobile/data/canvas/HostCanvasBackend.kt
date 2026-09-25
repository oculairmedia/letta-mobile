package com.letta.mobile.data.canvas

import kotlinx.coroutines.channels.Channel
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/** Who is calling a host canvas tool: the runtime scope the App Server stamped, never the input. */
data class HostCanvasCaller(val agentId: String, val conversationId: String? = null)

/** A canvas's scene as the host's log has it: [revision] is the log's head cursor. */
data class HostCanvasScene(val sceneJson: String, val revision: Long, val lamport: Long)

/** What a caller may do with a canvas it asked for. */
sealed interface HostCanvasAccess {
    data class Granted(val entry: HostCanvasEntry) : HostCanvasAccess

    data class Denied(val reason: String) : HostCanvasAccess
}

/**
 * The host's side of the `canvas.*` tools (letta-mobile-aknkw.1): canvases read from and written to
 * the relay's durable op log, so an agent running on the host draws on the same boards the apps
 * share, whether or not any app is connected.
 *
 * - A scene is the projection of its topic's log, from the first op; nothing else holds a copy.
 * - An agent's ops enter the log the way an app's do: through a relay connection of its own
 *   (origin `agent:<id>`), so they are stored, acknowledged and fanned out to every app on the
 *   board by the same code.
 * - Who may read and write each canvas is in [directory] (letta-mobile-aknkw.2). An agent may claim
 *   the canvas of the conversation its runtime is in, and no other: the App Server's runtime scope
 *   is the proof that the conversation is its own.
 */
@OptIn(ExperimentalUuidApi::class)
class HostCanvasBackend(
    private val relay: CanvasRelayHost,
    private val store: CanvasRelayStore,
    private val directory: HostCanvasDirectory,
    private val newOpId: () -> String = { "agent-op-${Uuid.random()}" },
    private val ackTimeout: Duration = DEFAULT_ACK_TIMEOUT,
) {
    /** The canvas [canvasId] for [caller]: known to the directory, or its own conversation's. */
    suspend fun open(caller: HostCanvasCaller, canvasId: String): HostCanvasAccess {
        directory.get(canvasId)?.let { return readable(caller, it) }
        val own = caller.conversationId
        if (own != null && canvasId == CanvasId.forConversation(own).value) return claimConversation(caller, own)
        return HostCanvasAccess.Denied("Canvas not found: $canvasId")
    }

    /**
     * The canvas of [conversationId]. The caller's own conversation always has one (claimed on first
     * use); another conversation's is the caller's only if the directory lets it read, and otherwise
     * a refusal when the caller asked to [claim] it, or null (nothing to show) when it only looked.
     */
    suspend fun conversation(caller: HostCanvasCaller, conversationId: String, claim: Boolean): HostCanvasAccess? {
        directory.forConversation(conversationId)?.let { return readable(caller, it) }
        if (conversationId == caller.conversationId) return claimConversation(caller, conversationId)
        return if (claim) HostCanvasAccess.Denied(notOwnConversation(caller, conversationId)) else null
    }

    /**
     * The canvas of the conversation [caller] runs in (claimed, so created, on first use), for a
     * call that names no canvas; null when the caller is in no conversation.
     */
    suspend fun ownConversation(caller: HostCanvasCaller): HostCanvasAccess? =
        caller.conversationId?.let { conversation(caller, it, claim = true) }

    /** A new canvas of no conversation, owned by the local user, the caller its writer. */
    suspend fun create(caller: HostCanvasCaller, title: String): HostCanvasEntry {
        val id = CanvasId.generate()
        val topic = CanvasRelayProtocol.canvasTopic(id)
        val bound = store.bind(topic, id)
        return directory.putIfAbsent(HostCanvasEntry(bound.value, topic, title, acl = aclFor(caller)))
    }

    /** Every canvas [caller] may read, its own conversation's included. */
    suspend fun list(caller: HostCanvasCaller): List<HostCanvasEntry> {
        caller.conversationId?.let { conversation(caller, it, claim = false) }
        return directory.all().filter { it.acl.canRead(caller.agentId) }
    }

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
     * Scenes and elements the apps cannot draw are refused before anything is sent
     * ([CanvasSceneValidator]): published, they would be acknowledged, logged and fanned out, and
     * show nothing (letta-mobile-qygvv.21).
     */
    suspend fun publish(caller: HostCanvasCaller, entry: HostCanvasEntry, ops: List<CanvasOp>): HostCanvasPublish {
        if (!entry.acl.canWrite(caller.agentId)) {
            return HostCanvasPublish.Denied("Unauthorized: actor '${caller.agentId}' cannot write to canvas '${entry.canvasId}'")
        }
        return when (val checked = CanvasSceneValidator.ops(ops)) {
            is CanvasOpsCheck.Invalid -> HostCanvasPublish.Invalid(checked.message)
            is CanvasOpsCheck.Valid -> send(caller, entry, checked.ops)
        }
    }

    private suspend fun send(caller: HostCanvasCaller, entry: HostCanvasEntry, ops: List<CanvasOp>): HostCanvasPublish {
        val replies = Channel<CanvasRelayMessage>(Channel.UNLIMITED)
        val link = relay.connect(CanvasRelayProtocol.AGENT_ORIGIN_PREFIX + caller.agentId) { replies.send(it) }
        try {
            link.receive(CanvasRelayMessage.Join(entry.topic, entry.canvasId, afterCursor = store.head(entry.topic)))
            var lamport = scene(entry).lamport
            val stamped = ops.map { it.withActor(caller.agentId).withStamp(newOpId(), ++lamport) }
            stamped.forEach { link.receive(CanvasRelayMessage.Publish(entry.topic, it)) }
            return HostCanvasAcks(stamped).await(replies, ackTimeout)
        } finally {
            link.close()
            replies.close()
        }
    }

    private suspend fun claimConversation(caller: HostCanvasCaller, conversationId: String): HostCanvasAccess {
        val topic = CanvasRelayProtocol.conversationTopic(conversationId)
        val bound = store.bind(topic, CanvasId.forConversation(conversationId))
        val entry = directory.putIfAbsent(
            HostCanvasEntry(bound.value, topic, CONVERSATION_CANVAS_TITLE, conversationId, aclFor(caller)),
        )
        return readable(caller, entry)
    }

    private fun readable(caller: HostCanvasCaller, entry: HostCanvasEntry): HostCanvasAccess =
        if (entry.acl.canRead(caller.agentId)) {
            HostCanvasAccess.Granted(entry)
        } else {
            HostCanvasAccess.Denied("Unauthorized: actor '${caller.agentId}' cannot read canvas '${entry.canvasId}'")
        }

    private fun notOwnConversation(caller: HostCanvasCaller, conversationId: String): String =
        "Unauthorized: actor '${caller.agentId}' cannot open the canvas of conversation '$conversationId'"

    private companion object {
        const val CONVERSATION_CANVAS_TITLE = "Conversation canvas"

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

/** How a host publish ended. */
sealed interface HostCanvasPublish {
    /** Every op is durable in the log; [revision] is its head. */
    data class Published(val revision: Long) : HostCanvasPublish

    data class Denied(val reason: String) : HostCanvasPublish

    /** Nothing was sent: [reason] names what the apps could not draw and how to write it. */
    data class Invalid(val reason: String) : HostCanvasPublish
}
