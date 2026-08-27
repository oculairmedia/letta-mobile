package com.letta.mobile.data.timeline

import com.letta.mobile.data.model.LettaMessage
import com.letta.mobile.data.model.MessageContentPart
import com.letta.mobile.data.model.ToolCall
import com.letta.mobile.data.model.ToolReturnMessage
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Complete raw semantic encoding. Never place this value in failure diagnostics. */
internal fun TimelineReducerState.semanticFingerprint(): String = semanticEncoding("TimelineReducerState") {
    timeline("timeline", timeline)
    map("pendingToolReturnsByCallId", pendingToolReturnsByCallId) { lettaMessage(it) }
    scalar("lifecycleEpoch", lifecycleEpoch)
    scalar("lastAppliedMutationSequence", lastAppliedMutationSequence)
    scalar("hydrateGeneration", hydrateGeneration)
    scalar("highestRequestedReconcileGeneration", highestRequestedReconcileGeneration)
    scalar("highestAppliedReconcileGeneration", highestAppliedReconcileGeneration)
    scalar("freshnessSequence", freshnessSequence)
}

/** Complete raw effect encoding used for exact order assertions. */
internal fun TimelineReductionEffect.semanticFingerprint(): String = semanticEncoding("TimelineReductionEffect") {
    when (val effect = this@semanticFingerprint) {
        is TimelineReductionEffect.EmitSyncEvent -> objectValue("EmitSyncEvent") {
            syncEvent("event", effect.event)
        }
        is TimelineReductionEffect.Notify -> objectValue("Notify") {
            scalar("serverId", effect.notification.serverId)
            scalar("messageType", effect.notification.messageType)
            nullableScalar("contentPreview", effect.notification.contentPreview)
        }
        is TimelineReductionEffect.Send -> objectValue("Send") {
            pending("pending", effect.pending)
        }
        is TimelineReductionEffect.PersistPendingLocal -> objectValue("PersistPendingLocal") {
            pending("pending", effect.pending)
            scalar("sentAt", effect.sentAt.toString())
        }
        is TimelineReductionEffect.DeletePendingLocal -> objectValue("DeletePendingLocal") {
            scalar("otid", effect.otid)
        }
        is TimelineReductionEffect.AdvanceCursor -> objectValue("AdvanceCursor") {
            scalar("cursor", effect.cursor)
        }
    }
}

internal fun semanticLengthPrefixed(value: String): String = "${value.length}:$value"

private fun semanticEncoding(root: String, block: SemanticEncoder.() -> Unit): String =
    SemanticEncoder().apply {
        token(root)
        token("{")
        block()
        token("}")
    }.toString()

private class SemanticEncoder {
    private val output = StringBuilder()

    fun token(value: String) {
        output.append(semanticLengthPrefixed(value))
    }

    fun scalar(name: String, value: Any) {
        token(name)
        token(value.toString())
    }

    fun nullableScalar(name: String, value: Any?) {
        token(name)
        if (value == null) {
            token("null")
        } else {
            token("value")
            token(value.toString())
        }
    }

    fun objectValue(name: String, block: SemanticEncoder.() -> Unit) {
        token(name)
        token("{")
        block()
        token("}")
    }

    fun <T> list(name: String, values: List<T>, encode: SemanticEncoder.(T) -> Unit) {
        token(name)
        token(values.size.toString())
        values.forEach { value ->
            token("[")
            encode(value)
            token("]")
        }
    }

    fun <T> nullableList(name: String, values: List<T>?, encode: SemanticEncoder.(T) -> Unit) {
        if (values == null) {
            nullableScalar(name, null)
        } else {
            token(name)
            token("value")
            list("items", values, encode)
        }
    }

    fun <V> map(name: String, values: Map<String, V>, encode: SemanticEncoder.(V) -> Unit) {
        token(name)
        token(values.size.toString())
        values.entries.sortedBy { it.key }.forEach { (key, value) ->
            token("entry")
            scalar("key", key)
            encode(value)
        }
    }

