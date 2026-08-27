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
internal fun TimelineReducerState.semanticFingerprint(): String = semanticEncoding(label("TimelineReducerState")) {
    timeline(label("timeline"), timeline)
    map(label("pendingToolReturnsByCallId"), pendingToolReturnsByCallId) { lettaMessage(it) }
    scalar(label("lifecycleEpoch"), lifecycleEpoch)
    scalar(label("lastAppliedMutationSequence"), lastAppliedMutationSequence)
    scalar(label("hydrateGeneration"), hydrateGeneration)
    scalar(label("highestRequestedReconcileGeneration"), highestRequestedReconcileGeneration)
    scalar(label("highestAppliedReconcileGeneration"), highestAppliedReconcileGeneration)
    scalar(label("freshnessSequence"), freshnessSequence)
}

/** Complete raw effect encoding used for exact order assertions. */
internal fun TimelineReductionEffect.semanticFingerprint(): String = semanticEncoding(label("TimelineReductionEffect")) {
    when (val effect = this@semanticFingerprint) {
        is TimelineReductionEffect.EmitSyncEvent -> objectValue(label("EmitSyncEvent")) {
            syncEvent(label("event"), effect.event)
        }
        is TimelineReductionEffect.Notify -> objectValue(label("Notify")) {
            scalar(label("serverId"), effect.notification.serverId)
            scalar(label("messageType"), effect.notification.messageType)
            nullableScalar(label("contentPreview"), effect.notification.contentPreview)
        }
        is TimelineReductionEffect.Send -> objectValue(label("Send")) {
            pending(label("pending"), effect.pending)
        }
        is TimelineReductionEffect.PersistPendingLocal -> objectValue(label("PersistPendingLocal")) {
            pending(label("pending"), effect.pending)
            scalar(label("sentAt"), effect.sentAt.toString())
        }
        is TimelineReductionEffect.DeletePendingLocal -> objectValue(label("DeletePendingLocal")) {
            scalar(label("otid"), effect.otid)
        }
        is TimelineReductionEffect.RecordStreamSequence -> objectValue(label("RecordStreamSequence")) {
            scalar(label("sequence"), effect.sequence)
        }
        is TimelineReductionEffect.AdvanceCursor -> objectValue(label("AdvanceCursor")) {
            scalar(label("cursor"), effect.cursor)
        }
    }
}

internal fun semanticLengthPrefixed(value: String): String = "${value.length}:$value"

private data class SemanticLabel(val value: String)

private fun label(value: String) = SemanticLabel(value)

private fun semanticEncoding(root: SemanticLabel, block: SemanticEncoder.() -> Unit): String =
    SemanticEncoder().apply {
        token(root.value)
        token("{")
        block()
        token("}")
    }.toString()

private class SemanticEncoder {
    private val output = StringBuilder()

    fun token(value: String) {
        output.append(semanticLengthPrefixed(value))
    }

    fun <T> scalar(name: SemanticLabel, value: T) {
        token(name.value)
        token(value.toString())
    }

    fun <T> nullableScalar(name: SemanticLabel, value: T?) {
        token(name.value)
        if (value == null) {
            token("null")
        } else {
            token("value")
            token(value.toString())
        }
    }

    fun objectValue(name: SemanticLabel, block: SemanticEncoder.() -> Unit) {
        token(name.value)
        token("{")
        block()
        token("}")
    }

    fun <T> list(name: SemanticLabel, values: List<T>, encode: SemanticEncoder.(T) -> Unit) {
        token(name.value)
        token(values.size.toString())
        values.forEach { value ->
            token("[")
            encode(value)
            token("]")
        }
    }

    fun <T> nullableList(name: SemanticLabel, values: List<T>?, encode: SemanticEncoder.(T) -> Unit) {
        if (values == null) {
            token(name.value)
            token("null")
        } else {
            token(name.value)
            token("value")
            list(label("items"), values, encode)
        }
    }

    fun <V> map(name: SemanticLabel, values: Map<String, V>, encode: SemanticEncoder.(V) -> Unit) {
        token(name.value)
        token(values.size.toString())
        values.entries.sortedBy { it.key }.forEach { (key, value) ->
            token("entry")
            scalar(label("key"), key)
            encode(value)
        }
    }

