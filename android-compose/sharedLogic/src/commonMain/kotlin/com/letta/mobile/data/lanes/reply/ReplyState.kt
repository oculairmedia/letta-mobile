package com.letta.mobile.data.lanes.reply

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Host-minted request address that a peer copies into its reply. */
@Serializable
data class ReplyTo(
    @SerialName("correlation_id") val correlationId: String,
    @SerialName("expires_at") val expiresAt: Long,
)

/** Host-owned routing record; the model cannot supply the origin. */
@Serializable
data class PendingReply(
    @SerialName("correlation_id") val correlationId: String,
    @SerialName("origin_agent") val originAgent: String,
    @SerialName("origin_conversation") val originConversation: String,
    @SerialName("expected_replier") val expectedReplier: String,
    @SerialName("expected_endpoint") val expectedEndpoint: String,
    @SerialName("created_at") val createdAt: Long,
    @SerialName("expires_at") val expiresAt: Long,
)

/** An authenticated reply held without starting an App Server turn. */
@Serializable
data class HeldReply(val request: PendingReply, val sender: String, val messageKey: String, val body: String,
    val inputToken: String? = null, val processEpoch: String? = null)

/** Durable evidence for replies that cannot be attached. */
@Serializable
data class ReplyDeadLetter(val correlationId: String, val messageKey: String, val reason: String, val recordedAt: Long)

/** One atomic journal generation, shared by sender, receiver and wrapper inputs. */
@Serializable
data class ReplySnapshot(
    val pending: List<PendingReply> = emptyList(),
    val held: List<HeldReply> = emptyList(),
    val deadLetters: List<ReplyDeadLetter> = emptyList(),
    val received: List<ReplyReceipt> = emptyList(),
    val expired: Set<String> = emptySet(),
)

/** Correlation admission before ordinary lane routing. */
enum class ReplyMatch { HELD, MISMATCH, INBOX }

/** Verified origin scope used by both request creation and wrapper attachment. */
data class ReplyOrigin(val agent: String, val conversation: String)

/** Retains authenticated duplicate evidence after a request closes. */
@Serializable
data class ReplyReceipt(val messageKey: String, val correlationId: String, val recipient: String,
    val sender: String, val endpoint: String, val retainedUntil: Long)
