package com.letta.mobile.data.timeline

import com.letta.mobile.data.model.LettaMessage
import com.letta.mobile.data.timeline.snapshot.TimelineScope
import com.letta.mobile.util.Telemetry
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json

@JvmInline
value class TimelineRequestId(val value: String) {
    init { require(value.isNotBlank()) { "requestId must not be blank" } }
}

@JvmInline
value class TimelineSelectionGeneration(val value: Long) {
    init { require(value >= 0L) { "selectionGeneration must not be negative" } }
}

@JvmInline
value class TimelineMessageId(val value: String) {
    init { require(value.isNotBlank()) { "messageId must not be blank" } }
}

enum class TimelineRemoteOrder { OldestFirst, NewestFirst, Unknown }

sealed interface TimelineContinuation {
    data object Initial : TimelineContinuation
    data class Before(val messageId: TimelineMessageId) : TimelineContinuation
    data class After(val messageId: TimelineMessageId) : TimelineContinuation
}

data class TimelinePageBudget(
    val maxMetadataRows: Int,
    val maxDecodedBodyBytes: Long,
) {
    init {
        require(maxMetadataRows > 0) { "maxMetadataRows must be positive" }
        require(maxDecodedBodyBytes > 0L) { "maxDecodedBodyBytes must be positive" }
    }
}

data class TimelineRemotePageRequest(
    val scope: TimelineScope,
    val requestId: TimelineRequestId,
    val selectionGeneration: TimelineSelectionGeneration,
    val order: TimelineRemoteOrder,
    val continuation: TimelineContinuation,
    val budget: TimelinePageBudget,
) {
    init { require(order != TimelineRemoteOrder.Unknown) { "unknown timeline order cannot be requested" } }
}

data class TimelineRemoteRecord(
    val identity: TimelineMessageId,
    val message: LettaMessage,
    val encodedBodyBytes: Long,
) {
    init { require(encodedBodyBytes >= 0L) { "encodedBodyBytes must not be negative" } }
}

sealed interface TimelineRemotePageResult {
    val requestId: TimelineRequestId
    val selectionGeneration: TimelineSelectionGeneration

    data class Page(
        override val requestId: TimelineRequestId,
        override val selectionGeneration: TimelineSelectionGeneration,
        val records: List<TimelineRemoteRecord>,
        val nextContinuation: TimelineContinuation?,
        val hasMore: Boolean,
        val decodedBodyBytes: Long,
    ) : TimelineRemotePageResult

    data class NoProgress(
        override val requestId: TimelineRequestId,
        override val selectionGeneration: TimelineSelectionGeneration,
        val continuation: TimelineContinuation,
    ) : TimelineRemotePageResult
}

data class TimelinePageProgress(
    val previousContinuation: TimelineContinuation,
    val returnedIdentityCount: Int,
    val newIdentityCount: Int,
) {
    init {
        require(returnedIdentityCount >= 0)
        require(newIdentityCount in 0..returnedIdentityCount)
    }
}

object TimelineRemotePageProgressClassifier {
    fun classify(
        request: TimelineRemotePageRequest,
        page: TimelineRemotePageResult.Page,
        progress: TimelinePageProgress,
    ): TimelineRemotePageResult {
        require(progress.returnedIdentityCount == page.records.size)
        val advanced = page.nextContinuation != progress.previousContinuation
        val outcome = if (page.records.isNotEmpty() && progress.newIdentityCount == 0 && !advanced) {
            TimelineRemotePageResult.NoProgress(page.requestId, page.selectionGeneration, progress.previousContinuation)
        } else {
            page
        }
        TimelineRemotePageTelemetry.record(request, page, progress, advanced, outcome)
        return outcome
    }
}

sealed class TimelineRemotePageException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class InvalidRequest(message: String) : TimelineRemotePageException(message)
    class BudgetExceeded(message: String) : TimelineRemotePageException(message)
    class MalformedPage(message: String) : TimelineRemotePageException(message)
    class Transport(message: String, cause: Throwable? = null) : TimelineRemotePageException(message, cause)
}

object TimelineRemotePageAdapter {
    private val json = Json { encodeDefaults = true }

    suspend fun load(
        request: TimelineRemotePageRequest,
        fetch: suspend (limit: Int, before: String?, after: String?, order: String) -> List<LettaMessage>,
    ): TimelineRemotePageResult.Page = try {
        val before = (request.continuation as? TimelineContinuation.Before)?.messageId?.value
        val after = (request.continuation as? TimelineContinuation.After)?.messageId?.value
        val wireOrder = when (request.order) {
            TimelineRemoteOrder.OldestFirst -> "asc"
            TimelineRemoteOrder.NewestFirst -> "desc"
            TimelineRemoteOrder.Unknown -> throw TimelineRemotePageException.InvalidRequest("unknown order")
        }
        val messages = fetch(request.budget.maxMetadataRows, before, after, wireOrder)
        fromMessages(request, messages, hasMore = messages.size >= request.budget.maxMetadataRows)
    } catch (e: CancellationException) {
        throw e
    } catch (e: TimelineRemotePageException) {
        throw e
    } catch (e: Exception) {
        throw TimelineRemotePageException.Transport("timeline remote page transport failed", e)
    }