    fun timeline(name: SemanticLabel, value: Timeline) = objectValue(name) {
        scalar(label("conversationId"), value.conversationId)
        list(label("events"), value.events) { timelineEvent(it) }
        nullableScalar(label("liveCursor"), value.liveCursor)
        nullableScalar(label("backfillCursor"), value.backfillCursor)
        list(
            label("abandonedAssistantFragmentSuppressions"),
            value.abandonedAssistantFragmentSuppressions.sortedWith(
                compareBy(
                    { it.serverId ?: "" },
                    { it.runId ?: "" },
                    { it.contentFingerprint },
                ),
            ),
        ) { suppression ->
            nullableScalar(label("serverId"), suppression.serverId)
            nullableScalar(label("runId"), suppression.runId)
            scalar(label("contentFingerprint"), suppression.contentFingerprint)
        }
        scalar(label("stablePrefixVersion"), value.stablePrefixVersion)
        scalar(label("visibleRevision"), value.visibleRevision)
        scalar(label("releasedOlderCount"), value.releasedOlderCount)
        list(label("residentOtids"), value.residentOtids.sorted()) { scalar(label("otid"), it) }
        scalar(label("invariantsKnown"), value.invariantsKnown)
    }

    fun timelineEvent(value: TimelineEvent) {
        when (value) {
            is TimelineEvent.Local -> objectValue(label("Local")) {
                scalar(label("position"), value.position)
                scalar(label("otid"), value.otid)
                scalar(label("content"), value.content)
                scalar(label("role"), value.role)
                scalar(label("sentAt"), value.sentAt.toString())
                scalar(label("deliveryState"), value.deliveryState)
                attachments(label("attachments"), value.attachments)
                scalar(label("source"), value.source)
                scalar(label("messageType"), value.messageType)
                toolCalls(label("toolCalls"), value.toolCalls)
                nullableScalar(label("approvalRequestId"), value.approvalRequestId)
                scalar(label("approvalDecided"), value.approvalDecided)
                nullableScalar(label("toolReturnContent"), value.toolReturnContent)
                scalar(label("toolReturnIsError"), value.toolReturnIsError)
                map(label("toolReturnContentByCallId"), value.toolReturnContentByCallId) { scalar(label("value"), it) }
                map(label("toolReturnIsErrorByCallId"), value.toolReturnIsErrorByCallId) { scalar(label("value"), it) }
                map(label("toolStartedAtByCallId"), value.toolStartedAtByCallId) { scalar(label("value"), it.toString()) }
                map(label("toolCompletedAtByCallId"), value.toolCompletedAtByCallId) { scalar(label("value"), it.toString()) }
                map(label("toolBatchIdByCallId"), value.toolBatchIdByCallId) { scalar(label("value"), it) }
                nullableScalar(label("reasoningContent"), value.reasoningContent)
            }
            is TimelineEvent.Confirmed -> objectValue(label("Confirmed")) {
                scalar(label("position"), value.position)
                scalar(label("otid"), value.otid)
                scalar(label("content"), value.content)
                scalar(label("serverId"), value.serverId)
                scalar(label("messageType"), value.messageType)
                scalar(label("date"), value.date.toString())
                nullableScalar(label("runId"), value.runId)
                nullableScalar(label("stepId"), value.stepId)
                nullableScalar(label("agentId"), value.agentId)
                attachments(label("attachments"), value.attachments)
                toolCalls(label("toolCalls"), value.toolCalls)
                nullableScalar(label("approvalRequestId"), value.approvalRequestId)
                scalar(label("approvalDecided"), value.approvalDecided)
                nullableScalar(label("approvalDecision"), value.approvalDecision)
                nullableScalar(label("toolReturnContent"), value.toolReturnContent)
                scalar(label("toolReturnIsError"), value.toolReturnIsError)
                map(label("toolReturnContentByCallId"), value.toolReturnContentByCallId) { scalar(label("value"), it) }
                map(label("toolReturnIsErrorByCallId"), value.toolReturnIsErrorByCallId) { scalar(label("value"), it) }
                map(label("toolReturnTruncationByCallId"), value.toolReturnTruncationByCallId) {
                    scalar(label("messageId"), it.messageId)
                    scalar(label("byteLen"), it.byteLen)
                }
                scalar(label("source"), value.source)
                nullableScalar(label("seqId"), value.seqId)
            }
        }
    }

