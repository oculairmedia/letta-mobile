package com.letta.mobile.data.messaging

import com.letta.mobile.data.controller.extras.CustomIrohMessagingTool
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * letta-mobile-slqfp: pure projection from transport-structural signals to
 * [AgentMessageProvenance]. Two independent structural sources feed this —
 * never message body/content text:
 *
 *  - INBOUND: the `clientMessageId` a2a envelope encoding written by
 *    `A2aWiring.inputOnConversation` (see [AgentMessageClientId]) when this
 *    conversation's agent RECEIVED the message.
 *  - OUTBOUND: the `agent_message_send` tool call's own JSON arguments
 *    (`to`, `body`) and JSON result
 *    (`{"ok":true,"delivered":...,"msgId":...,"to":...}`), emitted by
 *    `CustomIrohMessagingTool` when this conversation's agent SENT the
 *    message.
 *
 * Kept in `sharedLogic/commonMain` per the cardinal rule so Android reuses
 * the identical projection once its chat render lands (letta-mobile-wq0c8).
 */
object AgentMessageProvenanceProjection {

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Project inbound provenance for a USER-role message landed by the a2a
     * receive path. [clientMessageId] is the timeline event's otid/
     * clientMessageId; [ownAgentId] is this conversation's agent (the
     * recipient). Returns null when the id doesn't carry the a2a envelope
     * encoding — i.e. an ordinary human- or hydrated-history message.
     */
    fun projectInbound(
        clientMessageId: String?,
        ownAgentId: String?,
    ): AgentMessageProvenance? {
        val decoded = AgentMessageClientId.decode(clientMessageId) ?: return null
        return AgentMessageProvenance(
            direction = AgentMessageDirection.INBOUND,
            fromAgentId = decoded.fromAgentId,
            toAgentId = ownAgentId ?: decoded.toAgentId,
            msgId = decoded.msgId,
            // The message already landed in this conversation — from the
            // recipient's own vantage point, receipt IS confirmation. There
            // is no "pending" state to observe here; the sender's own send
            // attempt (tracked separately, outbound-side) carries the
            // pending/sent/failed lifecycle.
            deliveryState = AgentMessageDeliveryState.RECEIVER_CONFIRMED,
        )
    }

    /**
     * Project outbound provenance for the `agent_message_send` tool call.
     * Returns null when [toolName] doesn't match the tool, or the structural
     * fields required to identify sender/recipient are missing.
     */
    fun projectOutbound(
        toolName: String?,
        argumentsJson: String?,
        resultJson: String?,
        isError: Boolean,
        fromAgentId: String?,
    ): AgentMessageProvenance? {
        if (toolName != CustomIrohMessagingTool.TOOL_NAME) return null
        val from = fromAgentId?.takeIf { it.isNotBlank() } ?: return null
        val args = argumentsJson?.let { runCatching { json.parseToJsonElement(it) as? JsonObject }.getOrNull() }
        val to = args?.get("to")?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: return null

        val result = resultJson?.let { runCatching { json.parseToJsonElement(it) as? JsonObject }.getOrNull() }
        val ok = result?.get("ok")?.jsonPrimitive?.booleanOrNull == true
        val delivered = result?.get("delivered")?.jsonPrimitive?.booleanOrNull == true
        val accepted = result?.get("accepted")?.jsonPrimitive?.booleanOrNull == true
        val msgIdFromResult = result?.get("msgId")?.jsonPrimitive?.contentOrNull

        val deliveryState = when {
            isError -> AgentMessageDeliveryState.FAILED
            result == null -> AgentMessageDeliveryState.PENDING
            ok && delivered -> AgentMessageDeliveryState.RECEIVER_CONFIRMED
            ok && accepted -> AgentMessageDeliveryState.SENT
            else -> AgentMessageDeliveryState.FAILED
        }
        val failureReason = if (deliveryState == AgentMessageDeliveryState.FAILED) {
            resultJson?.takeIf { it.isNotBlank() }
        } else {
            null
        }

        return AgentMessageProvenance(
            direction = AgentMessageDirection.OUTBOUND,
            fromAgentId = from,
            toAgentId = to,
            msgId = msgIdFromResult ?: "",
            deliveryState = deliveryState,
            failureReason = failureReason,
        )
    }
}
