package com.letta.mobile.data.timeline

import kotlinx.collections.immutable.PersistentList
import kotlinx.collections.immutable.toPersistentList

/** Reference checkpoint of invocation ownership without arguments or output bodies. */
internal class HistoricalToolEvidence private constructor(
    private val calls: PersistentList<Owner>,
) {
    fun ownersFor(callId: String, runId: String?): PersistentList<Owner> = calls.filter {
        it.callId == callId &&
            (it.runId.isNullOrBlank() || runId.isNullOrBlank() || it.runId == runId)
    }.toPersistentList()

    /** Resolve metadata first; never fetch a body for an absent or ambiguous owner. */
    suspend fun <T> withUniqueOwnerBody(
        callId: String,
        runId: String?,
        readBody: suspend (String) -> T?,
    ): BodyLookup<T> {
        val owners = ownersFor(callId, runId).distinctBy { it.otid }
        if (owners.isEmpty()) return BodyLookup.NoOwner
        if (owners.size != 1) return BodyLookup.Ambiguous
        val owner = owners.single()
        val body = readBody(owner.otid) ?: return BodyLookup.MissingBody(owner.otid)
        return BodyLookup.Found(owner, body)
    }

    sealed interface BodyLookup<out T> {
        data object NoOwner : BodyLookup<Nothing>
        data object Ambiguous : BodyLookup<Nothing>
        data class MissingBody(val otid: String) : BodyLookup<Nothing>
        data class Found<T>(val owner: Owner, val body: T) : BodyLookup<T>
    }

    data class Owner(
        val otid: String,
        val runId: String?,
        val callId: String,
        val hasReturnBody: Boolean,
        val truncation: ToolReturnTruncation?,
    )

    companion object {
        fun checkpoint(timeline: Timeline): HistoricalToolEvidence = HistoricalToolEvidence(
            timeline.events.filterIsInstance<TimelineEvent.Confirmed>().flatMap { event ->
                event.toolCalls.mapNotNull { call ->
                    val callId = call.effectiveId.takeIf(String::isNotBlank) ?: return@mapNotNull null
                    Owner(
                        otid = event.otid,
                        runId = event.runId,
                        callId = callId,
                        hasReturnBody = callId in event.toolReturnContentByCallId,
                        truncation = event.toolReturnTruncationByCallId[callId],
                    )
                }
            }.toPersistentList(),
        )
    }
}
