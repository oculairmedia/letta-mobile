package com.letta.mobile.cli.swarm

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.ProgramResult
import com.letta.mobile.data.serialization.LettaJson
import com.letta.mobile.data.swarm.OutboxEntry
import com.letta.mobile.data.swarm.Activity
import com.letta.mobile.data.swarm.DeliveryEvent
import com.letta.mobile.data.swarm.DeliveryFailure
import com.letta.mobile.data.swarm.DeliveryReceipt
import com.letta.mobile.data.swarm.PendingSwarmApproval
import com.letta.mobile.data.swarm.Retrying
import com.letta.mobile.data.swarm.SwarmAgent
import com.letta.mobile.data.swarm.SwarmMessage
import com.letta.mobile.data.swarm.SwarmReply
import com.letta.mobile.data.swarm.SwarmStatus
import com.letta.mobile.data.swarm.TurnTerminal
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** One line of output: where it goes and what it says. */
data class OutputLine(val text: String, val toStderr: Boolean = false)

/** JSON as the CLI prints it: one compact document per line, through the canonical wire config. */
fun JsonElement.printable(): String = LettaJson.wire.encodeToString(JsonElement.serializer(), this)

/** A send event as one JSON object (every event, in json mode). */
fun DeliveryEvent.toJson(): JsonObject = when (this) {
    is DeliveryReceipt -> receiptJson()
    is DeliveryFailure -> buildJsonObject {
        put("event", "failure")
        put("error", errorName)
        put("exit_code", exit.code)
        put("message", describe())
    }
    is Activity -> activityJson()
    is Retrying -> buildJsonObject {
        put("event", "retrying")
        put("attempt", attempt)
        put("reason", reason)
        put("delay_ms", delayMs)
    }
}

/** A send event in text mode, or null for the ones only json shows (tool traffic, retries go to stderr). */
fun DeliveryEvent.toText(): OutputLine? = when (this) {
    is DeliveryReceipt.Connected -> OutputLine(
        "connected: ${agent.name} (${agent.id.value}) conversation ${conversation.value} client-message-id ${clientMessageId.value}",
    )
    is DeliveryReceipt.Accepted -> OutputLine("accepted: ${disposition.name.lowercase()}")
    is DeliveryReceipt.Persisted -> OutputLine("persisted: ${messageId.value}")
    is DeliveryReceipt.Processed -> OutputLine(processedText())
    is DeliveryFailure -> OutputLine("error: ${describe()}", toStderr = true)
    is Activity.ApprovalRequested -> OutputLine("approval needed: ${approval.toolName} (${approval.approvalId})", toStderr = true)
    is Activity -> null
    is Retrying -> OutputLine("retrying ($attempt): $reason, next dial in ${delayMs}ms", toStderr = true)
}

private fun DeliveryReceipt.receiptJson(): JsonObject = buildJsonObject {
    put("event", stage.name.lowercase())
    when (val receipt = this@receiptJson) {
        is DeliveryReceipt.Connected -> {
            put("agent_id", receipt.agent.id.value)
            put("agent_name", receipt.agent.name)
            put("conversation_id", receipt.conversation.value)
            put("client_message_id", receipt.clientMessageId.value)
        }
        is DeliveryReceipt.Accepted -> {
            put("client_message_id", receipt.clientMessageId.value)
            put("disposition", receipt.disposition.name.lowercase())
        }
        is DeliveryReceipt.Persisted -> put("message_id", receipt.messageId.value)
        is DeliveryReceipt.Processed -> {
            putTerminal(receipt.terminal)
            putReply(receipt.reply)
        }
    }
}

private fun Activity.activityJson(): JsonObject = buildJsonObject {
    when (val activity = this@activityJson) {
        is Activity.ToolCall -> {
            put("event", "tool_call")
            put("tool_name", activity.toolName)
            put("tool_call_id", activity.toolCallId)
        }
        is Activity.ToolReturn -> {
            put("event", "tool_return")
            put("tool_call_id", activity.toolCallId)
            put("status", activity.status)
        }
        is Activity.ApprovalRequested -> {
            put("event", "approval_requested")
            putApproval(activity.approval)
        }
    }
}

private fun JsonObjectBuilder.putTerminal(terminal: TurnTerminal) {
    put("terminal", terminal.wireName)
    put("exit_code", terminal.exit.code)
    when (terminal) {
        is TurnTerminal.Failed -> put("reason", terminal.reason)
        is TurnTerminal.Cancelled -> put("reason", terminal.reason)
        is TurnTerminal.NeedsApproval -> put(
            "approvals",
            JsonArray(terminal.approvals.map { approval -> buildJsonObject { putApproval(approval) } }),
        )
        TurnTerminal.Completed, TurnTerminal.Unobserved -> Unit
    }
}

private fun JsonObjectBuilder.putApproval(approval: PendingSwarmApproval) {
    put("tool_name", approval.toolName)
    approval.toolCallId?.let { put("tool_call_id", it) }
    put("approval_id", approval.approvalId)
}

private fun JsonObjectBuilder.putReply(reply: SwarmReply) {
    put("reply", reply.text)
    put("reply_message_ids", JsonArray(reply.messageIds.map { JsonPrimitive(it.value) }))
    put("reply_truncated", reply.truncated)
}

private val TurnTerminal.wireName: String
    get() = when (this) {
        TurnTerminal.Completed -> "completed"
        is TurnTerminal.Failed -> "failed"
        is TurnTerminal.Cancelled -> "cancelled"
        is TurnTerminal.NeedsApproval -> "needs_approval"
        TurnTerminal.Unobserved -> "reply_unobserved"
    }

