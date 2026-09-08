package com.letta.mobile.data.timeline

import com.letta.mobile.data.model.MessageContentPart
import com.letta.mobile.data.timeline.snapshot.TimelineScope
import com.letta.mobile.data.timeline.snapshot.TimelineSnapshotCodec
import kotlinx.serialization.Serializable

/** Bounded durable optimistic state, independent of screen residency and settled history. */
class CanonicalPendingLocalStore(private val store: TimelineBoundedStore) {
    @Serializable
    data class Record(
        val otid: String,
        val content: String,
        val attachments: List<MessageContentPart.Image>,
        val sentAt: String,
        val delivery: Delivery = Delivery.Sending,
    )

    @Serializable
    enum class Delivery { Sending, Sent, Failed }

    @Serializable
    private data class Envelope(val version: Int = 1, val records: List<Record>)

    suspend fun load(scope: TimelineScope): List<Record> = store.read(scope) { readPending() }

    /** Persistence failure propagates to the caller before a transport send may be accepted. */
    suspend fun save(scope: TimelineScope, record: Record) {
        require(record.otid.isNotBlank())
        store.transaction(scope) {
            val previous = readPending()
            val existing = previous.firstOrNull { it.otid == record.otid }
            if (existing != null) {
                require(existing.content == record.content && existing.attachments == record.attachments) {
                    "Pending identity already belongs to another message"
                }
                return@transaction
            }
            writePending(previous + record)
        }
    }

    suspend fun mark(scope: TimelineScope, otid: String, delivery: Delivery) {
        store.transaction(scope) {
            val previous = readPending()
            val next = previous.map { if (it.otid == otid) it.copy(delivery = delivery) else it }
            if (next != previous) writePending(next)
        }
    }

    /** Called inside the same transaction that durably confirms the server echo. */
    internal suspend fun confirm(transaction: TimelineStoreTransaction, otid: String) {
        with(transaction) {
            val previous = readPending()
            val next = previous.filterNot { it.otid == otid }
            if (next != previous) writePending(next)
        }
    }

    private suspend fun TimelineStoreReader.readPending(): List<Record> {
        val bytes = evidence(KEY, MAX_BYTES) ?: return emptyList()
        require(bytes.size <= MAX_BYTES) { "Pending byte budget exceeded" }
        val envelope = TimelineSnapshotCodec.json.decodeFromString(Envelope.serializer(), bytes.decodeToString())
        require(envelope.version == 1) { "Unsupported pending state version" }
        require(envelope.records.size <= MAX_RECORDS) { "Pending row budget exceeded" }
        require(envelope.records.all { it.otid.isNotBlank() })
        require(envelope.records.map { it.otid }.toSet().size == envelope.records.size)
        return envelope.records
    }

    private suspend fun TimelineStoreTransaction.writePending(records: List<Record>) {
        require(records.size <= MAX_RECORDS) { "Pending row budget exceeded" }
        // Reject oversized inputs before serialization allocates another copy of their text.
        var textUnits = 0L
        for (record in records) {
            textUnits += record.content.length.toLong() + record.otid.length + record.sentAt.length
            require(record.attachments.size <= MAX_RECORDS) { "Pending attachment budget exceeded" }
            for (attachment in record.attachments) {
                textUnits += attachment.base64.length.toLong() + attachment.mediaType.length
                require(textUnits <= MAX_BYTES) { "Pending byte budget exceeded" }
            }
            require(textUnits <= MAX_BYTES) { "Pending byte budget exceeded" }
        }
        val bytes = TimelineSnapshotCodec.json.encodeToString(Envelope.serializer(), Envelope(records = records)).encodeToByteArray()
        require(bytes.size <= MAX_BYTES) { "Pending byte budget exceeded" }
        if (records.isEmpty()) deleteEvidence(KEY) else putEvidence(KEY, bytes)
    }

    companion object {
        private const val KEY = "pending/local/v1"
        const val MAX_RECORDS = 64
        const val MAX_BYTES = 2 * 1024 * 1024
    }
}