    fun fromMessages(
        request: TimelineRemotePageRequest,
        messages: List<LettaMessage>,
        hasMore: Boolean,
        explicitNextContinuation: TimelineContinuation? = null,
    ): TimelineRemotePageResult.Page {
        val canonical = messages.sortedWith(compareBy({ it.date.orEmpty() }, { it.otid ?: it.id }))
        val records = canonical.map { message ->
            TimelineRemoteRecord(
                identity = TimelineMessageId(message.id),
                message = message,
                encodedBodyBytes = json.encodeToString(LettaMessage.serializer(), message).encodeToByteArray().size.toLong(),
            )
        }
        val derived = explicitNextContinuation ?: if (hasMore) deriveContinuation(request, canonical) else null
        return TimelineRemotePageValidation.validate(
            TimelineRemotePageResult.Page(
                requestId = request.requestId,
                selectionGeneration = request.selectionGeneration,
                records = records,
                nextContinuation = derived,
                hasMore = hasMore,
                decodedBodyBytes = records.sumOf { it.encodedBodyBytes },
            ),
            request.budget,
        )
    }

    private fun deriveContinuation(
        request: TimelineRemotePageRequest,
        messages: List<LettaMessage>,
    ): TimelineContinuation? = when (request.continuation) {
        is TimelineContinuation.Before -> messages.firstOrNull()?.id?.let { TimelineContinuation.Before(TimelineMessageId(it)) }
        is TimelineContinuation.After -> messages.lastOrNull()?.id?.let { TimelineContinuation.After(TimelineMessageId(it)) }
        TimelineContinuation.Initial -> when (request.order) {
            TimelineRemoteOrder.NewestFirst -> messages.firstOrNull()?.id?.let { TimelineContinuation.Before(TimelineMessageId(it)) }
            TimelineRemoteOrder.OldestFirst -> messages.lastOrNull()?.id?.let { TimelineContinuation.After(TimelineMessageId(it)) }
            TimelineRemoteOrder.Unknown -> null
        }
    }
}

private object TimelineRemotePageValidation {
    fun validate(page: TimelineRemotePageResult.Page, budget: TimelinePageBudget): TimelineRemotePageResult.Page {
        if (page.records.size > budget.maxMetadataRows) throw TimelineRemotePageException.BudgetExceeded("metadata row budget exceeded")
        if (page.decodedBodyBytes > budget.maxDecodedBodyBytes) throw TimelineRemotePageException.BudgetExceeded("decoded body byte budget exceeded")
        if (page.hasMore && page.nextContinuation == null) throw TimelineRemotePageException.MalformedPage("hasMore requires next continuation")
        if (!page.hasMore && page.nextContinuation != null) throw TimelineRemotePageException.MalformedPage("terminal page cannot carry continuation")
        return page
    }
}

private object TimelineRemotePageTelemetry {
    fun record(
        request: TimelineRemotePageRequest,
        page: TimelineRemotePageResult.Page,
        progress: TimelinePageProgress,
        advanced: Boolean,
        outcome: TimelineRemotePageResult,
    ) {
        Telemetry.event(
            "TimelineRemotePage", "pageResult",
            "scopeHash" to hash(request.scope.storageKey),
            "requestIdHash" to hash(request.requestId.value),
            "selectionGeneration" to request.selectionGeneration.value,
            "order" to request.order.name,
            "continuationKind" to request.continuation.kind(),
            "nextContinuationKind" to page.nextContinuation?.kind(),
            "recordCount" to page.records.size,
            "newIdentityCount" to progress.newIdentityCount,
            "decodedBodyBytes" to page.decodedBodyBytes,
            "hasMore" to page.hasMore,
            "continuationAdvanced" to advanced,
            "duplicateOnly" to (page.records.isNotEmpty() && progress.newIdentityCount == 0),
            "outcome" to if (outcome is TimelineRemotePageResult.NoProgress) "no_progress" else "page",
        )
    }

    private fun TimelineContinuation.kind(): String = when (this) {
        TimelineContinuation.Initial -> "initial"
        is TimelineContinuation.Before -> "before"
        is TimelineContinuation.After -> "after"
    }

    private fun hash(value: String): Long {
        var result = 0xcbf29ce484222325UL.toLong()
        value.forEach { result = (result xor it.code.toLong()) * 0x100000001b3L }
        return result
    }
}
