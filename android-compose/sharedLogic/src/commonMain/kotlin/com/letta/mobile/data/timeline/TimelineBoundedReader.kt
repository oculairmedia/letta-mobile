package com.letta.mobile.data.timeline

import com.letta.mobile.data.timeline.snapshot.TimelineScope

/** Raw bounded page; decoding and evidence policy remain shared, outside the platform backend. */
data class TimelineBodyPage(val metadata: TimelineMetadataPage, val bodies: List<ByteArray>)

class TimelineBoundedReader(private val store: TimelineBoundedStore) {
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
            val bytes = body(row.body, 0, row.body.encodedBytes.toInt())
            require(bytes.size.toLong() == row.body.encodedBytes) { "Incomplete or oversized body" }
            bytes
        }
        TimelineBodyPage(page, bodies)
    }
}
