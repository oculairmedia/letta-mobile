package com.letta.mobile.data.document

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Who may read a shared document and who may apply ops to it. */
interface DocumentAcl {
    fun canRead(actorId: String?): Boolean

    fun canWrite(actorId: String?): Boolean
}

/**
 * The users-and-agents [DocumentAcl] every shared document uses today (the canvas's
 * [com.letta.mobile.data.canvas.CanvasAcl] is this type).
 *
 * Identifiers can be raw or prefixed with "user:" or "agent:". A prefixed identifier only ever
 * matches entries of its own principal type: `user:sam` is never the agent `sam`, and `agent:sam`
 * is never the user `sam`. A raw identifier carries no type and matches either.
 *
 * The serial name is the canvas's original class name, so the descriptor every stored and relayed
 * canvas was written under is unchanged.
 */
@Serializable
@SerialName("com.letta.mobile.data.canvas.CanvasAcl")
data class PrincipalAcl(
    val ownerUserId: String,
    val writerUserIds: Set<String> = emptySet(),
    val writerAgentIds: Set<String> = emptySet(),
    val readerUserIds: Set<String> = emptySet(),
    val readerAgentIds: Set<String> = emptySet(),
) : DocumentAcl {
    /** Checks if [actorId] is authorized to mutate the document. */
    override fun canWrite(actorId: String?): Boolean {
        if (actorId.isNullOrBlank()) return false
        val actor = DocumentPrincipal.parse(actorId)
        return isOwner(actor) || isWriter(actor)
    }

    private fun isOwner(actor: DocumentPrincipal): Boolean =
        actor.matches(ownerUserId, DocumentPrincipal.Kind.USER)

    private fun isWriter(actor: DocumentPrincipal): Boolean =
        actor.matchesAny(writerUserIds, DocumentPrincipal.Kind.USER) ||
            actor.matchesAny(writerAgentIds, DocumentPrincipal.Kind.AGENT)

    /**
     * Checks if [actorId] is authorized to view the document.
     * When [readerUserIds] and [readerAgentIds] are empty, the document defaults to public-read.
     */
    override fun canRead(actorId: String?): Boolean {
        if (isPublicRead()) return true
        if (actorId.isNullOrBlank()) return false
        if (canWrite(actorId)) return true
        return isReader(DocumentPrincipal.parse(actorId))
    }

    private fun isPublicRead(): Boolean =
        readerUserIds.isEmpty() && readerAgentIds.isEmpty()

    private fun isReader(actor: DocumentPrincipal): Boolean =
        actor.matchesAny(readerUserIds, DocumentPrincipal.Kind.USER) ||
            actor.matchesAny(readerAgentIds, DocumentPrincipal.Kind.AGENT)
}

/**
 * An ACL identifier split into its principal type and bare id. [kind] is null for a raw
 * identifier, which is untyped and so may match an entry of either type.
 */
internal data class DocumentPrincipal(val kind: Kind?, val id: String) {
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
        fun parse(id: String): DocumentPrincipal {
            for (kind in Kind.entries) {
                if (id.startsWith(kind.prefix)) return DocumentPrincipal(kind, id.removePrefix(kind.prefix))
            }
            return DocumentPrincipal(null, id)
        }
    }
}

/** Thrown when an actor attempts a mutation the document's [DocumentAcl] does not allow. */
open class UnauthorizedDocumentMutationException(
    val actorId: String?,
    /** The document's id as text, whatever its id type. */
    val documentKey: String,
    message: String = "Actor '${actorId ?: "anonymous"}' is not authorized to mutate document '$documentKey'",
) : IllegalStateException(message)
