package com.letta.mobile.data.model

import kotlinx.serialization.Serializable
import kotlin.jvm.JvmInline
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * The id of a canvas board (letta-mobile-by4vm.99).
 *
 * Unlike the value classes in `DomainIds.kt`, this is a data class and serializes as an object,
 * `{"value":"canvas-…"}`. That is the shape already persisted in every stored canvas's metadata
 * (`CanvasDocument.id` in the Automerge notebook and the desktop JSON store), so it stays a data
 * class: a value class would serialize as a bare string and stop old files from decoding.
 * Wire DTOs that carry a canvas id as a bare string keep `String` and unwrap [value] at the edge.
 */
@Serializable
data class CanvasId(val value: String) {
    override fun toString(): String = value

    companion object {
        /** Prefix of every canvas id minted here. */
        const val PREFIX: String = "canvas-"

        /** Prefix of a conversation's canvas id: `canvas-conversation-<conversationId>`. */
        const val CONVERSATION_PREFIX: String = PREFIX + "conversation-"

        /**
         * A fresh id for a canvas that is about to be upserted. Every store's upsert replaces
         * an existing row (and its ACL) silently, so the id must not be able to collide: a
         * timestamp plus a small suffix could repeat within one millisecond, a UUID cannot.
         */
        @OptIn(ExperimentalUuidApi::class)
        fun generate(): CanvasId = CanvasId(PREFIX + Uuid.random())

        /**
         * The canvas of conversation [conversationId], the same on every app: apps share a canvas
         * through their host by id, so a random id per app would give each its own board.
         */
        fun forConversation(conversationId: ConversationId): CanvasId = CanvasId(CONVERSATION_PREFIX + conversationId.value)
    }
}

/**
 * Who wrote a canvas op (letta-mobile-by4vm.99): an agent's id, the local user
 * (`CanvasSession.LOCAL_USER_ACTOR_ID`), or a system writer such as `system`. Ops carry it on the
 * wire and in the op log as a bare string ([value]); the canvas session and the op-stamping
 * helpers take it typed. Not blank-checked: an op built only to be validated or rebound names no
 * one yet (`""`).
 */
@JvmInline
@Serializable
value class CanvasActorId(val value: String) {
    override fun toString(): String = value
}