    fun timeline(name: String, value: Timeline) = objectValue(name) {
        scalar("conversationId", value.conversationId)
        list("events", value.events) { timelineEvent(it) }
        nullableScalar("liveCursor", value.liveCursor)
        nullableScalar("backfillCursor", value.backfillCursor)
        list(
            "abandonedAssistantFragmentSuppressions",
            value.abandonedAssistantFragmentSuppressions.sortedWith(
                compareBy(
                    { it.serverId ?: "" },
                    { it.runId ?: "" },
                    { it.contentFingerprint },
                ),
            ),
        ) { suppression ->
            nullableScalar("serverId", suppression.serverId)
            nullableScalar("runId", suppression.runId)
            scalar("contentFingerprint", suppression.contentFingerprint)
        }
        scalar("stablePrefixVersion", value.stablePrefixVersion)
        scalar("visibleRevision", value.visibleRevision)
        scalar("releasedOlderCount", value.releasedOlderCount)
        list("residentOtids", value.residentOtids.sorted()) { scalar("otid", it) }
        scalar("invariantsKnown", value.invariantsKnown)
    }

    fun timelineEvent(value: TimelineEvent) {
        when (value) {
            is TimelineEvent.Local -> objectValue("Local") {
                scalar("position", value.position)
                scalar("otid", value.otid)
                scalar("content", value.content)
                scalar("role", value.role)
                scalar("sentAt", value.sentAt.toString())
                scalar("deliveryState", value.deliveryState)
                attachments("attachments", value.attachments)
                scalar("source", value.source)
                scalar("messageType", value.messageType)
                toolCalls("toolCalls", value.toolCalls)
                nullableScalar("approvalRequestId", value.approvalRequestId)
                scalar("approvalDecided", value.approvalDecided)
                nullableScalar("toolReturnContent", value.toolReturnContent)
                scalar("toolReturnIsError", value.toolReturnIsError)
                map("toolReturnContentByCallId", value.toolReturnContentByCallId) { scalar("value", it) }
                map("toolReturnIsErrorByCallId", value.toolReturnIsErrorByCallId) { scalar("value", it) }
                map("toolStartedAtByCallId", value.toolStartedAtByCallId) { scalar("value", it.toString()) }
                map("toolCompletedAtByCallId", value.toolCompletedAtByCallId) { scalar("value", it.toString()) }
                map("toolBatchIdByCallId", value.toolBatchIdByCallId) { scalar("value", it) }
                nullableScalar("reasoningContent", value.reasoningContent)
            }
            is TimelineEvent.Confirmed -> objectValue("Confirmed") {
                scalar("position", value.position)
                scalar("otid", value.otid)
                scalar("content", value.content)
                scalar("serverId", value.serverId)
                scalar("messageType", value.messageType)
                scalar("date", value.date.toString())
                nullableScalar("runId", value.runId)
                nullableScalar("stepId", value.stepId)
                nullableScalar("agentId", value.agentId)
                attachments("attachments", value.attachments)
                toolCalls("toolCalls", value.toolCalls)
                nullableScalar("approvalRequestId", value.approvalRequestId)
                scalar("approvalDecided", value.approvalDecided)
                nullableScalar("approvalDecision", value.approvalDecision)
                nullableScalar("toolReturnContent", value.toolReturnContent)
                scalar("toolReturnIsError", value.toolReturnIsError)
                map("toolReturnContentByCallId", value.toolReturnContentByCallId) { scalar("value", it) }
                map("toolReturnIsErrorByCallId", value.toolReturnIsErrorByCallId) { scalar("value", it) }
                map("toolReturnTruncationByCallId", value.toolReturnTruncationByCallId) {
                    scalar("messageId", it.messageId)
                    scalar("byteLen", it.byteLen)
                }
                scalar("source", value.source)
                nullableScalar("seqId", value.seqId)
            }
        }
    }

