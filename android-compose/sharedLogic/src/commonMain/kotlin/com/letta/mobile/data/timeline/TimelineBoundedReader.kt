package com.letta.mobile.data.timeline

import com.letta.mobile.data.timeline.snapshot.TimelineScope

/** Raw bounded page; decoding and evidence policy remain shared, outside the platform backend. */
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
        var remaining = budget.maxDecodedBodyBytes
        val bodies = page.rows.map { row ->
            val size = minOf(row.body.encodedBytes, remaining, 16L * 1024).toInt()
            val bytes = if (size == 0) byteArrayOf() else body(row.body, 0, size)
            require(bytes.size == size) { "Incomplete preview" }
            remaining -= size
            bytes
        }
        TimelineBodyPage(page, bodies)
    }

    suspend fun load(
        scope: TimelineScope,
        position: TimelineReadPosition,
        budget: TimelinePageBudget,
    ): TimelineBodyPage = store.read(scope) {
        val page = metadata(position, budget.maxMetadataRows)
        require(page.rows.size <= budget.maxMetadataRows) { "Metadata budget exceeded" }
        require(page.rows.zipWithNext().all { (a, b) -> a.key < b.key }) { "Invalid ledger ordering" }
        var remaining = budget.maxDecodedBodyBytes
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