private fun DeliveryReceipt.Processed.processedText(): String {
    val head = when (val ended = terminal) {
        is TurnTerminal.Failed -> "processed: failed (${ended.reason})"
        is TurnTerminal.Cancelled -> "processed: cancelled (${ended.reason})"
        is TurnTerminal.NeedsApproval ->
            "processed: needs approval (${ended.approvals.joinToString { it.toolName }}); answer it in the Meridian app"
        TurnTerminal.Completed -> "processed: completed"
        TurnTerminal.Unobserved -> "processed: reply read from history after a dropped link (terminal not observed)"
    }
    val tail = if (reply.truncated) "\n[reply truncated]" else ""
    return if (reply.text.isEmpty()) head else "$head\n\n${reply.text}$tail"
}

private val DeliveryFailure.errorName: String
    get() = exit.name.replace(Regex("([a-z])([A-Z])"), "$1_$2").lowercase()

/** What a failure says to the person. Never includes key material or invite secrets. */
fun DeliveryFailure.describe(): String = when (this) {
    DeliveryFailure.NotPaired -> "this machine is not paired; run `meridian pair --invite -`"
    is DeliveryFailure.Denied -> "the host refused this device ($reason): it was revoked or never paired"
    is DeliveryFailure.Unreachable -> "host unreachable: $reason"
    is DeliveryFailure.AgentNotFound -> "no agent '${ref.value}' in this device's scope"
    is DeliveryFailure.AgentAmbiguous -> "'${ref.value}' names several agents: ${matches.joinToString { "${it.name} (${it.id.value})" }}"
    is DeliveryFailure.ConversationNotFound -> "conversation ${conversation.value} not found: $reason"
    is DeliveryFailure.ConversationOutOfScope -> "conversation ${conversation.value} belongs to another agent; refused"
    is DeliveryFailure.HostError -> "$operation failed: $reason"
    is DeliveryFailure.Rejected -> "the host rejected the message: $reason"
    is DeliveryFailure.TimedOut -> "timed out after ${reached?.name?.lowercase() ?: "no confirmed stage"}; the turn keeps running on the host"
    is DeliveryFailure.Unconfirmed ->
        "link dropped and the history does not show ${clientMessageId.value} yet; check with `meridian message status`"
    is DeliveryFailure.Expired -> "outbox entry ${clientMessageId.value} expired before it was sent"
    is DeliveryFailure.IdReused -> "client message id ${clientMessageId.value} was already used for a different message"
}

@JvmName("agentsToJson")
fun List<SwarmAgent>.toJson(): JsonArray = JsonArray(map { agent ->
    buildJsonObject {
        put("id", agent.id.value)
        put("name", agent.name)
    }
})

@JvmName("agentsToText")
fun List<SwarmAgent>.toText(): String = joinToString("\n") { "${it.id.value}\t${it.name}" }

@JvmName("messagesToJson")
fun List<SwarmMessage>.toJson(): JsonArray = JsonArray(map { message ->
    buildJsonObject {
        put("id", message.id.value)
        put("type", message.type)
        message.date?.let { put("date", it) }
        message.senderId?.let { put("sender_id", it) }
        message.clientMessageId?.let { put("client_message_id", it) }
        message.text?.let { put("text", it) }
        if (message.truncated) put("truncated", true)
    }
})

@JvmName("messagesToText")
fun List<SwarmMessage>.toText(): String = joinToString("\n\n") { message ->
    val sender = message.senderId?.let { " sender=$it" }.orEmpty()
    val header = "[${message.date.orEmpty()}] ${message.type} ${message.id.value}$sender"
    message.text?.let { "$header\n$it${if (message.truncated) "\n[truncated]" else ""}" } ?: header
}

fun SwarmStatus.toJson(): JsonObject = buildJsonObject {
    outboxStage?.let { put("outbox_stage", it.name.lowercase()) }
    persisted?.let { put("message_id", it.value) }
    put("persisted", persisted != null)
    put("replied", replied)
    putReply(reply)
}

fun SwarmStatus.toText(): String {
    val stage = when {
        replied -> "replied"
        persisted != null -> "persisted"
        else -> outboxStage?.let { "outbox: ${it.name.lowercase()}" } ?: "not found"
    }
    val head = "status: $stage${persisted?.let { " (${it.value})" }.orEmpty()}"
    return if (reply.text.isEmpty()) head else "$head\n\n${reply.text}"
}

/** Prints [failure] in the command's format and exits with its code. */
fun CliktCommand.failWith(failure: DeliveryFailure, output: OutputFormat): Nothing {
    when (output) {
        OutputFormat.Json -> echo(failure.toJson().printable())
        OutputFormat.Text -> echo("error: ${failure.describe()}", err = true)
    }
    throw ProgramResult(failure.exit.code)
}

/** The outbox listing: ids, stages and targets only (message text stays on disk). */
fun pendingJson(entries: List<OutboxEntry>): JsonArray = JsonArray(entries.map { entry ->
    buildJsonObject {
        put("client_message_id", entry.id.value)
        put("stage", entry.stage.name.lowercase())
        put("agent", entry.agentRef.value)
        entry.conversation?.let { put("conversation_id", it.value) }
        entry.persistedAs?.let { put("message_id", it.value) }
        put("created_at_ms", entry.createdAtMs)
    }
})