    fun pending(name: String, value: PendingSend) = objectValue(name) {
        scalar("otid", value.otid)
        scalar("content", value.content)
        attachments("attachments", value.attachments)
    }

    fun attachments(name: String, values: List<MessageContentPart.Image>) = list(name, values) {
        scalar("mediaType", it.mediaType)
        scalar("base64", it.base64)
    }

    fun toolCalls(name: String, values: List<ToolCall>) = list(name, values) {
        nullableScalar("id", it.id)
        nullableScalar("toolCallId", it.toolCallId)
        nullableScalar("name", it.name)
        nullableScalar("arguments", it.arguments)
        scalar("type", it.type)
    }

    fun lettaMessage(value: LettaMessage) {
        // Serialization includes every declared field for every LettaMessage
        // subtype. Canonical JSON key ordering removes map insertion-order noise.
        val raw = fingerprintJson.encodeToString(LettaMessage.serializer(), value)
        scalar("message", canonicalJson(fingerprintJson.parseToJsonElement(raw)))
        if (value is ToolReturnMessage) {
            // Include the derived normalized return as semantic state too; its
            // fields can differ even when legacy raw shapes normalize similarly.
            objectValue("normalizedToolReturn") {
                scalar("toolCallId", value.toolReturn.toolCallId)
                scalar("status", value.toolReturn.status)
                nullableScalar("funcResponse", value.toolReturn.funcResponse)
                nullableList("stdout", value.toolReturn.stdout) { scalar("line", it) }
                nullableList("stderr", value.toolReturn.stderr) { scalar("line", it) }
            }
        }
    }

    fun syncEvent(name: String, value: TimelineSyncEvent) = objectValue(name) {
        when (value) {
            is TimelineSyncEvent.Hydrated -> objectValue("Hydrated") { scalar("messageCount", value.messageCount) }
            is TimelineSyncEvent.LocalAppended -> objectValue("LocalAppended") { scalar("otid", value.otid) }
            is TimelineSyncEvent.LocalConfirmed -> objectValue("LocalConfirmed") {
                scalar("otid", value.otid)
                scalar("serverId", value.serverId)
            }
            is TimelineSyncEvent.ServerEvent -> objectValue("ServerEvent") { lettaMessage(value.message) }
            is TimelineSyncEvent.StreamError -> objectValue("StreamError") {
                scalar("type", value.type)
                scalar("message", value.message)
            }
            is TimelineSyncEvent.StreamEventIngested -> objectValue("StreamEventIngested") {
                scalar("serverId", value.serverId)
                nullableScalar("messageType", value.messageType)
            }
            is TimelineSyncEvent.OrphanAssistantFragmentsCleaned -> objectValue("OrphanAssistantFragmentsCleaned") {
                scalar("runId", value.runId)
                nullableScalar("turnId", value.turnId)
                scalar("count", value.count)
                scalar("reason", value.reason)
            }
            TimelineSyncEvent.StreamSubscriberOpened -> token("StreamSubscriberOpened")
            TimelineSyncEvent.StreamSubscriberClosed -> token("StreamSubscriberClosed")
            is TimelineSyncEvent.ReconcileError -> objectValue("ReconcileError") { scalar("message", value.message) }
            is TimelineSyncEvent.HydrateFailed -> objectValue("HydrateFailed") { scalar("message", value.message) }
        }
    }

    override fun toString(): String = output.toString()
}

private val fingerprintJson = Json {
    encodeDefaults = true
    explicitNulls = true
}

private fun canonicalJson(value: JsonElement): String = when (value) {
    JsonNull -> "null"
    is JsonPrimitive -> value.toString()
    is JsonArray -> value.joinToString(prefix = "[", postfix = "]") { canonicalJson(it) }
    is JsonObject -> value.entries.sortedBy { it.key }.joinToString(prefix = "{", postfix = "}") {
        JsonPrimitive(it.key).toString() + ":" + canonicalJson(it.value)
    }
}
