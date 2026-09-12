package com.letta.mobile.data.timeline

import com.letta.mobile.data.timeline.snapshot.TimelineScope

/**
 * The one body shape this client can decode into a rendered message. Anything else is durable but
 * opaque: it has no renderer and no pager, so it belongs nowhere in the conversation.
 */
const val TIMELINE_EVENT_CONTENT_TYPE = "application/vnd.letta.timeline-event+json;version=1"

/** Revision is the metadata PAGE revision, not the individual row revision. */
data class TimelineBodyReference(
    val scope: TimelineScope,
    val key: TimelinePageKey,
    val pointer: TimelineBodyPointer,
    val contentType: String,
    val revision: Long,
)

data class TimelineBodyChunk(
    val reference: TimelineBodyReference,
    val offset: Long,
    val bytes: ByteArray,
) {
    val nextOffset: Long get() = offset + bytes.size
    val isLast: Boolean get() = nextOffset == reference.pointer.encodedBytes
}

/** Complete small bodies or empty deferred slots, aligned with metadata; never serialized prefixes. */
data class TimelineBodyPage(val metadata: TimelineMetadataPage, val bodies: List<ByteArray>)

class TimelineBoundedReader(private val store: TimelineBoundedStore) {
    suspend fun preview(
        scope: TimelineScope,
        position: TimelineReadPosition,
        budget: TimelinePageBudget,
    ): TimelineBodyPage = store.read(scope) {
        val page = metadata(position, budget.maxMetadataRows)
        require(page.rows.size <= budget.maxMetadataRows)
        require(page.rows.zipWithNext().all { (a, b) -> a.key < b.key })
        var remaining = minOf(budget.maxDecodedBodyBytes, MAX_PAGE_BODY_BYTES)
        val bodies = page.rows.map { row ->
            // Serialized prefixes are not events. Defer the entire body if it cannot fit.
            val size = if (row.body.encodedBytes <= minOf(remaining, 16L * 1024)) {
                row.body.encodedBytes.toInt()
            } else 0
            val bytes = if (size == 0) byteArrayOf() else body(row.body, 0, size)
            require(bytes.size == size) { "Incomplete preview" }
            remaining -= size
            bytes
        }
        TimelineBodyPage(page, bodies)
    }

    /**
     * Raw serialized chunk, NOT a renderable event or independently decodable UTF-8/JSON.
     * The reference pins scope, key, pointer (including length), content type and page revision.
     * Every call revalidates in one snapshot; callers must discard prior chunks on stale failure.
     * No accumulation or cache is retained here. EOF is allowed only at the declared length.
     */
    suspend fun readChunk(
        reference: TimelineBodyReference,
        offset: Long,
        maxBytes: Int,
    ): TimelineBodyChunk {
        require(offset >= 0 && offset <= reference.pointer.encodedBytes) { "Invalid body offset" }
        require(maxBytes in 1..MAX_CHUNK_BYTES) { "Invalid chunk limit" }
        return store.read(reference.scope) {
            val page = metadata(TimelineReadPosition.Around(reference.key), 1)
            check(page.rows.size <= 1) { "Metadata budget exceeded" }
            check(page.revision == reference.revision) { "Stale body revision" }
            val row = page.rows.singleOrNull()
            check(row != null && row.key == reference.key && row.body == reference.pointer &&
                row.contentType == reference.contentType) { "Stale body pointer" }
            val requested = minOf(maxBytes.toLong(), reference.pointer.encodedBytes - offset).toInt()
            val bytes = if (requested == 0) byteArrayOf() else body(reference.pointer, offset, requested)
            check(bytes.size <= requested && (requested == 0 || bytes.isNotEmpty())) { "Invalid body chunk" }
            TimelineBodyChunk(reference, offset, bytes)
        }
    }

    companion object {
        const val MAX_PAGE_BODY_BYTES: Long = 2L * 1024 * 1024
        const val MAX_CHUNK_BYTES: Int = 64 * 1024
    }

    suspend fun load(
        scope: TimelineScope,
        position: TimelineReadPosition,
        budget: TimelinePageBudget,
    ): TimelineBodyPage = store.read(scope) {
        val page = metadata(position, budget.maxMetadataRows)
        require(page.rows.size <= budget.maxMetadataRows) { "Metadata budget exceeded" }
        require(page.rows.zipWithNext().all { (a, b) -> a.key < b.key }) { "Invalid ledger ordering" }
        var remaining = minOf(budget.maxDecodedBodyBytes, MAX_PAGE_BODY_BYTES)
        // Validate the whole selection before allocating any body. Oversized bodies use the chunk API.
        for (row in page.rows) {
            require(row.body.encodedBytes <= remaining && row.body.encodedBytes <= Int.MAX_VALUE) {
                "Body budget exceeded; use bounded chunk access"
            }
            remaining -= row.body.encodedBytes
        }
        val bodies = page.rows.map { row ->
            val bytes = ByteArray(row.body.encodedBytes.toInt())
            var offset = 0
            while (offset < bytes.size) {
                val requested = minOf(64 * 1024, bytes.size - offset)
                val chunk = body(row.body, offset.toLong(), requested)
                require(chunk.isNotEmpty() && chunk.size <= requested) { "Incomplete or oversized body" }
                chunk.copyInto(bytes, offset)
                offset += chunk.size
            }
            bytes
        }
        TimelineBodyPage(page, bodies)
    }
}
