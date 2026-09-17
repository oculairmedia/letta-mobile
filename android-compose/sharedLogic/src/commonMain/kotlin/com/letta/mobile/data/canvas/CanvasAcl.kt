package com.letta.mobile.data.canvas

import kotlinx.serialization.Serializable

/**
 * Access Control List for a collaborative [CanvasDocument].
 *
 * Defines authorization for reading and writing canvas documents and applying operations.
 * Identifiers can be raw or prefixed with "user:" or "agent:". A prefixed identifier only ever
 * matches entries of its own principal type: `user:sam` is never the agent `sam`, and
 * `agent:sam` is never the user `sam`. A raw identifier carries no type and matches either.
 */
@Serializable
data class CanvasAcl(
    val ownerUserId: String,
    val writerUserIds: Set<String> = emptySet(),
    val writerAgentIds: Set<String> = emptySet(),
    val readerUserIds: Set<String> = emptySet(),
    val readerAgentIds: Set<String> = emptySet(),
) {
    /**
     * Checks if [actorId] is authorized to mutate the canvas.
     */
    fun canWrite(actorId: String?): Boolean {
        if (actorId.isNullOrBlank()) return false
        val actor = CanvasPrincipal.parse(actorId)
        return isOwner(actor) || isWriter(actor)
    }

    private fun isOwner(actor: CanvasPrincipal): Boolean =
        actor.matches(ownerUserId, CanvasPrincipal.Kind.USER)

    private fun isWriter(actor: CanvasPrincipal): Boolean =
        actor.matchesAny(writerUserIds, CanvasPrincipal.Kind.USER) ||
            actor.matchesAny(writerAgentIds, CanvasPrincipal.Kind.AGENT)

    /**
     * Checks if [actorId] is authorized to view the canvas.
     * When [readerUserIds] and [readerAgentIds] are empty, the canvas defaults to public-read.
     */
    fun canRead(actorId: String?): Boolean {
        if (isPublicRead()) return true
        if (actorId.isNullOrBlank()) return false
        if (canWrite(actorId)) return true
        return isReader(CanvasPrincipal.parse(actorId))
    }

    private fun isPublicRead(): Boolean =
        readerUserIds.isEmpty() && readerAgentIds.isEmpty()

    private fun isReader(actor: CanvasPrincipal): Boolean =
        actor.matchesAny(readerUserIds, CanvasPrincipal.Kind.USER) ||
            actor.matchesAny(readerAgentIds, CanvasPrincipal.Kind.AGENT)
}

/**
 * An ACL identifier split into its principal type and bare id. [kind] is null for a raw
 * identifier, which is untyped and so may match an entry of either type.
 */
internal data class CanvasPrincipal(val kind: Kind?, val id: String) {
    enum class Kind(val prefix: String) {
        USER("user:"),
        AGENT("agent:"),
    }

    /**
     * Whether this actor matches [entry], an identifier listed in a set whose entries are all of
     * [entryKind]. An entry that carries the other type's prefix is malformed and never matches.
     */
    fun matches(entry: String, entryKind: Kind): Boolean {
        if (kind != null && kind != entryKind) return false
        val listed = parse(entry)
        if (listed.kind != null && listed.kind != entryKind) return false
        return id == listed.id
    }

    fun matchesAny(entries: Set<String>, entryKind: Kind): Boolean =
        entries.any { matches(it, entryKind) }

    companion object {
        fun parse(id: String): CanvasPrincipal {
            for (kind in Kind.entries) {
                if (id.startsWith(kind.prefix)) return CanvasPrincipal(kind, id.removePrefix(kind.prefix))
            }
            return CanvasPrincipal(null, id)
        }
    }
}

/**
 * Thrown when an actor attempts an unauthorized mutation on a [CanvasSession].
 */
class UnauthorizedCanvasMutationException(
    val actorId: String?,
    val canvasId: CanvasId,
    message: String = "Actor '${actorId ?: "anonymous"}' is not authorized to mutate canvas '$canvasId'",
) : IllegalStateException(message)