    fun pending(name: SemanticLabel, value: PendingSend) = objectValue(name) {
        scalar(label("otid"), value.otid)
        scalar(label("content"), value.content)
        attachments(label("attachments"), value.attachments)
    }

    fun attachments(name: SemanticLabel, values: List<MessageContentPart.Image>) = list(name, values) {
        scalar(label("mediaType"), it.mediaType)
        scalar(label("base64"), it.base64)
    }

    fun toolCalls(name: SemanticLabel, values: List<ToolCall>) = list(name, values) {
        nullableScalar(label("id"), it.id)
        nullableScalar(label("toolCallId"), it.toolCallId)
        nullableScalar(label("name"), it.name)
        nullableScalar(label("arguments"), it.arguments)
        scalar(label("type"), it.type)
    }

    fun lettaMessage(value: LettaMessage) {
        // Serialization includes every declared field for every LettaMessage
        // subtype. Canonical JSON key ordering removes map insertion-order noise.
        val raw = fingerprintJson.encodeToString(LettaMessage.serializer(), value)
        scalar(label("message"), canonicalJson(fingerprintJson.parseToJsonElement(raw)))
        if (value is ToolReturnMessage) {
            // Include the derived normalized return as semantic state too; its
            // fields can differ even when legacy raw shapes normalize similarly.
            objectValue(label("normalizedToolReturn")) {
                scalar(label("toolCallId"), value.toolReturn.toolCallId)
                scalar(label("status"), value.toolReturn.status)
                nullableScalar(label("funcResponse"), value.toolReturn.funcResponse)
                nullableList(label("stdout"), value.toolReturn.stdout) { scalar(label("line"), it) }
                nullableList(label("stderr"), value.toolReturn.stderr) { scalar(label("line"), it) }
            }
        }
    }

    fun syncEvent(name: SemanticLabel, value: TimelineSyncEvent) = objectValue(name) {
        when (value) {
            is TimelineSyncEvent.Hydrated -> objectValue(label("Hydrated")) { scalar(label("messageCount"), value.messageCount) }
            is TimelineSyncEvent.LocalAppended -> objectValue(label("LocalAppended")) { scalar(label("otid"), value.otid) }
            is TimelineSyncEvent.LocalConfirmed -> objectValue(label("LocalConfirmed")) {
                scalar(label("otid"), value.otid)
                scalar(label("serverId"), value.serverId)
            }
            is TimelineSyncEvent.ServerEvent -> objectValue(label("ServerEvent")) { lettaMessage(value.message) }
            is TimelineSyncEvent.StreamError -> objectValue(label("StreamError")) {
                scalar(label("type"), value.type)
                scalar(label("message"), value.message)
            }
            is TimelineSyncEvent.StreamEventIngested -> objectValue(label("StreamEventIngested")) {
                scalar(label("serverId"), value.serverId)
                nullableScalar(label("messageType"), value.messageType)
            }
            is TimelineSyncEvent.OrphanAssistantFragmentsCleaned -> objectValue(label("OrphanAssistantFragmentsCleaned")) {
                scalar(label("runId"), value.runId)
                nullableScalar(label("turnId"), value.turnId)
                scalar(label("count"), value.count)
                scalar(label("reason"), value.reason)
            }
            TimelineSyncEvent.StreamSubscriberOpened -> token("StreamSubscriberOpened")
            TimelineSyncEvent.StreamSubscriberClosed -> token("StreamSubscriberClosed")
            is TimelineSyncEvent.ReconcileError -> objectValue(label("ReconcileError")) { scalar(label("message"), value.message) }
            is TimelineSyncEvent.HydrateFailed -> objectValue(label("HydrateFailed")) { scalar(label("message"), value.message) }
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
